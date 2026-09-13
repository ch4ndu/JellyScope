// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.CommittedSessionSnapshot
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val emptyAccounts = MutableStateFlow<List<AccountSession>>(emptyList()).asStateFlow()

interface SessionRepository {
    val sessionState: StateFlow<SessionState>

    val accounts: StateFlow<List<AccountSession>>
        get() = emptyAccounts

    suspend fun setLoggedIn(session: Session)

    suspend fun setLoggedIn(
        session: Session,
        authenticationAttempt: AuthenticationAttemptToken,
    ) {
        setLoggedIn(session)
    }

    suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
    ): Result<Unit>

    suspend fun switchTo(accountId: String): Result<Session> = Result.failure(AuthError.AccountNotFound)

    suspend fun removeAccount(
        accountId: String,
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
    ): Result<AccountRemoval> = Result.failure(AuthError.AccountNotFound)

    suspend fun updateParentalRating(
        expectedSession: Session,
        expectedBoundaryEpoch: Long,
        maxParentalRating: Int?,
    ): ParentalRatingUpdateResult = ParentalRatingUpdateResult.Rejected
}

data class AccountRemoval(
    val removedAccount: AccountSession,
    val activeSession: Session?,
)

enum class ParentalRatingUpdateResult {
    Updated,
    Unchanged,
    Rejected,
}

class DefaultSessionRepository(
    private val sessionStore: SessionStore,
    private val sessionTransitionCoordinator: SessionTransitionCoordinator,
    scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : SessionRepository {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    private val storedAccountsSource = MutableStateFlow<List<StoredSession>>(emptyList())
    private val _accounts = MutableStateFlow<List<AccountSession>>(emptyList())
    override val accounts: StateFlow<List<AccountSession>> = _accounts.asStateFlow()

    // Recompute the exposed account list from the persisted sessions plus the LIVE
    // session, so an account's isActive can never disagree with _sessionState. Called
    // from the non-cancellable transition publish sites so accounts and sessionState
    // are updated together and cannot desync (the add-account remount race).
    private fun publishDerivedAccounts() {
        val activeAccountId =
            (_sessionState.value as? SessionState.LoggedIn)?.session?.toStored()?.accountId()
        _accounts.value = storedAccountsSource.value.toAccounts(activeAccountId)
    }

    private fun publishCommittedSnapshot(
        snapshot: CommittedSessionSnapshot,
        boundaryEpoch: Long?,
        loggedOutServerUrl: String? = null,
    ) {
        storedAccountsSource.value = snapshot.sessions
        val activeSession = snapshot.activeSession?.toDomain()
        _sessionState.value =
            if (activeSession == null || boundaryEpoch == null) {
                SessionState.LoggedOut(serverUrl = loggedOutServerUrl)
            } else {
                SessionState.LoggedIn(activeSession, boundaryEpoch)
            }
        _accounts.value = snapshot.sessions.toAccounts(snapshot.activeAccountId)
    }

    init {
        scope.launch {
            val removalRecoveryCompleted =
                runCatchingCancellable {
                    sessionTransitionCoordinator.resumeIncompleteRemovalOperations {
                        storedAccountsSource.value = emptyList()
                        _sessionState.value = SessionState.LoggedOut(serverUrl = null)
                        publishDerivedAccounts()
                    }
                }.onFailure { throwable ->
                    sessionLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "session-removal-recovery",
                            event = "failed",
                            throwable = throwable,
                        )
                    }
                }.isSuccess
            if (!removalRecoveryCompleted) {
                return@launch
            }

            val restoredSnapshot =
                runCatchingCancellable {
                    withContext(workDispatcher) {
                        sessionStore.readSnapshot()
                    }
                }.onFailure { throwable ->
                    sessionLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "session-restore",
                            event = "failed",
                            throwable = throwable,
                        )
                    }
                }.getOrNull()
            if (restoredSnapshot?.logoutPending == true) {
                runCatchingCancellable {
                    sessionTransitionCoordinator.commitLogout(
                        prepareLogout = { sessionStore.markLogoutPending() },
                        publishLoggedOut = {
                            _sessionState.value = SessionState.LoggedOut(serverUrl = null)
                        },
                        publishAccountsCleared = {
                            storedAccountsSource.value = emptyList()
                            publishDerivedAccounts()
                        },
                    )
                }.onFailure { throwable ->
                    sessionLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "session-logout-retry",
                            event = "failed",
                            throwable = throwable,
                        )
                    }
                }
            } else {
                val snapshot = restoredSnapshot ?: emptyCommittedSessionSnapshot
                sessionTransitionCoordinator.restore(snapshot) { committedSnapshot, boundaryEpoch ->
                    publishCommittedSnapshot(committedSnapshot, boundaryEpoch)
                }
            }
        }
    }

    override suspend fun setLoggedIn(session: Session) {
        setLoggedInInternal(session, authenticationAttempt = null)
    }

    override suspend fun setLoggedIn(
        session: Session,
        authenticationAttempt: AuthenticationAttemptToken,
    ) {
        setLoggedInInternal(session, authenticationAttempt)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> =
        runCatchingCancellable {
            sessionTransitionCoordinator.commitLogout(
                authorization = authorization,
                prepareLogout = { sessionStore.markLogoutPending() },
                publishLoggedOut = {
                    _sessionState.value = SessionState.LoggedOut(serverUrl)
                },
                publishAccountsCleared = {
                    storedAccountsSource.value = emptyList()
                    publishDerivedAccounts()
                },
            )
        }

    override suspend fun switchTo(accountId: String): Result<Session> =
        runCatchingCancellable {
            sessionTransitionCoordinator.commitSwitch(
                currentAccountIdentity =
                    (sessionState.value as? SessionState.LoggedIn)
                        ?.session
                        ?.accountIdentity(),
                load = { sessionStore.switchActiveAccount(accountId) },
                publish = { snapshot, boundaryEpoch ->
                    publishCommittedSnapshot(snapshot, boundaryEpoch)
                    snapshot.activeSession?.toDomain()
                        ?: throw IllegalStateException("Committed account switch has no active session.")
                },
            )
        }

    override suspend fun removeAccount(
        accountId: String,
        authorization: SessionRemovalAuthorization,
    ): Result<AccountRemoval> =
        runCatchingCancellable {
            val accountIdentity =
                storedAccountsSource.value
                    .firstOrNull { stored -> stored.accountId() == accountId }
                    ?.let { stored -> AccountIdentity(stored.serverId, stored.userId) }
                    ?: throw AuthError.AccountNotFound
            sessionTransitionCoordinator.commitAccountRemoval(
                accountIdentity = accountIdentity,
                authorization = authorization,
                removeAccount = { sessionStore.removeAccount(accountId) },
                publish = { transition ->
                    val activeSession = transition.activeSession
                    val boundaryEpoch =
                        if (activeSession == null) {
                            null
                        } else {
                            transition.boundaryEpoch
                                ?: (sessionState.value as? SessionState.LoggedIn)?.boundaryEpoch
                                ?: throw IllegalStateException("Account removal fallback has no boundary epoch")
                        }
                    publishCommittedSnapshot(
                        snapshot = transition.committedSnapshot,
                        boundaryEpoch = boundaryEpoch,
                        loggedOutServerUrl = transition.removedSession.serverUrl,
                    )
                    AccountRemoval(
                        removedAccount = transition.removedSession.toStored().toAccount(activeAccountId = null),
                        activeSession = activeSession,
                    )
                },
            )
        }

    override suspend fun updateParentalRating(
        expectedSession: Session,
        expectedBoundaryEpoch: Long,
        maxParentalRating: Int?,
    ): ParentalRatingUpdateResult =
        sessionTransitionCoordinator.commitSameBoundaryParentalRatingUpdate(
            expectedSession = expectedSession,
            expectedBoundaryEpoch = expectedBoundaryEpoch,
            maxParentalRating = maxParentalRating,
            currentSessionState = { _sessionState.value },
            readSnapshot = { sessionStore.readSnapshot() },
            persist = { updatedSession -> sessionStore.writeSession(updatedSession.toStored()) },
            publish = { snapshot -> publishCommittedSnapshot(snapshot, expectedBoundaryEpoch) },
        )

    private suspend fun setLoggedInInternal(
        session: Session,
        authenticationAttempt: AuthenticationAttemptToken?,
    ) {
        sessionTransitionCoordinator.commitLoggedIn(
            session = session,
            authenticationAttempt = authenticationAttempt,
            previousAccountIdentity =
                (sessionState.value as? SessionState.LoggedIn)
                    ?.session
                    ?.accountIdentity(),
            persist = { sessionStore.writeSession(session.toStored()) },
            publish = { snapshot, boundaryEpoch ->
                publishCommittedSnapshot(snapshot, boundaryEpoch)
            },
        )
    }
}

