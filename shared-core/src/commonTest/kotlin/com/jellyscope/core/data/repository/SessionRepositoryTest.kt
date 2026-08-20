// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.DiscoveryCache
import com.jellyscope.core.data.local.FakeSecureStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.PersistentAccountStoreCleaner
import com.jellyscope.core.data.local.RecordingGridSortStore
import com.jellyscope.core.data.local.RecordingLibrarySortStore
import com.jellyscope.core.data.local.RecordingLibraryViewStore
import com.jellyscope.core.data.local.RecordingPlaybackPreferencesStore
import com.jellyscope.core.data.local.RecordingPlayerBackendOverrideStore
import com.jellyscope.core.data.local.RecordingRecentSearchStore
import com.jellyscope.core.data.local.RecordingSubtitleSelectionStore
import com.jellyscope.core.data.local.RecordingWatchNextStore
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.ServerScopedClearableStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoreCleanupException
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionRepositoryTest {
    @Test
    fun switchToRethrowsCancellationFromCacheCleanup() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val fixture =
                sessionRepositoryFixture(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = RecordingServerScopedStore(),
                )
            fixture.registry.register(CancellingRefetchableCache())

            assertFailsWith<CancellationException> {
                fixture.repository.switchTo(second.accountId())
            }
        }

    @Test
    fun coldLoggedInRestoreInstallsExactEpochBeforeLeasingWork() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1").copy(enableContentDownloading = true)
            val fixture =
                sessionRepositoryFixture(
                    first = first,
                    second = first,
                    activeAccountId = first.accountId(),
                    serverStore = RecordingServerScopedStore(),
                )

            val loggedIn = fixture.repository.sessionState.value as SessionState.LoggedIn

            assertEquals(1L, loggedIn.boundaryEpoch)
            assertTrue(loggedIn.session.enableContentDownloading)
            assertTrue(
                fixture.registry.acquireWorkLease(loggedIn.session.accountIdentity(), loggedIn.boundaryEpoch) != null,
            )
        }

    @Test
    fun pendingLogoutRefusesColdRestoreAndRetriesCleanup() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            sessionStore.writeSession(session)
            sessionStore.markLogoutPending()
            val registry = ServerScopedStoreRegistry()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            persistentAccountStoreCleaner = persistentCleaner(sessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )

            advanceUntilIdle()

            assertEquals(SessionState.LoggedOut(null), repository.sessionState.value)
            assertEquals(emptyList(), repository.accounts.value)
            assertEquals(null, sessionStore.readSession())
            assertFalse(sessionStore.isLogoutPending())
        }

    @Test
    fun incompleteRemovalReplayRunsBeforeNormalSessionReadsAndBlocksLoggedInRestore() =
        runTest {
            val events = mutableListOf<String>()
            val secureStore = ReadTrackingSecureStore(events)
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            sessionStore.writeSession(session)
            events.clear()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val participant = StartupReplayingParticipant(session.toDomain().accountIdentity(), events)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            sessionStore = sessionStore,
                            sessionBoundaryParticipant = participant,
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )

            advanceUntilIdle()

            assertEquals("resume", events.first())
            assertEquals(SessionState.LoggedOut(null), repository.sessionState.value)
            assertEquals(emptyList(), repository.accounts.value)
            assertEquals(null, sessionStore.readSession())
        }

    @Test
    fun failedRemovalReplayPublishesLoggedOutWithoutNormalSessionReads() =
        runTest {
            val events = mutableListOf<String>()
            val secureStore = ReadTrackingSecureStore(events)
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(storedSession(serverId = "server-1", userId = "user-1"))
            events.clear()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            sessionStore = sessionStore,
                            sessionBoundaryParticipant = FailingStartupRemovalParticipant(events),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )

            advanceUntilIdle()

            assertEquals(listOf("resume"), events)
            assertEquals(SessionState.LoggedOut(null), repository.sessionState.value)
            assertEquals(emptyList(), repository.accounts.value)
        }

    @Test
    fun cancelledAddAccountCommitKeepsSameServerAccountsAndLiveActiveMarker() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-1", userId = "user-2")
            val secureStore =
                AccountsReadGate(
                    armOnWriteKey = "session_account:${second.accountId()}",
                )
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(first)
            val registry = ServerScopedStoreRegistry()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator = SessionTransitionCoordinator(registry, ioDispatcher = dispatcher),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            val commit =
                launch {
                    repository.setLoggedIn(second.toDomain())
                }
            runCurrent()
            secureStore.accountsRead.await()

            assertEquals(second.toDomain(), (repository.sessionState.value as SessionState.LoggedIn).session)
            commit.cancel()
            secureStore.releaseAccountsRead.complete(Unit)
            advanceUntilIdle()

            val loggedIn = repository.sessionState.value as SessionState.LoggedIn
            val accounts = repository.accounts.value
            assertEquals(
                setOf(first.accountId(), second.accountId()),
                accounts.map { account -> account.accountId }.toSet(),
            )
            assertEquals(1, accounts.count { account -> account.isActive })
            assertEquals(
                loggedIn.session.accountIdentity().accountId,
                accounts.single { account -> account.isActive }.accountId,
            )
        }

    @Test
    fun logoutErasesCredentialsBeforeMidListCacheFailure() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            sessionStore.writeSession(session)
            val registry = ServerScopedStoreRegistry()
            val firstCache = RecordingRefetchableCache()
            val failingCache = RecordingRefetchableCache(IllegalStateException("cache failure"))
            val lastCache = RecordingRefetchableCache()
            registry.register(firstCache)
            registry.register(failingCache)
            registry.register(lastCache)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            persistentAccountStoreCleaner = persistentCleaner(sessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            assertIs<StoreCleanupException>(repository.setLoggedOut(session.serverUrl).exceptionOrNull())

            assertTrue(firstCache.wasCleared)
            assertTrue(failingCache.wasCleared)
            assertTrue(lastCache.wasCleared)
            assertEquals(null, sessionStore.readSession())
            assertFalse(sessionStore.isLogoutPending())
            assertEquals(SessionState.LoggedOut(session.serverUrl), repository.sessionState.value)
            assertEquals(emptyList(), repository.accounts.value)

            val coldSessionStore = SessionStore(secureStore, Json)
            val coldRepository =
                DefaultSessionRepository(
                    sessionStore = coldSessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            persistentAccountStoreCleaner = persistentCleaner(coldSessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            assertEquals(SessionState.LoggedOut(null), coldRepository.sessionState.value)
            assertEquals(emptyList(), coldRepository.accounts.value)
        }

    @Test
    fun credentialClearFailureLeavesPendingAndColdRestoreDoesNotAuthenticate() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            sessionStore.writeSession(session)
            secureStore.failOnRemoveKey = "session_account:${session.accountId()}"
            val registry = ServerScopedStoreRegistry()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            persistentAccountStoreCleaner = persistentCleaner(sessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            assertIs<StoreCleanupException>(repository.setLoggedOut(session.serverUrl).exceptionOrNull())

            assertTrue(sessionStore.isLogoutPending())
            assertEquals(SessionState.LoggedOut(session.serverUrl), repository.sessionState.value)
            assertEquals(emptyList(), repository.accounts.value)
            secureStore.failOnRemoveKey = null

            val coldSecureSessionStore = SessionStore(secureStore, Json)
            val coldRepository =
                DefaultSessionRepository(
                    sessionStore = coldSecureSessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            persistentAccountStoreCleaner = persistentCleaner(coldSecureSessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            assertEquals(SessionState.LoggedOut(null), coldRepository.sessionState.value)
            assertEquals(emptyList(), coldRepository.accounts.value)
            assertEquals(null, coldSecureSessionStore.readSession())
            assertFalse(coldSecureSessionStore.isLogoutPending())
        }

    @Test
    fun logoutMarkerWriteFailureAbortsLogoutAndKeepsSessionIntact() =
        runTest {
            val secureStore = FakeSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            sessionStore.writeSession(session)
            val registry = ServerScopedStoreRegistry()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            persistentAccountStoreCleaner = persistentCleaner(sessionStore),
                            ioDispatcher = dispatcher,
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()
            val stateBeforeLogout = repository.sessionState.value
            assertTrue(stateBeforeLogout is SessionState.LoggedIn)

            // The durable logout-pending marker cannot be persisted: logout must
            // abort fail-closed rather than clear credentials without a recoverable
            // marker (which would risk a silent re-login on the next cold start).
            secureStore.failOnWriteKey = "session_logout_pending"

            assertIs<IllegalStateException>(repository.setLoggedOut(session.serverUrl).exceptionOrNull())

            // Nothing was published or cleared — the session is fully intact.
            assertEquals(stateBeforeLogout, repository.sessionState.value)
            assertEquals(session, sessionStore.readSession())
        }

    @Test
    fun removeAccountRethrowsCancellationFromCacheCleanup() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val fixture =
                sessionRepositoryFixture(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = RecordingServerScopedStore(),
                )
            fixture.registry.register(CancellingRefetchableCache())

            assertFailsWith<CancellationException> {
                fixture.repository.removeAccount(first.accountId())
            }
        }

    @Test
    fun switchToClearsDiscoveryCacheWithoutClearingServerStoresOrCredentials() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val serverStore = RecordingServerScopedStore()
            val fixture =
                sessionRepositoryFixture(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = serverStore,
                )
            fixture.discoveryCache.getOrLoad(first.toDomain().accountIdentity(), 1L, "landing") { Result.success("before") }

            val switchedSession = fixture.repository.switchTo(second.accountId()).getOrThrow()

            var reloadCount = 0
            val cachedAfterSwitch =
                fixture.discoveryCache
                    .getOrLoad(second.toDomain().accountIdentity(), 2L, "landing") {
                        reloadCount += 1
                        Result.success("after")
                    }.getOrThrow()
            assertEquals(second.toDomain(), switchedSession)
            assertEquals("after", cachedAfterSwitch)
            assertEquals(1, reloadCount)
            assertFalse(serverStore.wasClearedAll)
            assertEquals(emptyList(), serverStore.clearedServerIds)
            assertEquals(listOf(first, second), fixture.sessionStore.readAccounts())
            assertEquals(second, fixture.sessionStore.readSession())
            assertEquals(second.accountId(), fixture.sessionStore.readActiveAccountId())
            val loggedIn = fixture.repository.sessionState.value as SessionState.LoggedIn
            assertEquals(second.toDomain(), loggedIn.session)
            assertEquals(2L, loggedIn.boundaryEpoch)
        }

    @Test
    fun removeAccountClearsDiscoveryCacheWithoutClearingServerStores() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val serverStore = RecordingServerScopedStore()
            val fixture =
                sessionRepositoryFixture(
                    first = first,
                    second = second,
                    activeAccountId = first.accountId(),
                    serverStore = serverStore,
                )
            fixture.discoveryCache.getOrLoad(first.toDomain().accountIdentity(), 1L, "landing") { Result.success("before") }

            val removal = fixture.repository.removeAccount(first.accountId()).getOrThrow()

            var reloadCount = 0
            val cachedAfterRemoval =
                fixture.discoveryCache
                    .getOrLoad(second.toDomain().accountIdentity(), 2L, "landing") {
                        reloadCount += 1
                        Result.success("after")
                    }.getOrThrow()
            assertEquals(first.accountId(), removal.removedAccount.accountId)
            assertEquals(first.userId, removal.removedAccount.userId)
            assertEquals(second.toDomain(), removal.activeSession)
            assertEquals("after", cachedAfterRemoval)
            assertEquals(1, reloadCount)
            assertFalse(serverStore.wasClearedAll)
            assertEquals(listOf("server-1"), serverStore.clearedServerIds)
            assertEquals(listOf(second), fixture.sessionStore.readAccounts())
            assertEquals(second, fixture.sessionStore.readSession())
        }

    @Test
    fun removeAccountPublishesRemainingAccountsWithoutReadingSecureStore() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-1", userId = "user-2")
            val secureStore =
                ThrowingAccountsReadGate(
                    armOnRemoveKey = "session_account:${second.accountId()}",
                )
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(first)
            sessionStore.writeSession(second)
            sessionStore.switchActiveAccount(first.accountId())

            val registry = ServerScopedStoreRegistry()
            val discoveryCache = DiscoveryCache(registry)
            registry.register(sessionStore)
            registry.register(discoveryCache)
            val workDispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            persistentAccountStoreCleaner =
                                persistentCleaner(
                                    SessionStore(FakeSecureStore(), Json),
                                ),
                            ioDispatcher = workDispatcher,
                        ),
                    scope = this,
                    workDispatcher = workDispatcher,
                )
            advanceUntilIdle()

            val removal = repository.removeAccount(second.accountId()).getOrThrow()

            assertTrue(secureStore.removeObserved)
            assertEquals(first.toDomain(), removal.activeSession)
            val loggedIn = repository.sessionState.value as SessionState.LoggedIn
            assertEquals(first.toDomain(), loggedIn.session)
            assertEquals(
                listOf(first.accountId()),
                repository.accounts.value.map { account -> account.accountId },
            )
            assertEquals(
                loggedIn.session.accountIdentity().accountId,
                repository.accounts.value
                    .single { account -> account.isActive }
                    .accountId,
            )
        }
}

