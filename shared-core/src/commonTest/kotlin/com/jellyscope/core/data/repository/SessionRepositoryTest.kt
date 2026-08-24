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
import com.jellyscope.core.data.local.SESSION_ENVELOPE_KEY
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.ServerScopedClearableStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoreCleanupException
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
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
import kotlin.test.assertSame
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
    fun loginPublishesCommittedSnapshotWithoutPostCommitRead() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-1", userId = "user-2")
            val secureStore = PostCommitReadRejectingSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(first)
            val registry = ServerScopedStoreRegistry()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val barriers = mutableListOf<AccountIdentity>()
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = registry,
                            ioDispatcher = dispatcher,
                            advanceLocalSubtitleAccountBarrier = { identity -> barriers += identity },
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            secureStore.rejectReadsAfterNextEnvelopeWrite()
            repository.setLoggedIn(second.toDomain())

            val loggedIn = repository.sessionState.value as SessionState.LoggedIn
            val accounts = repository.accounts.value
            assertTrue(secureStore.envelopeWriteObserved)
            assertEquals(0, secureStore.forbiddenReadCount)
            assertEquals(
                setOf(first.accountId(), second.accountId()),
                accounts.map { account -> account.accountId }.toSet(),
            )
            assertEquals(1, accounts.count { account -> account.isActive })
            assertEquals(
                loggedIn.session.accountIdentity().accountId,
                accounts.single { account -> account.isActive }.accountId,
            )
            assertEquals(listOf(first.toDomain().accountIdentity()), barriers)
        }

    @Test
    fun switchPublishesCommittedSnapshotWithoutPostCommitRead() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-2", userId = "user-2")
            val secureStore = PostCommitReadRejectingSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(first)
            sessionStore.writeSession(second)
            sessionStore.switchActiveAccount(first.accountId())
            val dispatcher = StandardTestDispatcher(testScheduler)
            val barriers = mutableListOf<AccountIdentity>()
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            ioDispatcher = dispatcher,
                            advanceLocalSubtitleAccountBarrier = { identity -> barriers += identity },
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            secureStore.rejectReadsAfterNextEnvelopeWrite()
            val switched = repository.switchTo(second.accountId()).getOrThrow()

            assertEquals(second.toDomain(), switched)
            assertEquals(second.toDomain(), (repository.sessionState.value as SessionState.LoggedIn).session)
            assertEquals(
                second.accountId(),
                repository.accounts.value
                    .single { account -> account.isActive }
                    .accountId,
            )
            assertTrue(secureStore.envelopeWriteObserved)
            assertEquals(0, secureStore.forbiddenReadCount)
            assertEquals(listOf(first.toDomain().accountIdentity()), barriers)
        }

    @Test
    fun sameIdentityReauthenticationAdvancesBarrierAndPublishesWithoutPostCommitRead() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val rotated = first.copy(accessToken = "rotated-token")
            val secureStore = PostCommitReadRejectingSecureStore()
            val sessionStore = SessionStore(secureStore, Json)
            sessionStore.writeSession(first)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val barriers = mutableListOf<AccountIdentity>()
            val repository =
                DefaultSessionRepository(
                    sessionStore = sessionStore,
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(
                            serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                            ioDispatcher = dispatcher,
                            advanceLocalSubtitleAccountBarrier = { identity -> barriers += identity },
                        ),
                    scope = this,
                    workDispatcher = dispatcher,
                )
            advanceUntilIdle()

            secureStore.rejectReadsAfterNextEnvelopeWrite()
            repository.setLoggedIn(rotated.toDomain())

            assertEquals(rotated.toDomain(), (repository.sessionState.value as SessionState.LoggedIn).session)
            assertEquals(1, repository.accounts.value.size)
            assertTrue(
                repository.accounts.value
                    .single()
                    .isActive,
            )
            assertEquals(listOf(first.toDomain().accountIdentity()), barriers)
            assertTrue(secureStore.envelopeWriteObserved)
            assertEquals(0, secureStore.forbiddenReadCount)
        }

    @Test
    fun loginCancellationAfterEnvelopeCommitSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Login,
                cancellationPoint = TransitionCancellationPoint.AfterEnvelopeCommit,
            )
        }

    @Test
    fun loginCancellationAfterBarrierAdvancementSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Login,
                cancellationPoint = TransitionCancellationPoint.AfterBarrierAdvancement,
            )
        }

    @Test
    fun switchCancellationAfterEnvelopeCommitSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Switch,
                cancellationPoint = TransitionCancellationPoint.AfterEnvelopeCommit,
            )
        }

    @Test
    fun switchCancellationAfterBarrierAdvancementSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Switch,
                cancellationPoint = TransitionCancellationPoint.AfterBarrierAdvancement,
            )
        }

    @Test
    fun sameIdentityReauthenticationCancellationAfterEnvelopeCommitSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Reauthentication,
                cancellationPoint = TransitionCancellationPoint.AfterEnvelopeCommit,
            )
        }

    @Test
    fun sameIdentityReauthenticationCancellationAfterBarrierAdvancementSettlesCommittedBoundary() =
        runTest {
            verifyCancelledTransitionSettlement(
                transition = CancellationTransition.Reauthentication,
                cancellationPoint = TransitionCancellationPoint.AfterBarrierAdvancement,
            )
        }

    private suspend fun TestScope.verifyCancelledTransitionSettlement(
        transition: CancellationTransition,
        cancellationPoint: TransitionCancellationPoint,
    ) {
        val first = storedSession(serverId = "server-1", userId = "user-1")
        val second = storedSession(serverId = "server-2", userId = "user-2")
        val rotated = first.copy(accessToken = "rotated-token")
        val expectedActive =
            when (transition) {
                CancellationTransition.Login,
                CancellationTransition.Switch,
                -> second
                CancellationTransition.Reauthentication -> rotated
            }
        val expectedSessions =
            when (transition) {
                CancellationTransition.Login,
                CancellationTransition.Switch,
                -> listOf(first, second)
                CancellationTransition.Reauthentication -> listOf(rotated)
            }
        val gate = TransitionCancellationGate()
        val secureStore = PostCommitReadRejectingSecureStore()
        val sessionStore = SessionStore(secureStore, Json)
        sessionStore.writeSession(first)
        if (transition == CancellationTransition.Switch) {
            sessionStore.writeSession(second)
            sessionStore.switchActiveAccount(first.accountId())
        }
        val registry = ServerScopedStoreRegistry()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barriers = mutableListOf<AccountIdentity>()
        val repository =
            DefaultSessionRepository(
                sessionStore = sessionStore,
                sessionTransitionCoordinator =
                    SessionTransitionCoordinator(
                        serverScopedStoreRegistry = registry,
                        ioDispatcher = dispatcher,
                        advanceLocalSubtitleAccountBarrier = { identity ->
                            barriers += identity
                            if (cancellationPoint == TransitionCancellationPoint.AfterBarrierAdvancement) {
                                gate.pause()
                            }
                        },
                    ),
                scope = this,
                workDispatcher = dispatcher,
            )
        advanceUntilIdle()
        val initialState = assertIs<SessionState.LoggedIn>(repository.sessionState.value)
        val initialLease =
            registry.acquireWorkLease(
                accountIdentity = initialState.session.accountIdentity(),
                boundaryEpoch = initialState.boundaryEpoch,
            )
        assertTrue(initialLease != null)

        secureStore.rejectReadsAfterNextEnvelopeWrite {
            if (cancellationPoint == TransitionCancellationPoint.AfterEnvelopeCommit) {
                gate.pause()
            }
        }
        var surfacedFailure: Throwable? = null
        val operation =
            launch {
                try {
                    when (transition) {
                        CancellationTransition.Login -> repository.setLoggedIn(second.toDomain())
                        CancellationTransition.Switch -> repository.switchTo(second.accountId()).getOrThrow()
                        CancellationTransition.Reauthentication -> repository.setLoggedIn(rotated.toDomain())
                    }
                } catch (throwable: Throwable) {
                    surfacedFailure = throwable
                }
            }

        runCurrent()
        gate.reached.await()
        val callerCancellation = CancellationException("caller cancelled after irreversible session commit")
        operation.cancel(callerCancellation)
        runCurrent()
        assertFalse(operation.isCompleted)
        gate.release.complete(Unit)
        advanceUntilIdle()

        assertSame(callerCancellation, surfacedFailure)
        val durableSnapshot = secureStore.readCommittedSnapshot()
        assertEquals(expectedSessions, durableSnapshot.sessions)
        assertEquals(expectedActive.accountId(), durableSnapshot.activeAccountId)
        assertEquals(expectedActive, durableSnapshot.activeSession)
        val publishedState = assertIs<SessionState.LoggedIn>(repository.sessionState.value)
        assertEquals(expectedActive.toDomain(), publishedState.session)
        assertEquals(expectedSessions.map(StoredSession::accountId), repository.accounts.value.map { it.accountId })
        assertEquals(
            expectedActive.accountId(),
            repository.accounts.value
                .single { account -> account.isActive }
                .accountId,
        )
        assertEquals(1, repository.accounts.value.count { account -> account.isActive })
        assertTrue(
            registry.acquireWorkLease(
                accountIdentity = initialState.session.accountIdentity(),
                boundaryEpoch = initialState.boundaryEpoch,
            ) == null,
        )
        assertTrue(
            registry.acquireWorkLease(
                accountIdentity = expectedActive.toDomain().accountIdentity(),
                boundaryEpoch = publishedState.boundaryEpoch,
            ) != null,
        )
        assertEquals(listOf(first.toDomain().accountIdentity()), barriers)
        assertTrue(secureStore.envelopeWriteObserved)
        assertEquals(0, secureStore.forbiddenReadCount)
    }

    @Test
    fun coldRestoreReadsOneCommittedSnapshot() =
        runTest {
            val delegate = FakeSecureStore()
            val firstStore = SessionStore(delegate, Json)
            val session = storedSession(serverId = "server-1", userId = "user-1")
            firstStore.writeSession(session)
            val countingStore = ReadCountingSecureStore(delegate)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                DefaultSessionRepository(
                    sessionStore = SessionStore(countingStore, Json),
                    sessionTransitionCoordinator =
                        SessionTransitionCoordinator(ServerScopedStoreRegistry(), ioDispatcher = dispatcher),
                    scope = this,
                    workDispatcher = dispatcher,
                )

            advanceUntilIdle()

            assertEquals(listOf(SESSION_ENVELOPE_KEY), countingStore.readKeys)
            assertEquals(session.toDomain(), (repository.sessionState.value as SessionState.LoggedIn).session)
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
            assertEquals(null, sessionStore.readSession())
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
            secureStore.failOnWriteKey = SESSION_ENVELOPE_KEY

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
    fun removeAccountPublishesCommittedSnapshotWithoutPostCommitRead() =
        runTest {
            val first = storedSession(serverId = "server-1", userId = "user-1")
            val second = storedSession(serverId = "server-1", userId = "user-2")
            val secureStore = PostCommitReadRejectingSecureStore()
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

            secureStore.rejectReadsAfterNextEnvelopeWrite()
            val removal = repository.removeAccount(second.accountId()).getOrThrow()

            assertTrue(secureStore.envelopeWriteObserved)
            assertEquals(0, secureStore.forbiddenReadCount)
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

private enum class CancellationTransition {
    Login,
    Switch,
    Reauthentication,
}

private enum class TransitionCancellationPoint {
    AfterEnvelopeCommit,
    AfterBarrierAdvancement,
}

private class TransitionCancellationGate {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause() {
        reached.complete(Unit)
        release.await()
    }
}

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

private class PostCommitReadRejectingSecureStore : SecureStore {
    private val delegate = FakeSecureStore()
    private var rejectAfterNextWrite = false
    private var rejectReads = false
    private var afterNextEnvelopeWrite: suspend () -> Unit = {}
    var envelopeWriteObserved = false
        private set
    var forbiddenReadCount = 0
        private set

    fun rejectReadsAfterNextEnvelopeWrite(afterWrite: suspend () -> Unit = {}) {
        rejectAfterNextWrite = true
        envelopeWriteObserved = false
        afterNextEnvelopeWrite = afterWrite
    }

    suspend fun readCommittedSnapshot() = SessionStore(delegate, Json).readSnapshot()

    override suspend fun read(key: String): String? {
        if (rejectReads) {
            forbiddenReadCount += 1
            throw IllegalStateException("secure-store read after committed envelope write")
        }
        return delegate.read(key)
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        delegate.write(key, value)
        if (key == SESSION_ENVELOPE_KEY && rejectAfterNextWrite) {
            rejectAfterNextWrite = false
            rejectReads = true
            envelopeWriteObserved = true
            afterNextEnvelopeWrite()
            afterNextEnvelopeWrite = {}
        }
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
    }

    override suspend fun clear() {
        delegate.clear()
    }
}

private class ReadCountingSecureStore(
    private val delegate: SecureStore,
) : SecureStore {
    val readKeys = mutableListOf<String>()

    override suspend fun read(key: String): String? {
        readKeys += key
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
