// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.os.SystemClock
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.ItemFilter
import com.jellyscope.core.data.remote.ItemsQuery
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.toDomainMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class LauncherMediaRepository(
    private val api: JellyfinApi,
    private val sessions: SessionRepository,
    private val registry: ServerScopedStoreRegistry,
    private val snapshots: LauncherSnapshotStore,
    private val images: LauncherImages,
    private val configurations: LauncherConfigurationRepository,
    private val scope: CoroutineScope,
) {
    val state = snapshots.state
    private val invalid = Channel<LauncherBoundary>(Channel.BUFFERED)
    val invalidSessions = invalid.receiveAsFlow()
    private var active: Pair<Session, LauncherBoundary>? = null
    private var refreshJob: Job? = null
    private var followUpRequested = false
    private var lastSuccessElapsed = 0L

    init {
        scope.launch {
            sessions.sessionState.collectLatest { sessionState ->
                active = null
                followUpRequested = false
                refreshJob?.cancelAndJoin()
                refreshJob = null
                registry.withBoundaryMutation { snapshots.hide() }
                lastSuccessElapsed = 0L
                if (sessionState is SessionState.LoggedIn) {
                    val session = sessionState.session
                    val boundary = LauncherBoundary(session.accountIdentity(), sessionState.boundaryEpoch)
                    val lease = registry.acquireWorkLease(boundary.account, boundary.epoch) ?: return@collectLatest
                    active = session to boundary
                    snapshots.activate(boundary, launcherHash("${launcherAccountKey(boundary.account)}:${session.serverUrl}"), lease)
                    prepareImages(session, boundary, lease, reset = false)
                    refresh(manual = true)
                }
            }
        }
    }

    fun refresh(manual: Boolean = false) {
        val (session, boundary) = active ?: return
        if (refreshJob?.isActive == true) {
            followUpRequested = followUpRequested || manual
            return
        }
        if (!manual && lastSuccessElapsed != 0L && SystemClock.elapsedRealtime() - lastSuccessElapsed < 30_000L) return
        refreshJob =
            scope.launch {
                val lease = registry.acquireWorkLease(boundary.account, boundary.epoch) ?: return@launch
                val request = snapshots.beginRefresh(boundary, lease) ?: return@launch
                var success = false
                try {
                    success = withContext(Dispatchers.IO) { refreshRows(session, boundary, lease, request) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    launcherFailure("media-refresh", failure)
                } finally {
                    try {
                        val durable = snapshots.finish(boundary, lease, request, success)
                        if (success && durable) lastSuccessElapsed = SystemClock.elapsedRealtime()
                    } finally {
                        refreshJob = null
                        val requested = followUpRequested
                        followUpRequested = false
                        if (requested && currentCoroutineContext().isActive) refresh(manual = true)
                    }
                }
            }
    }

    private suspend fun refreshRows(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
    ): Boolean {
        val context = session.requestContext()
        val configuration = configurations.fetch(session)
        val policy =
            try {
                val user = api.getCurrentUser(context)
                if (user.id != session.userId) throw JellyfinApiException.Unauthorized
                val views =
                    api
                        .getUserViews(context)
                        .items
                        .mapNotNull { it.id?.let(::canonicalLauncherLibraryId) }
                        .distinct()
                        .sorted()
                launcherHash("${user.policy?.maxParentalRating}:${views.joinToString { "${it.length}:$it" }}") to views.toSet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.isDenied()) {
                    snapshots.rejectAll(boundary, lease, request)
                    invalidateArtwork(boundary, lease)
                    confirmInvalid(session, boundary, lease, request)
                } else {
                    snapshots.failRefresh(boundary, lease, request, configuration)
                }
                launcherFailure("policy-refresh", failure)
                return false
            }
        val layout =
            try {
                snapshots.adoptLayout(boundary, lease, request, policy.first, policy.second, configuration) ?: return false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                invalidateArtwork(boundary, lease)
                throw failure
            }
        prepareImages(session, boundary, lease, reset = layout.invalidateArtwork)
        val permits = Semaphore(2)
        return supervisorScope {
            layout.work
                .map { row ->
                    async {
                        permits.withPermit {
                            if (!snapshots.isCurrent(boundary, lease, row)) return@withPermit false
                            try {
                                val items =
                                    when (row.layout.query) {
                                        LauncherQuery.Resume ->
                                            api.getResumeItems(context, limit = row.layout.limit, parentId = row.layout.libraryId).items
                                        LauncherQuery.NextUp ->
                                            api
                                                .getNextUp(
                                                    context,
                                                    limit = row.layout.limit,
                                                    parentId = row.layout.libraryId,
                                                    includeResumable = false,
                                                ).items
                                        LauncherQuery.Latest ->
                                            api.getLatestItems(context, limit = row.layout.limit, parentId = row.layout.libraryId)
                                        LauncherQuery.Favorites, LauncherQuery.Library ->
                                            api
                                                .getItems(
                                                    context,
                                                    ItemsQuery(
                                                        recursive = true,
                                                        includeItemTypes = listOf("Movie", "Episode"),
                                                        limit = row.layout.limit,
                                                        parentId = row.layout.libraryId,
                                                        sortBy = "DateCreated",
                                                        sortOrder = "Descending",
                                                        enableTotalRecordCount = false,
                                                        filters =
                                                            if (row.layout.query == LauncherQuery.Favorites) {
                                                                listOf(ItemFilter.IsFavorite)
                                                            } else {
                                                                emptyList()
                                                            },
                                                    ),
                                                ).items
                                    }
                                val cards =
                                    items
                                        .asSequence()
                                        .mapNotNull(::card)
                                        .distinctBy { it.id }
                                        .take(row.layout.limit)
                                        .toList()
                                snapshots.commit(boundary, lease, row, cards, LauncherRowStatus.Fresh)
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (failure.isDenied()) {
                                    try {
                                        snapshots.commit(boundary, lease, row, emptyList(), LauncherRowStatus.Denied)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (cleanupFailure: Exception) {
                                        launcherFailure("denied-row-cleanup", cleanupFailure)
                                    }
                                    prepareImages(session, boundary, lease, reset = true)
                                    if (failure is JellyfinApiException.Unauthorized) confirmInvalid(session, boundary, lease, request)
                                } else {
                                    snapshots.commit(boundary, lease, row, null, LauncherRowStatus.Failed)
                                }
                                launcherFailure("row-refresh", failure)
                                false
                            }
                        }
                    }
                }.awaitAll()
                .all { it }
        }
    }

    private suspend fun confirmInvalid(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
    ) {
        try {
            val user = api.getCurrentUser(session.requestContext())
            if (user.id != session.userId) throw JellyfinApiException.Unauthorized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.isDenied()) {
                snapshots.rejectAll(boundary, lease, request)
                invalidateArtwork(boundary, lease)
                registry.withGuardedLease(lease) { invalid.trySend(boundary) }
            }
        }
    }

    private suspend fun invalidateArtwork(
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
    ) {
        try {
            images.invalidate(boundary, lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("artwork-clear", failure)
        }
    }

    private suspend fun prepareImages(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
        reset: Boolean,
    ) {
        try {
            if (reset) images.reset(session, boundary, lease) else images.activate(session, boundary, lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("artwork-prepare", failure)
        }
    }

    private fun Exception.isDenied(): Boolean =
        this is JellyfinApiException.Unauthorized || this is JellyfinApiException.ServerError && statusCode == 403

    private fun card(dto: BaseItemDto): LauncherCard? {
        val item = dto.toDomainMediaItem() ?: return null
        if (item.id.length > 256) return null
        val backdrop = item.imageRefs.backdropTag?.takeIf { it.isNotBlank() && it.length <= 256 }
        val primary = item.imageRefs.primaryTag?.takeIf { it.isNotBlank() && it.length <= 256 }
        val progress =
            dto.runTimeTicks?.takeIf { it > 0L }?.let { duration ->
                dto.userData?.playbackPositionTicks?.let {
                    (it.toDouble() / duration)
                        .takeIf(
                            Double::isFinite,
                        )?.coerceIn(0.0, 1.0)
                        ?.toFloat()
                }
            }
        return LauncherCard(
            id = item.id,
            title = item.name.take(300),
            progress = progress,
            primaryTag = primary,
            backdropTag = backdrop,
        )
    }

    private fun Session.requestContext() = AuthenticatedRequestContext(serverUrl, userId, accessToken)
}
