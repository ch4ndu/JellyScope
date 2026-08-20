// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.FakeSecureStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.LogoutCleanupCancellationException
import com.jellyscope.core.data.local.ServerScopedClearableStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoredAccountRemoval
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionTransitionCoordinatorTest {
    @Test
    fun cancellationImmediatelyAfterRemoveAccountStillClearsAndPublishes() =
        runTest {
            val fixture = RemovalFixture()
            val result = runRemoval(fixture, CancellationPoint.AFTER_REMOVE_ACCOUNT)

            assertIs<LogoutCleanupCancellationException>(result.failure)
            assertTrue(result.published)
            assertEquals(1, fixture.accountStore.accountClearCount)
            assertEquals(1, fixture.serverStore.serverClearCount)
        }

    @Test
    fun cancellationBetweenAccountAndServerClearStillClearsAndPublishes() =
        runTest {
            val fixture = RemovalFixture()
            val result = runRemoval(fixture, CancellationPoint.BETWEEN_ACCOUNT_AND_SERVER)

            assertIs<LogoutCleanupCancellationException>(result.failure)
            assertTrue(result.published)
            assertEquals(1, fixture.accountStore.accountClearCount)
            assertEquals(1, fixture.serverStore.serverClearCount)
        }

    @Test
    fun cancellationBeforePublishStillPublishes() =
        runTest {
            val fixture = RemovalFixture()
            val result = runRemoval(fixture, CancellationPoint.BEFORE_PUBLISH)

            assertIs<LogoutCleanupCancellationException>(result.failure)
            assertTrue(result.published)
            assertEquals(1, fixture.accountStore.accountClearCount)
            assertEquals(1, fixture.serverStore.serverClearCount)
        }

    @Test
    fun cancellationThrowingStoreDoesNotSkipLaterCleanupOrPublish() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val first = ThrowingAccountStore(CancellationException("store cancelled"))
            val last = RecordingAccountStore()
            val serverStore = RecordingServerStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(last)
            registry.register(serverStore)
            val coordinator = SessionTransitionCoordinator(registry)
            var published = false

            val failure =
                kotlin.test.assertFailsWith<LogoutCleanupCancellationException> {
                    coordinator.commitAccountRemoval(
                        accountIdentity = account,
                        removeAccount = {
                            StoredAccountRemoval(
                                removedSession = storedSession(account),
                                activeSession = null,
                                hasServerSibling = false,
                            )
                        },
                        publish = {
                            published = true
                            Unit
                        },
                    )
                }

            assertTrue(published)
            assertEquals(1, first.accountClearCount)
            assertEquals(1, last.accountClearCount)
            assertEquals(1, serverStore.serverClearCount)
            assertEquals(1, failure.failures.size)
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.runRemoval(
        fixture: RemovalFixture,
        cancellationPoint: CancellationPoint,
    ): RemovalResult {
        lateinit var operationJob: Job
        val job =
            launch {
                operationJob = coroutineContext[Job] ?: error("Removal job was not installed")
                try {
                    fixture.coordinator.commitAccountRemoval(
                        accountIdentity = fixture.account,
                        removeAccount = {
                            if (cancellationPoint == CancellationPoint.AFTER_REMOVE_ACCOUNT) {
                                operationJob.cancel()
                            }
                            fixture.removal
                        },
                        publish = {
                            fixture.published = true
                            Unit
                        },
                    )
                } catch (throwable: Throwable) {
                    fixture.failure = throwable
                }
            }

        fixture.accountStore.onAccountClear = {
            if (cancellationPoint == CancellationPoint.BETWEEN_ACCOUNT_AND_SERVER) {
                operationJob.cancel()
            }
        }
        fixture.serverStore.onServerClear = {
            if (cancellationPoint == CancellationPoint.BEFORE_PUBLISH) {
                operationJob.cancel()
            }
        }
        job.join()
        return RemovalResult(fixture.failure, fixture.published)
    }

    @Test
    fun publishedTransitionCarriesRemainingAccountsSoNoStoreReadIsNeeded() =
        kotlinx.coroutines.test.runTest {
            // Publication must not re-read the store: a post-deletion read re-enters
            // the migration pass, which retries the index/active-key writes and can
            // throw inside publish(), skipping the failure aggregate and leaving the
            // published account list stale after the session state already changed.
            val fixture = RemovalFixture()
            val sibling = storedSession(AccountIdentity("server-1", "user-2"))
            var publishedRemaining: List<String>? = null

            fixture.coordinator.commitAccountRemoval(
                accountIdentity = fixture.account,
                removeAccount = { fixture.removal.copy(remainingSessions = listOf(sibling)) },
                publish = { transition ->
                    publishedRemaining = transition.remainingSessions.map { session -> session.userId }
                },
            )

            assertEquals(listOf("user-2"), publishedRemaining)
        }

    @Test
    fun accountSwitchCallbackUsesGateHeldLeaseBeforeSessionMutation() =
        runTest {
            val current = AccountIdentity("server-1", "user-1")
            val replacement = AccountIdentity("server-2", "user-2")
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(current, boundaryEpoch = 9L)
            val lease = registry.acquireWorkLease(current, boundaryEpoch = 9L)
            assertTrue(lease != null)
            val participant = SwitchRecordingParticipant(lease)
            val coordinator =
                SessionTransitionCoordinator(
                    serverScopedStoreRegistry = registry,
                    sessionBoundaryParticipant = participant,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            withTimeout(1.seconds) {
                coordinator.commitSwitch(
                    currentAccountIdentity = current,
                    load = {
                        participant.events += "load"
                        storedSession(replacement)
                    },
                    publish = { _, _ -> participant.events += "publish" },
                )
            }

            assertEquals(listOf("before:true", "load", "publish"), participant.events)
        }

    @Test
    fun removalAuthorizationFailureStopsBeforeCredentialMutation() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val participant = RejectingRemovalParticipant()
            val coordinator =
                SessionTransitionCoordinator(
                    serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                    sessionBoundaryParticipant = participant,
                )
            var credentialMutationCalled = false

            kotlin.test.assertFailsWith<SessionRemovalError.DownloadRemovalConfirmationRequired> {
                coordinator.commitAccountRemoval(
                    accountIdentity = account,
                    removeAccount = {
                        credentialMutationCalled = true
                        StoredAccountRemoval(
                            removedSession = storedSession(account),
                            activeSession = null,
                            hasServerSibling = false,
                        )
                    },
                    publish = { Unit },
                )
            }

            assertEquals(SessionRemovalScope.Account(account), participant.scope)
            assertEquals(SessionRemovalAuthorization.None, participant.authorization)
            assertTrue(!credentialMutationCalled)
        }

    @Test
    fun fullLogoutPreparesEveryStableAccountBeforeCredentialMutation() =
        runTest {
            val first = AccountIdentity("server-1", "user-1")
            val second = AccountIdentity("server-2", "user-2")
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            sessionStore.writeSession(storedSession(first))
            sessionStore.writeSession(storedSession(second))
            val participant = RejectingRemovalParticipant()
            val coordinator =
                SessionTransitionCoordinator(
                    serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                    sessionStore = sessionStore,
                    sessionBoundaryParticipant = participant,
                )
            var credentialMutationCalled = false

            kotlin.test.assertFailsWith<SessionRemovalError.DownloadRemovalConfirmationRequired> {
                coordinator.commitLogout(
                    prepareLogout = { credentialMutationCalled = true },
                    publishLoggedOut = { credentialMutationCalled = true },
                    publishAccountsCleared = { credentialMutationCalled = true },
                )
            }

            assertEquals(SessionRemovalScope.FullLogout(listOf(first, second)), participant.scope)
            assertEquals(SessionRemovalAuthorization.None, participant.authorization)
            assertTrue(!credentialMutationCalled)
            assertEquals(listOf(first, second), sessionStore.readAccounts().map { stored -> stored.toIdentity() })
        }

    @Test
    fun coldReplayExecutorRemovesPresentAndAlreadyAbsentAccountIdempotently() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val sessionStore = SessionStore(FakeSecureStore(), Json)
            sessionStore.writeSession(storedSession(account))
            val participant = ReplayingRemovalParticipant(account)
            val coordinator =
                SessionTransitionCoordinator(
                    serverScopedStoreRegistry = ServerScopedStoreRegistry(),
                    sessionStore = sessionStore,
                    sessionBoundaryParticipant = participant,
                )

            coordinator.resumeIncompleteRemovalOperations {
                error("Replay should not publish logged out as a failure")
            }

            assertEquals(emptyList(), sessionStore.readAccounts())
            assertEquals(2, participant.removeCalls)
            assertTrue(participant.verifiedAbsent)
        }
}