private suspend fun TestScope.sessionRepositoryFixture(
    first: StoredSession,
    second: StoredSession,
    activeAccountId: String,
    serverStore: RecordingServerScopedStore,
): SessionRepositoryFixture {
    val sessionStore = SessionStore(FakeSecureStore(), Json)
    sessionStore.writeSession(first)
    sessionStore.writeSession(second)
    sessionStore.switchActiveAccount(activeAccountId)
    val registry = ServerScopedStoreRegistry()
    val discoveryCache = DiscoveryCache(registry)
    registry.register(sessionStore)
    registry.register(discoveryCache)
    registry.register(serverStore)
    val workDispatcher = StandardTestDispatcher(testScheduler)
    val repository =
        DefaultSessionRepository(
            sessionStore = sessionStore,
            sessionTransitionCoordinator = SessionTransitionCoordinator(registry, ioDispatcher = workDispatcher),
            scope = this,
            workDispatcher = workDispatcher,
        )
    advanceUntilIdle()

    return SessionRepositoryFixture(
        repository = repository,
        sessionStore = sessionStore,
        discoveryCache = discoveryCache,
        registry = registry,
    )
}

private data class SessionRepositoryFixture(
    val repository: DefaultSessionRepository,
    val sessionStore: SessionStore,
    val discoveryCache: DiscoveryCache,
    val registry: ServerScopedStoreRegistry,
)

