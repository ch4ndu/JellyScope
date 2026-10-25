// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class LauncherRefreshOutcome { Success, Retry, NoSession, BoundaryChanged, Invalid }

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

    // Coordination stays on Main; IO returns per-run outcomes instead of mutating these fields.
    private var active: Pair<Session, LauncherBoundary>? = null
    private val ready = MutableStateFlow<Pair<LauncherBoundary, LauncherRefreshOutcome>?>(null)
    private var flight: RefreshFlight? = null
    private var lastSuccessElapsed = 0L

    init {
        scope.launch {
            sessions.sessionState.collectLatest { sessionState ->
                active = null
                ready.value = null
                val previous = flight
                previous?.followUpRequested = false
                previous?.completion?.complete(
                    if (
                        sessionState is SessionState.LoggedOut
                    ) {
                        LauncherRefreshOutcome.NoSession
                    } else {
                        LauncherRefreshOutcome.BoundaryChanged
                    },
                )
                previous?.job?.cancelAndJoin()
                lastSuccessElapsed = 0L
                val loggedIn = sessionState as? SessionState.LoggedIn
                val boundary = loggedIn?.let { LauncherBoundary(it.session.accountIdentity(), it.boundaryEpoch) }
                try {
                    registry.withBoundaryMutation { snapshots.hide() }
                    if (loggedIn != null && boundary != null) {
                        val session = loggedIn.session
                        val lease = registry.acquireWorkLease(boundary.account, boundary.epoch)
                        if (lease == null ||
                            snapshots.activate(
                                boundary,
                                launcherHash("${launcherAccountKey(boundary.account)}:${session.serverUrl}"),
                                lease,
                            ) == null
                        ) {
                            ready.value = boundary to LauncherRefreshOutcome.BoundaryChanged
                            return@collectLatest
                        }
                        prepareImages(session, boundary, lease, reset = false)
                        active = session to boundary
                        ready.value = boundary to LauncherRefreshOutcome.Success
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    launcherFailure("media-activate", failure)
                    if (boundary != null) ready.value = boundary to LauncherRefreshOutcome.NoSession
                }
            }
        }
    }

    fun refresh(
        manual: Boolean = false,
        fresh: Boolean = false,
    ) {
        scope.launch {
            val expected = currentBoundary() ?: return@launch
            requestRefresh(expected, foreground = true, fresh = manual || fresh, followUp = manual)
        }
    }

    suspend fun refreshAndAwait(expected: LauncherBoundary): LauncherRefreshOutcome =
        withContext(Dispatchers.Main.immediate) {
            requestRefresh(expected, foreground = false, fresh = false, followUp = false)
        }

    private suspend fun requestRefresh(
        expected: LauncherBoundary,
        foreground: Boolean,
        fresh: Boolean,
        followUp: Boolean,
    ): LauncherRefreshOutcome {
        val available =
            withTimeoutOrNull(10_000L) {
                combine(sessions.sessionState, ready) { sessionState, activated ->
                    when {
                        sessionState == SessionState.Restoring -> null
                        sessionState !is SessionState.LoggedIn -> LauncherRefreshOutcome.NoSession
                        LauncherBoundary(sessionState.session.accountIdentity(), sessionState.boundaryEpoch) != expected ->
                            LauncherRefreshOutcome.BoundaryChanged
                        else -> activated?.takeIf { it.first == expected }?.second
                    }
                }.filterNotNull().first()
            } ?: return LauncherRefreshOutcome.Retry
        if (available != LauncherRefreshOutcome.Success) return available
        while (true) {
            val current =
                active?.takeIf { it.second == expected && currentBoundary() == expected }
                    ?: return boundaryOutcome()
            val previous = flight
            if (previous != null && !previous.job.isActive) {
                previous.job.join()
                continue
            }
            if (previous == null && !fresh && lastSuccessElapsed != 0L && SystemClock.elapsedRealtime() - lastSuccessElapsed < 30_000L) {
                return LauncherRefreshOutcome.Success
            }
            val run = previous ?: startRefresh(current.first, expected, foreground)
            run.foregroundAttached = run.foregroundAttached || foreground
            if (followUp && previous != null) run.followUpRequested = true
            if (foreground) return run.completion.await()
            run.workerWaiters += 1
            try {
                return run.completion.await()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    run.workerWaiters -= 1
                    if (run.workerWaiters == 0 && !run.foregroundAttached && !run.completion.isCompleted && flight === run) {
                        Log.i("Launcher", "stage=media-refresh event=cancelled reason=worker-detached")
                        run.job.cancel()
                    }
                }
            }
        }
    }

    private fun startRefresh(
        session: Session,
        boundary: LauncherBoundary,
        foreground: Boolean,
    ): RefreshFlight {
        val run = RefreshFlight(foreground)
        flight = run
        run.job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val outcome = performRefresh(session, boundary)
                    currentCoroutineContext().ensureActive()
                    Log.i("Launcher", "stage=media-refresh event=completed outcome=${outcome.name}")
                    run.completion.complete(outcome)
                } catch (cancelled: CancellationException) {
                    run.completion.cancel(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    launcherFailure("media-refresh", failure)
                    run.completion.complete(if (currentBoundary() == boundary) LauncherRefreshOutcome.Retry else boundaryOutcome())
                } finally {
                    if (flight === run) flight = null
                    if (run.followUpRequested && currentBoundary() == boundary && currentCoroutineContext().isActive) {
                        refresh(manual = true)
                    }
                }
            }
        run.job.start()
        return run
    }

    private suspend fun performRefresh(
        session: Session,
        boundary: LauncherBoundary,
    ): LauncherRefreshOutcome {
        val lease = registry.acquireWorkLease(boundary.account, boundary.epoch) ?: return boundaryOutcome()
        // Once a pending marker is established, retain its generation even if the Worker stops.
        val request = withContext(NonCancellable) { snapshots.beginRefresh(boundary, lease) }
        var outcome = LauncherRefreshOutcome.Retry
        var durable = false
        if (request != null) {
            try {
                outcome = withContext(Dispatchers.IO) { refreshRows(session, boundary, lease, request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                launcherFailure("media-refresh", failure)
            } finally {
                withContext(NonCancellable) {
                    try {
                        durable = snapshots.finish(boundary, lease, request, outcome == LauncherRefreshOutcome.Success)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        launcherFailure("media-finish", failure)
                    }
                }
            }
        }
        if (currentBoundary() != boundary || active?.second != boundary || registry.withGuardedLease(lease) { true } != true) {
            return boundaryOutcome()
        }
        if (outcome == LauncherRefreshOutcome.Success) {
            if (!durable) return LauncherRefreshOutcome.Retry
            lastSuccessElapsed = SystemClock.elapsedRealtime()
        }
        return outcome
    }

    private fun currentBoundary(): LauncherBoundary? =
        (sessions.sessionState.value as? SessionState.LoggedIn)?.let { LauncherBoundary(it.session.accountIdentity(), it.boundaryEpoch) }

    private fun boundaryOutcome(): LauncherRefreshOutcome =
        if (
            sessions.sessionState.value is SessionState.LoggedOut
        ) {
            LauncherRefreshOutcome.NoSession
        } else {
            LauncherRefreshOutcome.BoundaryChanged
        }

    private suspend fun refreshRows(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
    ): LauncherRefreshOutcome {
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
                    if (confirmInvalid(session, boundary, lease, request)) return LauncherRefreshOutcome.Invalid
                } else {
                    snapshots.failRefresh(boundary, lease, request, configuration)
                }
                launcherFailure("policy-refresh", failure)
                return LauncherRefreshOutcome.Retry
            }
        val layout =
            try {
                snapshots.adoptLayout(boundary, lease, request, policy.first, policy.second, configuration)
                    ?: return LauncherRefreshOutcome.BoundaryChanged
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
                            if (!snapshots.isCurrent(boundary, lease, row)) return@withPermit LauncherRefreshOutcome.BoundaryChanged
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
                                        LauncherQuery.Favorites,
                                        LauncherQuery.Library,
                                        LauncherQuery.LatestMovies,
                                        LauncherQuery.LatestEpisodes,
                                        LauncherQuery.LatestSeries,
                                        ->
                                            api
                                                .getItems(
                                                    context,
                                                    ItemsQuery(
                                                        recursive = true,
                                                        includeItemTypes =
                                                            when (row.layout.query) {
                                                                LauncherQuery.LatestMovies -> listOf("Movie")
                                                                LauncherQuery.LatestEpisodes -> listOf("Episode")
                                                                LauncherQuery.LatestSeries -> listOf("Series")
                                                                else -> listOf("Movie", "Episode")
                                                            },
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
                                LauncherRefreshOutcome.Success
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
                                    if (failure is JellyfinApiException.Unauthorized && confirmInvalid(session, boundary, lease, request)) {
                                        return@withPermit LauncherRefreshOutcome.Invalid
                                    }
                                } else {
                                    snapshots.commit(boundary, lease, row, null, LauncherRowStatus.Failed)
                                }
                                launcherFailure("row-refresh", failure)
                                LauncherRefreshOutcome.Retry
                            }
                        }
                    }
                }.map { result ->
                    try {
                        result.await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        launcherFailure("row-refresh", failure)
                        LauncherRefreshOutcome.Retry
                    }
                }.let { outcomes ->
                    when {
                        LauncherRefreshOutcome.Invalid in outcomes -> LauncherRefreshOutcome.Invalid
                        LauncherRefreshOutcome.BoundaryChanged in outcomes -> LauncherRefreshOutcome.BoundaryChanged
                        outcomes.all { it == LauncherRefreshOutcome.Success } -> LauncherRefreshOutcome.Success
                        else -> LauncherRefreshOutcome.Retry
                    }
                }
        }
    }

    private suspend fun confirmInvalid(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
    ): Boolean {
        try {
            val user = api.getCurrentUser(session.requestContext())
            if (user.id != session.userId) throw JellyfinApiException.Unauthorized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.isDenied()) {
                snapshots.rejectAll(boundary, lease, request)
                invalidateArtwork(boundary, lease)
                Log.i("Launcher", "stage=media-refresh event=rejected reason=invalid-session")
                return registry.withGuardedLease(lease) { invalid.trySend(boundary).isSuccess } == true
            }
        }
        return false
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

    private class RefreshFlight(
        var foregroundAttached: Boolean,
    ) {
        val completion = CompletableDeferred<LauncherRefreshOutcome>()
        lateinit var job: Job
        var workerWaiters = 0
        var followUpRequested = false
    }
}