private val emptyCommittedSessionSnapshot =
    CommittedSessionSnapshot(
        sessions = emptyList(),
        activeAccountId = null,
        activeSession = null,
        logoutPending = false,
    )

internal fun StoredSession.toDomain(): Session =
    Session(
        serverUrl = serverUrl,
        serverId = serverId,
        serverName = serverName,
        userId = userId,
        userName = userName,
        accessToken = accessToken,
        deviceId = deviceId,
        enableContentDownloading = enableContentDownloading,
        maxParentalRating = maxParentalRating,
    )

internal fun Session.toStored(): StoredSession =
    StoredSession(
        serverUrl = serverUrl,
        serverId = serverId,
        serverName = serverName,
        userId = userId,
        userName = userName,
        accessToken = accessToken,
        deviceId = deviceId,
        enableContentDownloading = enableContentDownloading,
        maxParentalRating = maxParentalRating,
    )

private fun List<StoredSession>.toAccounts(activeAccountId: String?): List<AccountSession> =
    map { session -> session.toAccount(activeAccountId) }

private fun StoredSession.toAccount(activeAccountId: String?): AccountSession =
    AccountSession(
        accountId = accountId(),
        serverUrl = serverUrl,
        serverId = serverId,
        serverName = serverName,
        userId = userId,
        userName = userName,
        avatarUserId = userId,
        isActive = accountId() == activeAccountId,
    )

private val sessionLogger = diagnosticLogger(DiagnosticTag.SessionRepository)