private class CancellingRefetchableCache : RefetchableServerCache {
    override suspend fun clearServerScoped(): Unit = throw CancellationException("account operation cancelled")
}

private class RecordingRefetchableCache(
    private val failure: Throwable? = null,
) : RefetchableServerCache {
    var wasCleared = false

    override suspend fun clearServerScoped() {
        wasCleared = true
        failure?.let { throw it }
    }
}

private class RecordingServerScopedStore : ServerScopedClearableStore {
    var wasClearedAll = false
        private set
    val clearedServerIds = mutableListOf<String>()

    override suspend fun clearServerScoped() {
        wasClearedAll = true
    }

    override suspend fun clearServerScoped(serverId: String) {
        clearedServerIds += serverId
    }
}

private class AccountsReadGate(
    private val armOnWriteKey: String,
) : SecureStore {
    private val delegate = FakeSecureStore()
    val accountsRead = CompletableDeferred<Unit>()
    val releaseAccountsRead = CompletableDeferred<Unit>()
    private var armed = false
    private var blocked = false

    override suspend fun read(key: String): String? {
        if (armed && !blocked && key == "session_accounts") {
            blocked = true
            accountsRead.complete(Unit)
            releaseAccountsRead.await()
        }
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        delegate.write(key, value)
        if (key == armOnWriteKey) {
            armed = true
        }
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
    }

    override suspend fun clear() {
        delegate.clear()
    }
}

