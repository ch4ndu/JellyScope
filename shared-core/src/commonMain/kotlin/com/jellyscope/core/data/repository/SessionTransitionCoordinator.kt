// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.BoundaryMutation
import com.jellyscope.core.data.local.CommittedSessionSnapshot
import com.jellyscope.core.data.local.LogoutCleanupCancellationException
import com.jellyscope.core.data.local.PersistentAccountStoreCleaner
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoreCleanupException
import com.jellyscope.core.data.local.StoredAccountRemoval
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.data.local.appendCleanupFailures
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Serializes session-boundary mutations through the registry's existing
 * mutation gate. Network authentication remains outside the gate; only its
 * attempt-token validation and final commit are serialized here.
 */
class SessionTransitionCoordinator(
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val persistentAccountStoreCleaner: PersistentAccountStoreCleaner? = null,
    private val sessionStore: SessionStore? = null,
    private val sessionBoundaryParticipant: SessionBoundaryParticipant = NoOpSessionBoundaryParticipant,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    private val advanceLocalSubtitleAccountBarrier: suspend (AccountIdentity) -> Unit = {},
) {
    private var nextBoundaryEpoch = 0L
    private var latestAuthenticationAttempt = 0L

    suspend fun beginAuthenticationAttempt(): AuthenticationAttemptToken =
        serverScopedStoreRegistry.withBoundaryMutation {
            latestAuthenticationAttempt += 1
            AuthenticationAttemptToken(latestAuthenticationAttempt)
        }

    /**
     * Replays durable cross-store removal work before the repository performs
     * any normal logout/session/account read. A failure installs and publishes
     * a logged-out boundary so cold restoration cannot expose stale credentials.
     */
    suspend fun resumeIncompleteRemovalOperations(publishLoggedOutOnFailure: suspend () -> Unit) {
        serverScopedStoreRegistry.withBoundaryMutation {
            invalidateAuthenticationAttempts()
            try {
                withContext(NonCancellable) {
                    sessionBoundaryParticipant
                        .resumeIncompleteRemovalOperations(
                            executor = GateHeldSessionRemovalExecutor(this@withBoundaryMutation),
                            gateHeldBoundaryCommit = this@withBoundaryMutation,
                        ).getOrThrow()
                }
            } catch (throwable: Throwable) {
                installLoggedOut()
                withContext(NonCancellable) { publishLoggedOutOnFailure() }
                throw throwable
            }
        }
    }

    internal suspend fun <T> restore(
        snapshot: CommittedSessionSnapshot,
        publish: suspend (snapshot: CommittedSessionSnapshot, boundaryEpoch: Long?) -> T,
    ): T =
        serverScopedStoreRegistry.withBoundaryMutation {
            latestAuthenticationAttempt += 1
            val activeSession = snapshot.activeSession?.toDomain()
            if (activeSession == null) {
                installLoggedOut()
                publish(snapshot, null)
            } else {
                val boundaryEpoch = nextBoundaryEpoch()
                installRestoredAccount(activeSession.accountIdentity(), boundaryEpoch)
                publish(snapshot, boundaryEpoch)
            }
        }

    internal suspend fun <T> commitLoggedIn(
        session: Session,
        authenticationAttempt: AuthenticationAttemptToken?,
        previousAccountIdentity: AccountIdentity? = null,
        persist: suspend () -> CommittedSessionSnapshot,
        publish: suspend (snapshot: CommittedSessionSnapshot, boundaryEpoch: Long) -> T,
    ): T =
        serverScopedStoreRegistry.withBoundaryMutation {
            val callerContext = currentCoroutineContext()
            validateAuthenticationAttempt(authenticationAttempt)
            previousAccountIdentity?.let { accountIdentity ->
                sessionBoundaryParticipant
                    .beforeAccountSwitch(accountIdentity, this)
                    .getOrThrow()
            }
            callerContext.ensureActive()
            val result =
                withContext(NonCancellable) {
                    val committedSnapshot = withContext(ioDispatcher) { persist() }
                    previousAccountIdentity?.let { accountIdentity ->
                        advanceLocalSubtitleAccountBarrier(accountIdentity)
                    }
                    val boundaryEpoch = nextBoundaryEpoch()
                    withContext(ioDispatcher) {
                        transitionToAccount(session.accountIdentity(), boundaryEpoch)
                    }
                    publish(committedSnapshot, boundaryEpoch)
                }
            callerContext.ensureActive()
            result
        }

    internal suspend fun <T> commitSwitch(
        currentAccountIdentity: AccountIdentity?,
        load: suspend () -> CommittedSessionSnapshot?,
        publish: suspend (snapshot: CommittedSessionSnapshot, boundaryEpoch: Long) -> T,
    ): T =
        serverScopedStoreRegistry.withBoundaryMutation {
            val callerContext = currentCoroutineContext()
            currentAccountIdentity?.let { accountIdentity ->
                sessionBoundaryParticipant
                    .beforeAccountSwitch(accountIdentity, this)
                    .getOrThrow()
            }
            invalidateAuthenticationAttempts()
            callerContext.ensureActive()
            val result =
                withContext(NonCancellable) {
                    val committedSnapshot =
                        withContext(ioDispatcher) { load() }
                            ?: throw com.jellyscope.core.domain.action.AuthError.AccountNotFound
                    val session =
                        committedSnapshot.activeSession?.toDomain()
                            ?: throw com.jellyscope.core.domain.action.AuthError.AccountNotFound
                    currentAccountIdentity?.let { accountIdentity ->
                        advanceLocalSubtitleAccountBarrier(accountIdentity)
                    }
                    val boundaryEpoch = nextBoundaryEpoch()
                    withContext(ioDispatcher) {
                        transitionToAccount(session.accountIdentity(), boundaryEpoch)
                    }
                    publish(committedSnapshot, boundaryEpoch)
                }
            callerContext.ensureActive()
            result
        }

    internal suspend fun commitSameBoundaryParentalRatingUpdate(
        expectedSession: Session,
        expectedBoundaryEpoch: Long,
        maxParentalRating: Int?,
        currentSessionState: () -> SessionState,
        readSnapshot: suspend () -> CommittedSessionSnapshot,
        persist: suspend (Session) -> CommittedSessionSnapshot,
        publish: suspend (CommittedSessionSnapshot) -> Unit,
    ): ParentalRatingUpdateResult =
        serverScopedStoreRegistry.withBoundaryMutation {
            val current = currentSessionState()
            if (current !is SessionState.LoggedIn ||
                current.boundaryEpoch != expectedBoundaryEpoch ||
                current.session != expectedSession
            ) {
                return@withBoundaryMutation ParentalRatingUpdateResult.Rejected
            }

            val expectedStored = expectedSession.toStored()
            val persistedSnapshot = withContext(ioDispatcher) { readSnapshot() }
            if (persistedSnapshot.logoutPending ||
                persistedSnapshot.activeAccountId != expectedStored.accountId() ||
                persistedSnapshot.activeSession != expectedStored
            ) {
                return@withBoundaryMutation ParentalRatingUpdateResult.Rejected
            }

            val updatedSession = expectedSession.copy(maxParentalRating = maxParentalRating)
            if (updatedSession == expectedSession) {
                return@withBoundaryMutation ParentalRatingUpdateResult.Unchanged
            }

            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            withContext(NonCancellable) {
                val committedSnapshot = withContext(ioDispatcher) { persist(updatedSession) }
                publish(committedSnapshot)
            }
            callerContext.ensureActive()
            ParentalRatingUpdateResult.Updated
        }

    internal suspend fun <T> commitAccountRemoval(
        accountIdentity: AccountIdentity,
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
        removeAccount: suspend () -> StoredAccountRemoval?,
        publish: suspend (transition: AccountRemovalTransition) -> T,
    ): T =
        serverScopedStoreRegistry.withBoundaryMutation {
            val outerContext = currentCoroutineContext()
            val failures = mutableListOf<Throwable>()
            val result =
                withContext(NonCancellable) {
                    val preparedRemoval =
                        sessionBoundaryParticipant
                            .prepareRemoval(
                                scope = SessionRemovalScope.Account(accountIdentity),
                                authorization = authorization,
                                gateHeldBoundaryCommit = this@withBoundaryMutation,
                            ).getOrThrow()
                    invalidateAuthenticationAttempts()
                    val removal =
                        withContext(ioDispatcher) { removeAccount() }
                            ?: throw com.jellyscope.core.domain.action.AuthError.AccountNotFound
                    val removedSession = removal.removedSession.toDomain()
                    // Mutations that failed after the credential was already
                    // deleted: the removal is irreversible, so they join the
                    // aggregate rather than aborting cleanup and publication.
                    removal.mutationFailures.forEach { throwable ->
                        appendCleanupFailures(failures, throwable)
                    }

                    try {
                        withContext(ioDispatcher) {
                            clearAccount(removedSession.accountIdentity())
                        }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }

                    try {
                        withContext(ioDispatcher) {
                            persistentAccountStoreCleaner?.clearAccount(removedSession.accountIdentity())
                        }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }

                    if (!removal.hasServerSibling) {
                        try {
                            withContext(ioDispatcher) {
                                clearServer(removedSession.serverId)
                            }
                        } catch (throwable: Throwable) {
                            appendCleanupFailures(failures, throwable)
                        }

                        try {
                            withContext(ioDispatcher) {
                                persistentAccountStoreCleaner?.clearServer(removedSession.serverId)
                            }
                        } catch (throwable: Throwable) {
                            appendCleanupFailures(failures, throwable)
                        }
                    }

                    if (preparedRemoval != null) {
                        try {
                            sessionBoundaryParticipant
                                .completeRemoval(
                                    removal = preparedRemoval,
                                    executor = GateHeldSessionRemovalExecutor(this@withBoundaryMutation),
                                    gateHeldBoundaryCommit = this@withBoundaryMutation,
                                ).getOrThrow()
                        } catch (throwable: Throwable) {
                            appendCleanupFailures(failures, throwable)
                        }
                    }

                    val activeSession = removal.activeSession?.toDomain()
                    val boundaryEpoch =
                        when {
                            activeSession == null -> {
                                invalidateBoundary()
                                null
                            }
                            activeSession.accountIdentity() != removedSession.accountIdentity() -> {
                                val epoch = nextBoundaryEpoch()
                                try {
                                    withContext(ioDispatcher) {
                                        transitionToAccount(activeSession.accountIdentity(), epoch)
                                    }
                                } catch (throwable: Throwable) {
                                    appendCleanupFailures(failures, throwable)
                                }
                                epoch
                            }
                            else -> null
                        }
                    publish(
                        AccountRemovalTransition(
                            removedSession = removedSession,
                            activeSession = activeSession,
                            boundaryEpoch = boundaryEpoch,
                            committedSnapshot = removal.committedSnapshot,
                        ),
                    )
                }

            val storeCancellation = failures.firstOrNull { throwable -> throwable is CancellationException }
            if (storeCancellation != null) {
                throw LogoutCleanupCancellationException(failures)
            }
            try {
                outerContext.ensureActive()
            } catch (cancellation: CancellationException) {
                throw LogoutCleanupCancellationException(failures + cancellation)
            }
            if (failures.isNotEmpty()) {
                throw StoreCleanupException(failures)
            }
            result
        }

    suspend fun <T> commitLogout(
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
        publishLoggedOut: suspend () -> Unit,
        publishAccountsCleared: suspend () -> T,
        prepareLogout: suspend () -> Unit = {},
    ): T =
        serverScopedStoreRegistry.withBoundaryMutation {
            val outerContext = currentCoroutineContext()
            val failures = mutableListOf<Throwable>()
            val result =
                withContext(NonCancellable) {
                    val accountIdentities = stableLogoutAccountIdentities()
                    val preparedRemoval =
                        sessionBoundaryParticipant
                            .prepareRemoval(
                                scope = SessionRemovalScope.FullLogout(accountIdentities),
                                authorization = authorization,
                                gateHeldBoundaryCommit = this@withBoundaryMutation,
                            ).getOrThrow()
                    // Fail-closed prerequisite: durably record the logout-pending
                    // marker before any observable or credential-destructive step.
                    withContext(ioDispatcher) { prepareLogout() }
                    invalidateAuthenticationAttempts()
                    invalidateBoundary()
                    publishLoggedOut()

                    try {
                        withContext(ioDispatcher) { persistentAccountStoreCleaner?.clearAll() }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }

                    try {
                        withContext(ioDispatcher) { clearAll() }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }

                    if (preparedRemoval != null) {
                        try {
                            sessionBoundaryParticipant
                                .completeRemoval(
                                    removal = preparedRemoval,
                                    executor = GateHeldSessionRemovalExecutor(this@withBoundaryMutation),
                                    gateHeldBoundaryCommit = this@withBoundaryMutation,
                                ).getOrThrow()
                        } catch (throwable: Throwable) {
                            appendCleanupFailures(failures, throwable)
                        }
                    }

                    publishAccountsCleared()
                }

            val storeCancellation = failures.firstOrNull { throwable -> throwable is CancellationException }
            if (storeCancellation != null) {
                throw LogoutCleanupCancellationException(failures)
            }
            try {
                outerContext.ensureActive()
            } catch (cancellation: CancellationException) {
                throw LogoutCleanupCancellationException(failures + cancellation)
            }
            if (failures.isNotEmpty()) {
                throw StoreCleanupException(failures)
            }
            result
        }

    private inner class GateHeldSessionRemovalExecutor(
        private val boundaryMutation: BoundaryMutation,
    ) : SessionRemovalExecutor {
        override suspend fun removeAccount(accountIdentity: AccountIdentity): Result<Unit> =
            withContext(NonCancellable) {
                val store =
                    sessionStore
                        ?: return@withContext Result.failure(
                            IllegalStateException("Session removal executor has no session store."),
                        )
                val storedAccounts =
                    try {
                        withContext(ioDispatcher) { store.readSnapshot().sessions }
                    } catch (throwable: Throwable) {
                        return@withContext Result.failure(throwable)
                    }
                val hasServerSibling =
                    storedAccounts.any { stored ->
                        stored.serverId == accountIdentity.serverId &&
                            stored.userId != accountIdentity.userId
                    }
                val failures = mutableListOf<Throwable>()

                try {
                    withContext(ioDispatcher) {
                        if (persistentAccountStoreCleaner == null) {
                            store.clearAccount(accountIdentity)
                        } else {
                            persistentAccountStoreCleaner.clearAccount(accountIdentity)
                        }
                    }
                } catch (throwable: Throwable) {
                    appendCleanupFailures(failures, throwable)
                }

                try {
                    withContext(ioDispatcher) { boundaryMutation.clearAccount(accountIdentity) }
                } catch (throwable: Throwable) {
                    appendCleanupFailures(failures, throwable)
                }

                if (!hasServerSibling) {
                    try {
                        withContext(ioDispatcher) {
                            persistentAccountStoreCleaner?.clearServer(accountIdentity.serverId)
                        }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }
                    try {
                        withContext(ioDispatcher) { boundaryMutation.clearServer(accountIdentity.serverId) }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                    }
                }

                val absent =
                    try {
                        withContext(ioDispatcher) {
                            store.readSnapshot().sessions.none { stored ->
                                stored.serverId == accountIdentity.serverId && stored.userId == accountIdentity.userId
                            }
                        }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                        false
                    }
                if (!absent && failures.isEmpty()) {
                    failures += IllegalStateException("Session account removal did not settle.")
                }
                cleanupResult(failures)
            }

        override suspend fun removeAllAccounts(): Result<Unit> =
            withContext(NonCancellable) {
                val store =
                    sessionStore
                        ?: return@withContext Result.failure(
                            IllegalStateException("Session removal executor has no session store."),
                        )
                val failures = mutableListOf<Throwable>()

                try {
                    withContext(ioDispatcher) {
                        if (persistentAccountStoreCleaner == null) {
                            store.clearSession()
                            store.clearLogoutPending()
                        } else {
                            persistentAccountStoreCleaner.clearAll()
                        }
                    }
                } catch (throwable: Throwable) {
                    appendCleanupFailures(failures, throwable)
                }

                try {
                    withContext(ioDispatcher) { boundaryMutation.clearAll() }
                } catch (throwable: Throwable) {
                    appendCleanupFailures(failures, throwable)
                }

                val accountsAbsent =
                    try {
                        withContext(ioDispatcher) { store.readSnapshot().sessions.isEmpty() }
                    } catch (throwable: Throwable) {
                        appendCleanupFailures(failures, throwable)
                        false
                    }
                if (!accountsAbsent && failures.isEmpty()) {
                    failures += IllegalStateException("Full session removal did not settle.")
                }
                cleanupResult(failures)
            }

        override suspend fun isAccountAbsent(accountIdentity: AccountIdentity): Result<Boolean> {
            val store =
                sessionStore
                    ?: return Result.failure(
                        IllegalStateException("Session removal executor has no session store."),
                    )
            return try {
                Result.success(
                    withContext(ioDispatcher) {
                        store.readSnapshot().sessions.none { stored ->
                            stored.serverId == accountIdentity.serverId && stored.userId == accountIdentity.userId
                        }
                    },
                )
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        }
    }

    private fun cleanupResult(failures: List<Throwable>): Result<Unit> =
        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(StoreCleanupException(failures.toList()))
        }

    private suspend fun stableLogoutAccountIdentities(): List<AccountIdentity> {
        if (sessionBoundaryParticipant === NoOpSessionBoundaryParticipant) {
            return emptyList()
        }
        val store =
            checkNotNull(sessionStore) {
                "A session store is required for participant-backed logout removal."
            }
        return withContext(ioDispatcher) {
            store
                .readSnapshot()
                .sessions
                .map { stored ->
                    AccountIdentity(
                        serverId = stored.serverId,
                        userId = stored.userId,
                    )
                }.distinct()
        }
    }

    private fun validateAuthenticationAttempt(attempt: AuthenticationAttemptToken?) {
        if (attempt != null && attempt.sequence != latestAuthenticationAttempt) {
            throw CancellationException("Authentication attempt was superseded.")
        }
        invalidateAuthenticationAttempts()
    }

    private fun invalidateAuthenticationAttempts() {
        latestAuthenticationAttempt += 1
    }

    private fun nextBoundaryEpoch(): Long {
        nextBoundaryEpoch += 1
        return nextBoundaryEpoch
    }
}

class AuthenticationAttemptToken internal constructor(
    internal val sequence: Long,
)

internal data class AccountRemovalTransition(
    val removedSession: Session,
    val activeSession: Session?,
    val boundaryEpoch: Long?,
    val committedSnapshot: CommittedSessionSnapshot,
)
