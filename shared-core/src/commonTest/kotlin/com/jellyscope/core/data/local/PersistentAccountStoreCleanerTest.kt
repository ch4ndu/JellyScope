// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistentAccountStoreCleanerTest {
    @Test
    fun accountCleanupPreservesSiblingSessionAndTargetsAccountStores() =
        runTest {
            val first = storedSession("server-1", "user-1")
            val sibling = storedSession("server-1", "user-2")
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            sessionStore.writeSession(first)
            sessionStore.writeSession(sibling)
            val recent = RecordingRecentSearchStore()
            val watchNext = RecordingWatchNextStore()
            val subtitles = RecordingSubtitleSelectionStore()
            val overrides = RecordingPlayerBackendOverrideStore()
            val cleaner = cleaner(sessionStore, recent, watchNext, subtitles, overrides)

            cleaner.clearAccount(AccountIdentity("server-1", "user-1"))

            assertEquals(listOf(sibling), sessionStore.readAccounts())
            assertEquals(listOf("server-1|user-1"), recent.accountClears)
            assertEquals(listOf("server-1|user-1"), watchNext.accountClears)
            assertEquals(listOf("server-1|user-1"), subtitles.accountClears)
            assertTrue(overrides.serverClears.isEmpty())
            assertTrue(recent.serverClears.isEmpty())
        }

    @Test
    fun lastAccountCleanupClearsServerScopedOverrides() =
        runTest {
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            val overrides = RecordingPlayerBackendOverrideStore()
            val cleaner =
                cleaner(
                    sessionStore,
                    RecordingRecentSearchStore(),
                    RecordingWatchNextStore(),
                    RecordingSubtitleSelectionStore(),
                    overrides,
                )

            cleaner.clearServer("server-1")

            assertEquals(listOf("server-1"), overrides.serverClears)
        }

    @Test
    fun fullCleanupClearsEveryPersistentMembership() =
        runTest {
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            sessionStore.writeSession(storedSession("server-1", "user-1"))
            val recent = RecordingRecentSearchStore()
            val watchNext = RecordingWatchNextStore()
            val subtitles = RecordingSubtitleSelectionStore()
            val playback = RecordingPlaybackPreferencesStore()
            val sort = RecordingLibrarySortStore()
            val view = RecordingLibraryViewStore()
            val grid = RecordingGridSortStore()
            val overrides = RecordingPlayerBackendOverrideStore()
            val cleaner =
                PersistentAccountStoreCleaner(
                    sessionStore = sessionStore,
                    playbackPreferencesStore = playback,
                    playerBackendOverrideStore = overrides,
                    recentSearchStore = recent,
                    watchNextSyncStore = watchNext,
                    subtitleSelectionStore = subtitles,
                    librarySortStore = sort,
                    libraryViewPreferencesStore = view,
                    gridSortStore = grid,
                )

            cleaner.clearAll()

            assertEquals(null, sessionStore.readSession())
            assertTrue(playback.clearedAll)
            assertTrue(overrides.clearedAll)
            assertTrue(recent.clearedAll)
            assertTrue(watchNext.clearedAll)
            assertTrue(subtitles.clearedAll)
            assertTrue(sort.clearedAll)
            assertTrue(view.clearedAll)
            assertTrue(grid.clearedAll)
        }

    @Test
    fun legacyCredentialCleanupFailureRetainsPendingButEmptyEnvelopeStaysAuthoritative() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession("server-1", "user-1")
            sessionStore.writeSession(session)
            sessionStore.markLogoutPending()
            secureStore.failOnRemoveKey = "session_account:server-1|user-1"
            val cleaner =
                cleaner(
                    sessionStore,
                    RecordingRecentSearchStore(),
                    RecordingWatchNextStore(),
                    RecordingSubtitleSelectionStore(),
                    RecordingPlayerBackendOverrideStore(),
                )

            assertFailsWith<StoreCleanupException> {
                cleaner.clearAll()
            }

            assertTrue(sessionStore.isLogoutPending())
            assertEquals(null, sessionStore.readSession())
        }

    @Test
    fun fullCleanupAttemptsAllStoresAfterMultipleFailures() =
        runTest {
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            val playback = RecordingPlaybackPreferencesStore(IllegalStateException("playback failure"))
            val recent = RecordingRecentSearchStore(CancellationException("recent cancellation"))
            val watchNext = RecordingWatchNextStore()
            val subtitles = RecordingSubtitleSelectionStore()
            val sort = RecordingLibrarySortStore()
            val view = RecordingLibraryViewStore()
            val grid = RecordingGridSortStore()
            val overrides = RecordingPlayerBackendOverrideStore()
            val cleaner =
                PersistentAccountStoreCleaner(
                    sessionStore = sessionStore,
                    playbackPreferencesStore = playback,
                    playerBackendOverrideStore = overrides,
                    recentSearchStore = recent,
                    watchNextSyncStore = watchNext,
                    subtitleSelectionStore = subtitles,
                    librarySortStore = sort,
                    libraryViewPreferencesStore = view,
                    gridSortStore = grid,
                )

            val failure =
                assertFailsWith<StoreCleanupException> {
                    cleaner.clearAll()
                }

            assertEquals(2, failure.failures.size)
            assertTrue(watchNext.clearedAll)
            assertTrue(subtitles.clearedAll)
            assertTrue(sort.clearedAll)
            assertTrue(view.clearedAll)
            assertTrue(grid.clearedAll)
        }

    private fun cleaner(
        sessionStore: SessionStore,
        recent: RecordingRecentSearchStore,
        watchNext: RecordingWatchNextStore,
        subtitles: RecordingSubtitleSelectionStore,
        overrides: RecordingPlayerBackendOverrideStore = RecordingPlayerBackendOverrideStore(),
    ): PersistentAccountStoreCleaner =
        PersistentAccountStoreCleaner(
            sessionStore = sessionStore,
            playbackPreferencesStore = RecordingPlaybackPreferencesStore(),
            playerBackendOverrideStore = overrides,
            recentSearchStore = recent,
            watchNextSyncStore = watchNext,
            subtitleSelectionStore = subtitles,
            librarySortStore = RecordingLibrarySortStore(),
            libraryViewPreferencesStore = RecordingLibraryViewStore(),
            gridSortStore = RecordingGridSortStore(),
        )
}