private enum class CancellationPoint {
    AFTER_REMOVE_ACCOUNT,
    BETWEEN_ACCOUNT_AND_SERVER,
    BEFORE_PUBLISH,
}

private data class RemovalResult(
    val failure: Throwable?,
    val published: Boolean,
)

private class RemovalFixture {
    val account = AccountIdentity("server-1", "user-1")
    val accountStore = RecordingAccountStore()
    val serverStore = RecordingServerStore()
    private val registry = ServerScopedStoreRegistry()
    val coordinator: SessionTransitionCoordinator
    val removal =
        StoredAccountRemoval(
            removedSession = storedSession(account),
            activeSession = null,
            hasServerSibling = false,
        )
    var published = false
    var failure: Throwable? = null

    init {
        registry.register(accountStore)
        registry.register(serverStore)
        coordinator = SessionTransitionCoordinator(registry)
    }
}

private open class RecordingAccountStore : AccountScopedClearableStore {
    var accountClearCount = 0
        private set
    var onAccountClear: (() -> Unit)? = null

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        accountClearCount += 1
        onAccountClear?.invoke()
    }
}

private class ThrowingAccountStore(
    private val failure: Throwable,
) : RecordingAccountStore() {
    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        super.clearAccount(accountIdentity)
        throw failure
    }
}