private class ThrowingAccountsReadGate(
    private val armOnRemoveKey: String,
) : SecureStore {
    private val delegate = FakeSecureStore()
    private var armed = false
    private var blocked = false
    var removeObserved = false
        private set

    override suspend fun read(key: String): String? {
        if (armed && !blocked && key == "session_accounts") {
            blocked = true
            throw IllegalStateException("account index read after account removal")
        }
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        delegate.write(key, value)
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
        if (key == armOnRemoveKey) {
            armed = true
            removeObserved = true
        }
    }

    override suspend fun clear() {
        delegate.clear()
    }
}

private class ReadTrackingSecureStore(
    private val events: MutableList<String>,
) : SecureStore {
    private val delegate = FakeSecureStore()

    override suspend fun read(key: String): String? {
        events += "read:$key"
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        delegate.write(key, value)
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
    }

    override suspend fun clear() {
        delegate.clear()
    }
}

private class StartupReplayingParticipant(
    private val accountIdentity: AccountIdentity,
    private val events: MutableList<String>,
) : SessionBoundaryParticipant {
    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> = Result.success(null)

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> {
        events += "resume"
        executor.removeAccount(accountIdentity).getOrThrow()
        return Result.success(Unit)
    }
}

private class FailingStartupRemovalParticipant(
    private val events: MutableList<String>,
) : SessionBoundaryParticipant {
    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> = Result.success(null)

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> {
        events += "resume"
        return Result.failure(SessionRemovalError.ConfirmationStale)
    }
}

private fun storedSession(
    serverId: String,
    userId: String,
): StoredSession =
    StoredSession(
        serverUrl = "https://$serverId.example",
        serverId = serverId,
        serverName = "Server $serverId",
        userId = userId,
        userName = "User $userId",
        accessToken = "token-$userId",
        deviceId = "device-1",
    )

private fun persistentCleaner(sessionStore: SessionStore): PersistentAccountStoreCleaner =
    PersistentAccountStoreCleaner(
        sessionStore = sessionStore,
        playbackPreferencesStore = RecordingPlaybackPreferencesStore(),
        playerBackendOverrideStore = RecordingPlayerBackendOverrideStore(),
        recentSearchStore = RecordingRecentSearchStore(),
        watchNextSyncStore = RecordingWatchNextStore(),
        subtitleSelectionStore = RecordingSubtitleSelectionStore(),
        librarySortStore = RecordingLibrarySortStore(),
        libraryViewPreferencesStore = RecordingLibraryViewStore(),
        gridSortStore = RecordingGridSortStore(),
    )
