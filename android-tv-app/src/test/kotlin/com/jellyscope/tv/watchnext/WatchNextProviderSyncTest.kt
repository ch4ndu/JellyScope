// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.WatchNextSyncState
import com.jellyscope.core.data.local.WatchNextSyncStore
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WatchNextProviderSyncTest {
    @Test
    fun workerFailureEmitsOnlyTypedSafeDiagnostic() {
        val messages = mutableListOf<String>()
        val writer =
            object : LogWriter() {
                override fun log(
                    severity: Severity,
                    message: String,
                    tag: String,
                    throwable: Throwable?,
                ) {
                    if (tag == DiagnosticTag.WatchNextSyncWorker.wireValue) {
                        messages += message
                        assertEquals(null, throwable)
                    }
                }
            }

        try {
            Logger.setLogWriters(writer)
            logWatchNextSyncFailure(IllegalStateException("watch-next server identity"))
        } finally {
            Logger.setLogWriters(emptyList())
        }

        val message = messages.single()
        assertEquals("stage=sync event=failed operation=watchNextSync exceptionType=IllegalStateException", message)
        assertEquals(message, LogScrubber.capture(DiagnosticTag.WatchNextSyncWorker.wireValue, message))
        assertFalse(message.contains("watch-next server identity"))
        assertFalse(message.contains("https://"))
    }

    @Test
    fun staleLeaseCannotCommitProviderOrRoomState() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val registry = ServerScopedStoreRegistry()
            val account = AccountIdentity("server-1", "user-1")
            val replacement = AccountIdentity("server-2", "user-2")
            registry.transitionToAccount(account, boundaryEpoch = 1L)
            val lease = registry.acquireWorkLease(account, boundaryEpoch = 1L)
            check(lease != null)
            registry.transitionToAccount(replacement, boundaryEpoch = 2L)
            val syncStore = RecordingWatchNextSyncStore()
            val httpClient = HttpClient()
            try {
                WatchNextProviderSync(
                    context = context,
                    getContinueWatchingUseCase =
                        com.jellyscope.core.domain.usecase
                            .GetContinueWatchingUseCase(EmptyMediaRepository),
                    getNextUpUseCase =
                        com.jellyscope.core.domain.usecase
                            .GetNextUpUseCase(EmptyMediaRepository),
                    syncStore = syncStore,
                    imageUrlBuilder = JellyfinImageUrlBuilder(),
                    httpClient = httpClient,
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?): String = "MediaBrowser Test"
                        },
                    serverScopedStoreRegistry = registry,
                ).sync(session(account), lease)

                assertTrue(syncStore.operations.isEmpty())
            } finally {
                httpClient.close()
                WatchNextContract.posterRootDirectory(context).deleteRecursively()
            }
        }
}

private val EmptyMediaRepository =
    object : MediaRepository {
        override suspend fun getLibraries() = Result.success(emptyList<Library>())

        override suspend fun getContinueWatching() = Result.success(emptyList<MediaItem>())

        override suspend fun getNextUp(
            seriesId: String?,
            includeResumable: Boolean,
        ) = Result.success(emptyList<MediaItem>())

        override suspend fun getItemDetail(
            itemId: String,
            includePlaybackFields: Boolean,
        ) = error("unused")

        override suspend fun getRelated(
            itemId: String,
            kind: MediaKind,
            seriesId: String?,
        ) = Result.success(emptyList<MediaItem>())

        override suspend fun getSeasons(seriesId: String) = Result.success(emptyList<MediaItem>())

        override suspend fun getEpisodes(
            seriesId: String,
            seasonId: String,
            seasonIndex: Int?,
        ) = Result.success(emptyList<MediaItem>())

        override suspend fun getRecentlyAdded() = Result.success(emptyList<MediaItem>())

        override suspend fun getRibbonItems(
            ribbon: MediaRibbon,
            limit: Int,
        ) = Result.success(emptyList<MediaItem>())

        override suspend fun getFavorites() = Result.success(emptyList<MediaItem>())

        override suspend fun search(query: FindQuery) = error("unused")

        override suspend fun findPersons(term: String) = Result.success(emptyList<Person>())

        override suspend fun getPlaybackInfo(
            itemId: String,
            mediaSourceId: String?,
            startTimeTicks: Long,
            audioStreamIndex: Int?,
            subtitleStreamIndex: Int?,
            maxStreamingBitrate: Long?,
        ) = error("unused")

        override suspend fun setPlayed(
            itemId: String,
            played: Boolean,
        ) = Result.success(Unit)
    }

private class RecordingWatchNextSyncStore : WatchNextSyncStore {
    val operations = mutableListOf<String>()

    override suspend fun get(
        serverId: String,
        itemId: String,
    ): WatchNextSyncState? = null

    override suspend fun upsert(
        serverId: String,
        state: WatchNextSyncState,
    ) {
        operations += "upsert"
    }

    override suspend fun list(serverId: String): List<WatchNextSyncState> = emptyList()

    override suspend fun clear(serverId: String) {
        operations += "clear"
    }

    override suspend fun clearServerScoped() = Unit
}

private fun session(identity: AccountIdentity) =
    Session(
        serverUrl = "https://${identity.serverId}.example",
        serverId = identity.serverId,
        serverName = identity.serverId,
        userId = identity.userId,
        userName = identity.userId,
        accessToken = "token",
        deviceId = "device",
    )