private class RecordingServerStore : ServerScopedClearableStore {
    var serverClearCount = 0
        private set
    var onServerClear: (() -> Unit)? = null

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) {
        serverClearCount += 1
        onServerClear?.invoke()
    }
}

private class SwitchRecordingParticipant(
    private val lease: AccountWorkLease,
) : SessionBoundaryParticipant {
    val events = mutableListOf<String>()

    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> {
        events += "before:${gateHeldBoundaryCommit.isCurrentLease(lease)}"
        return Result.success(Unit)
    }

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
    ): Result<Unit> = Result.success(Unit)
}

private class RejectingRemovalParticipant : SessionBoundaryParticipant {
    var scope: SessionRemovalScope? = null
    var authorization: SessionRemovalAuthorization? = null

    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> {
        this.scope = scope
        this.authorization = authorization
        return Result.failure(SessionRemovalError.DownloadRemovalConfirmationRequired)
    }

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = error("Removal must not complete after failed preparation")

    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> = Result.success(Unit)
}

private class ReplayingRemovalParticipant(
    private val accountIdentity: AccountIdentity,
) : SessionBoundaryParticipant {
    var removeCalls = 0
        private set
    var verifiedAbsent = false
        private set

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
        executor.removeAccount(accountIdentity).getOrThrow()
        removeCalls += 1
        executor.removeAccount(accountIdentity).getOrThrow()
        removeCalls += 1
        verifiedAbsent = executor.isAccountAbsent(accountIdentity).getOrThrow()
        return Result.success(Unit)
    }
}

private fun storedSession(account: AccountIdentity): StoredSession =
    StoredSession(
        serverUrl = "https://${account.serverId}.example",
        serverId = account.serverId,
        serverName = account.serverId,
        userId = account.userId,
        userName = account.userId,
        accessToken = "token-${account.userId}",
        deviceId = "device-1",
    )

private fun StoredSession.toIdentity(): AccountIdentity =
    AccountIdentity(
        serverId = serverId,
        userId = userId,
    )
