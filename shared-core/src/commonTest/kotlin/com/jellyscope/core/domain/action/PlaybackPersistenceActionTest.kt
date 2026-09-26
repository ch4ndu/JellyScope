// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackPersistenceActionTest {
    @Test
    fun selectionWritesKeepTheLatestSameKeyValue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = FakePlaybackSelectionStore()
                val boundary = playbackBoundary()
                val action = SavePlaybackSelectionAction(store, boundary.sessions, boundary.registry, scope, dispatcher)
                val key = PlaybackSelectionKey("server", "user", "item", "source")

                action.save(key, PlaybackSelection(audioStreamIndex = 1))
                action.save(key, PlaybackSelection(audioStreamIndex = 2))
                advanceUntilIdle()

                assertEquals(
                    PlaybackSelection(audioStreamIndex = 2).normalized(),
                    store.values[key],
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun timingWritesKeepTheLatestSameKeyValue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = FakePlaybackTimingStore()
                val boundary = playbackBoundary()
                val action = SavePlaybackTimingOffsetAction(store, boundary.sessions, boundary.registry, scope, dispatcher)
                val key = PlaybackTimingKey("server", "user", "item", "source", "track", PlaybackTimingKind.Audio)

                action.save(PlaybackTimingOffset(key, offsetMs = -500L))
                action.save(PlaybackTimingOffset(key, offsetMs = 750L))
                advanceUntilIdle()

                assertEquals(750L, store.values[key]?.offsetMs)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun directSelectionWriteAndDeletePreserveTheSourceQualifiedKey() =
        runTest {
            val store = FakePlaybackSelectionStore()
            val key = PlaybackSelectionKey("server", "user", "item", "source")
            val selection = PlaybackSelection(audioStreamIndex = 2)
            val scope = CoroutineScope(SupervisorJob())
            val boundary = playbackBoundary()

            try {
                SavePlaybackSelectionAction(store, boundary.sessions, boundary.registry, scope).invoke(key, selection)

                assertEquals(selection.normalized(), store.values[key])

                DeletePlaybackSelectionAction(store).invoke(key)

                assertEquals(null, store.values[key])
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun delayedSelectionWriteIsCancelledAfterAccountLeavesAndReturns() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val boundary = playbackBoundary()
            val store = FakePlaybackSelectionStore()
            val action = SavePlaybackSelectionAction(store, boundary.sessions, boundary.registry, scope, dispatcher)
            val key = PlaybackSelectionKey("server", "user", "item", "source")

            val write = action.save(key, PlaybackSelection(audioStreamIndex = 2))
            boundary.sessions.publish(AccountIdentity("other-server", "other-user"), 2L)
            boundary.registry.transitionToAccount(AccountIdentity("other-server", "other-user"), 2L)
            boundary.sessions.publish(AccountIdentity("server", "user"), 3L)
            boundary.registry.transitionToAccount(AccountIdentity("server", "user"), 3L)
            advanceUntilIdle()
            assertTrue(write.isCancelled)
            assertNull(store.values[key])
            scope.cancel()
        }

    @Test
    fun backendOverrideActionsSaveAndDeleteTheExactItemScope() =
        runTest {
            val store = FakePlayerBackendOverrideStore()

            SavePlayerBackendOverrideAction(store)("server", "item", PlayerBackend.LibVlc)

            assertEquals(PlayerBackend.LibVlc, store.values["server" to "item"])

            DeletePlayerBackendOverrideAction(store)("server", "item")

            assertEquals(null, store.values["server" to "item"])
        }
}

private suspend fun playbackBoundary(): PlaybackBoundary {
    val account = AccountIdentity("server", "user")
    val sessions = FakePlaybackSessionRepository(account, 1L)
    val registry = ServerScopedStoreRegistry()
    registry.transitionToAccount(account, 1L)
    return PlaybackBoundary(sessions, registry)
}

private data class PlaybackBoundary(
    val sessions: FakePlaybackSessionRepository,
    val registry: ServerScopedStoreRegistry,
)

private class FakePlaybackSessionRepository(
    accountIdentity: AccountIdentity,
    boundaryEpoch: Long,
) : SessionRepository {
    private val state = MutableStateFlow<SessionState>(loggedInSession(accountIdentity, boundaryEpoch))
    override val sessionState: StateFlow<SessionState> = state

    fun publish(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ) {
        state.value = loggedInSession(accountIdentity, boundaryEpoch)
    }

    override suspend fun setLoggedIn(session: Session): Unit = error("Unused test mutation")

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> = error("Unused test mutation")
}

private fun loggedInSession(
    account: AccountIdentity,
    epoch: Long,
): SessionState.LoggedIn =
    SessionState.LoggedIn(Session("https://example", account.serverId, "Server", account.userId, "User", "token", "device"), epoch)

private class FakePlaybackSelectionStore : PlaybackSelectionStore {
    val values = mutableMapOf<PlaybackSelectionKey, PlaybackSelection>()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = values[key]

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        values[key] = selection
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        values.remove(key)
    }

    override suspend fun clearServerScoped() = values.clear()

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        values.keys.removeAll { key ->
            key.serverId == accountIdentity.serverId && key.userId == accountIdentity.userId
        }
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        values.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }
}

private class FakePlaybackTimingStore : PlaybackTimingStore {
    val values = mutableMapOf<PlaybackTimingKey, PlaybackTimingOffset>()

    override suspend fun get(key: PlaybackTimingKey): PlaybackTimingOffset? = values[key]

    override suspend fun save(offset: PlaybackTimingOffset) {
        values[offset.key] = offset
    }

    override suspend fun delete(key: PlaybackTimingKey) {
        values.remove(key)
    }

    override suspend fun clearServerScoped() = values.clear()

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        values.keys.removeAll { key ->
            key.serverId == accountIdentity.serverId && key.userId == accountIdentity.userId
        }
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        values.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }
}

private class FakePlayerBackendOverrideStore : PlayerBackendOverrideStore {
    val values = mutableMapOf<Pair<String, String>, PlayerBackend>()

    override suspend fun get(
        serverId: String,
        itemId: String,
    ): PlayerBackend? = values[serverId to itemId]

    override suspend fun save(
        serverId: String,
        itemId: String,
        backend: PlayerBackend?,
    ) {
        if (backend == null) {
            values.remove(serverId to itemId)
        } else {
            values[serverId to itemId] = backend
        }
    }

    override suspend fun delete(
        serverId: String,
        itemId: String,
    ) {
        values.remove(serverId to itemId)
    }

    override suspend fun clearServerScoped() = values.clear()

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { key -> key.first == serverId }
    }
}
