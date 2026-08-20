// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoredSession
import com.jellyscope.core.data.local.accountId
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.AuthError
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
}

data class AccountRemoval(
    val removedAccount: AccountSession,
    val activeSession: Session?,
)

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

            val restore =
                runCatchingCancellable {
                    withContext(workDispatcher) {
                        if (sessionStore.isLogoutPending()) {
                            RestoredSessions(
                                activeSession = null,
                                storedAccounts = emptyList(),
                                logoutPending = true,
                            )
                        } else {
                            val activeSession = sessionStore.readSession()
                            val storedAccounts = sessionStore.readAccounts()
                            RestoredSessions(
                                activeSession = activeSession,
                                storedAccounts = storedAccounts,
                                logoutPending = false,
                            )
                        }
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
            if (restore?.logoutPending == true) {
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
                val activeSession = restore?.activeSession?.toDomain()
                sessionTransitionCoordinator.restore(activeSession) { boundaryEpoch ->
                    storedAccountsSource.value = restore?.storedAccounts.orEmpty()
                    _sessionState.value =
                        if (activeSession == null || boundaryEpoch == null) {
                            SessionState.LoggedOut(serverUrl = null)
                        } else {
                            SessionState.LoggedIn(activeSession, boundaryEpoch)
                        }
                    publishDerivedAccounts()
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
                publish = { session, boundaryEpoch ->
                    _sessionState.value = SessionState.LoggedIn(session, boundaryEpoch)
                    refreshStoredAccounts()
                    session
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
                    if (activeSession == null) {
                        _sessionState.value = SessionState.LoggedOut(transition.removedSession.serverUrl)
                    } else {
                        val boundaryEpoch =
                            transition.boundaryEpoch
                                ?: (sessionState.value as? SessionState.LoggedIn)?.boundaryEpoch
                                ?: throw IllegalStateException("Account removal fallback has no boundary epoch")
                        _sessionState.value = SessionState.LoggedIn(activeSession, boundaryEpoch)
                    }
                    // Publish the pre-read account list rather than calling
                    // refreshStoredAccounts(): that re-reads the store, whose
                    // migration retries the index/active-key writes and can throw
                    // here — skipping the failure aggregate and leaving accounts
                    // stale after _sessionState has already changed.
                    storedAccountsSource.value = transition.remainingSessions
                    publishDerivedAccounts()
                    AccountRemoval(
                        removedAccount = transition.removedSession.toStored().toAccount(activeAccountId = null),
                        activeSession = activeSession,
                    )
                },
            )
        }

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
            publish = { boundaryEpoch ->
                _sessionState.value = SessionState.LoggedIn(session, boundaryEpoch)
                refreshStoredAccounts()
            },
        )
    }

    private suspend fun refreshStoredAccounts() {
        val storedAccounts =
            withContext(workDispatcher) {
                sessionStore.readAccounts()
            }
        storedAccountsSource.value = storedAccounts
        publishDerivedAccounts()
    }
}

private data class RestoredSessions(
    val activeSession: StoredSession?,
    val storedAccounts: List<StoredSession>,
    val logoutPending: Boolean,
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