internal class RecordingPlayerBackendOverrideStore(
    private val clearAllFailure: Throwable? = null,
) : PlayerBackendOverrideStore {
    var clearedAll = false
    val serverClears = mutableListOf<String>()

    override suspend fun get(
        serverId: String,
        itemId: String,
    ): PlayerBackend? = null

    override suspend fun save(
        serverId: String,
        itemId: String,
        backend: PlayerBackend?,
    ) = Unit

    override suspend fun delete(
        serverId: String,
        itemId: String,
    ) = Unit

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }

    override suspend fun clearServerScoped(serverId: String) {
        serverClears += serverId
    }
}

internal class RecordingRecentSearchStore(
    private val clearAllFailure: Throwable? = null,
) : RecentSearchStore {
    var clearedAll = false
    val serverClears = mutableListOf<String>()
    val accountClears = mutableListOf<String>()

    override suspend fun add(
        serverId: String,
        query: String,
    ) = Unit

    override suspend fun list(serverId: String): List<String> = emptyList()

    override suspend fun clear(serverId: String) {
        serverClears += serverId
    }

    override suspend fun clear(
        serverId: String,
        userId: String,
    ) {
        accountClears += "$serverId|$userId"
    }

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

internal class RecordingWatchNextStore(
    private val clearAllFailure: Throwable? = null,
) : WatchNextSyncStore {
    var clearedAll = false
    val accountClears = mutableListOf<String>()

    override suspend fun get(
        serverId: String,
        itemId: String,
    ): WatchNextSyncState? = null

    override suspend fun upsert(
        serverId: String,
        state: WatchNextSyncState,
    ) = Unit

    override suspend fun list(serverId: String): List<WatchNextSyncState> = emptyList()

    override suspend fun clear(serverId: String) = Unit

    override suspend fun clear(
        serverId: String,
        userId: String,
    ) {
        accountClears += "$serverId|$userId"
    }

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

internal class RecordingSubtitleSelectionStore(
    private val clearAllFailure: Throwable? = null,
) : SubtitleSelectionStore {
    var clearedAll = false
    val accountClears = mutableListOf<String>()

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearNonLocalAccountSelections(
        serverId: String,
        userId: String,
    ) {
        accountClears += "$serverId|$userId"
    }

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }

    override suspend fun clearServerScoped(serverId: String) = Unit
}

internal class RecordingPlaybackPreferencesStore(
    private val clearAllFailure: Throwable? = null,
) : PlaybackPreferencesStore {
    var clearedAll = false

    override suspend fun get(serverId: String): PlaybackPreferences = PlaybackPreferences()

    override suspend fun save(
        serverId: String,
        preferences: PlaybackPreferences,
    ) = Unit

    override suspend fun clear(serverId: String) = Unit

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

internal class RecordingLibrarySortStore(
    private val clearAllFailure: Throwable? = null,
) : LibrarySortStore {
    var clearedAll = false

    override fun savedSort(libraryKey: String): SavedLibrarySort? = null

    override suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) = Unit

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

internal class RecordingGridSortStore(
    private val clearAllFailure: Throwable? = null,
) : GridSortStore {
    var clearedAll = false

    override fun savedSort(gridKey: String): String? = null

    override suspend fun setSort(
        gridKey: String,
        sortName: String,
    ) = Unit

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

internal class RecordingLibraryViewStore(
    private val clearAllFailure: Throwable? = null,
) : LibraryViewPreferencesStore {
    var clearedAll = false
    override val rememberLastView = MutableStateFlow(false)

    override fun lastLibraryId(accountKey: String): String? = null

    override fun savedView(libraryKey: String): LibraryInnerView? = null

    override suspend fun setRememberLastView(enabled: Boolean) = Unit

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) = Unit

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) = Unit

    override suspend fun clearServerScoped() {
        clearAllFailure?.let { throw it }
        clearedAll = true
    }
}

private fun storedSession(
    serverId: String,
    userId: String,
): StoredSession =
    StoredSession(
        serverUrl = "https://$serverId.example",
        serverId = serverId,
        serverName = serverId,
        userId = userId,
        userName = userId,
        accessToken = "token-$userId",
        deviceId = "device-1",
    )
