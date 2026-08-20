// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.WatchNextSyncState
import com.jellyscope.core.data.local.WatchNextSyncStore
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.WatchNextOrigin
import com.jellyscope.core.domain.playback.selectWatchNextCandidates
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

@SuppressLint("RestrictedApi")
internal class WatchNextProviderSync(
    context: Context,
    private val getContinueWatchingUseCase: GetContinueWatchingUseCase,
    private val getNextUpUseCase: GetNextUpUseCase,
    private val syncStore: WatchNextSyncStore,
    imageUrlBuilder: JellyfinImageUrlBuilder,
    httpClient: HttpClient,
    authHeaderProvider: AuthHeaderProvider,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val posterCache =
        WatchNextPosterCache(
            context = appContext,
            imageUrlBuilder = imageUrlBuilder,
            httpClient = httpClient,
            authHeaderProvider = authHeaderProvider,
        )

    suspend fun sync(
        session: Session,
        lease: AccountWorkLease,
    ) {
        withContext(Dispatchers.IO) {
            val stagingDirectory = WatchNextContract.stagingDirectory(appContext, session.accountIdentity())
            try {
                val desiredItems = loadDesiredItems()
                val desiredPrograms =
                    desiredItems.map { desiredItem ->
                        desiredItem.toProgram(
                            context = appContext,
                            session = session,
                            posterCache = posterCache,
                            stagingDirectory = stagingDirectory,
                        )
                    }

                serverScopedStoreRegistry.withGuardedLease(lease) {
                    desiredPrograms.forEach { program -> program.stagedPoster?.let(posterCache::commitPoster) }
                    reconcileProviderPrograms(session, desiredPrograms)
                    persistSyncState(session = session, desiredItems = desiredItems)
                }
            } finally {
                stagingDirectory.deleteRecursively()
            }
        }
    }

    private suspend fun loadDesiredItems(): List<DesiredWatchNextItem> =
        coroutineScope {
            val continueWatching = async { getContinueWatchingUseCase().getOrThrow() }
            val nextUp = async { getNextUpUseCase().getOrThrow() }
            selectWatchNextCandidates(
                continueWatching = continueWatching.await(),
                nextUp = nextUp.await(),
                limit = MAX_WATCH_NEXT_ITEMS,
            ).map { candidate ->
                DesiredWatchNextItem(
                    item = candidate.item,
                    watchNextType =
                        when (candidate.origin) {
                            WatchNextOrigin.ContinueWatching ->
                                TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                            WatchNextOrigin.NextUp -> candidate.item.nextEpisodeWatchNextType()
                        },
                )
            }
        }

    private fun reconcileProviderPrograms(
        session: Session,
        desiredPrograms: List<DesiredWatchNextProgram>,
    ) {
        val accountIdentity = session.accountIdentity()
        val desiredIds = desiredPrograms.map { program -> WatchNextContract.accountPayload(accountIdentity, program.itemId) }.toSet()
        val existingPrograms = readCurrentPrograms()
        val keptPrograms = mutableMapOf<String, WatchNextProgram>()
        val programsToDelete = mutableListOf<WatchNextProgram>()

        existingPrograms.forEach { program ->
            val ownership = WatchNextContract.parseAccountPayload(program.internalProviderId)
            val ownedByThisApp = program.packageName == appContext.packageName
            when {
                !ownedByThisApp -> Unit
                ownership == null -> programsToDelete += program
                ownership.serverId != accountIdentity.serverId || ownership.userId != accountIdentity.userId ->
                    programsToDelete += program
                !program.isBrowsable -> programsToDelete += program
                WatchNextContract.accountPayload(accountIdentity, ownership.itemId) !in desiredIds ->
                    programsToDelete += program
                keptPrograms.containsKey(program.internalProviderId) -> programsToDelete += program
                else -> keptPrograms[program.internalProviderId] = program
            }
        }

        deletePrograms(programsToDelete.distinctBy { program -> program.id })

        val programsToInsert = mutableListOf<ContentValues>()
        desiredPrograms.forEach { desired ->
            val ownershipId = WatchNextContract.accountPayload(accountIdentity, desired.itemId)
            val existing = keptPrograms[ownershipId]
            if (existing == null) {
                programsToInsert += desired.contentValues
            } else {
                contentResolver.update(
                    TvContractCompat.buildWatchNextProgramUri(existing.id),
                    desired.contentValuesForReconciliationUpdate(existing),
                    null,
                    null,
                )
            }
        }

        if (programsToInsert.isNotEmpty()) {
            contentResolver.bulkInsert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                programsToInsert.toTypedArray(),
            )
        }
    }

    private fun readCurrentPrograms(): List<WatchNextProgram> {
        val cursor =
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null,
            ) ?: return emptyList()

        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    add(WatchNextProgram.fromCursor(cursor))
                }
            }
        }
    }

    private fun deletePrograms(programs: List<WatchNextProgram>) {
        programs.forEach { program ->
            contentResolver.delete(
                TvContractCompat.buildWatchNextProgramUri(program.id),
                null,
                null,
            )
        }
    }

    private suspend fun persistSyncState(
        session: Session,
        desiredItems: List<DesiredWatchNextItem>,
    ) {
        val syncedAt = System.currentTimeMillis()
        syncStore.clear(session.serverId, session.userId)
        desiredItems.forEach { desiredItem ->
            val item = desiredItem.item
            syncStore.upsert(
                serverId = session.serverId,
                userId = session.userId,
                state =
                    WatchNextSyncState(
                        itemId = item.id,
                        playbackPositionTicks = item.playbackPositionTicks,
                        played = item.played,
                        lastSyncedAtEpochMs = syncedAt,
                    ),
            )
        }
    }
}
