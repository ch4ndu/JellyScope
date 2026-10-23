// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.data.repository.SessionTransitionCoordinator
import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LauncherViewModel(
    private val sessions: SessionRepository,
    private val coordinator: SessionTransitionCoordinator,
    private val loginAction: LoginAction,
    private val validateServer: ValidateServerAction,
    private val logoutAction: LogoutAction,
    private val media: LauncherMediaRepository,
    private val apps: LauncherAppsRepository,
    cleaner: LauncherAccountStoreCleaner,
    private val authMutation: Mutex,
) : ViewModel() {
    private val _state = MutableStateFlow(LauncherUiState())
    val state = _state.asStateFlow()
    private var loginJob: Job? = null
    private var loginGeneration = 0L
    private var foregroundJob: Job? = null
    private var suppressMedia = false

    init {
        viewModelScope.launch {
            sessions.sessionState.collect { sessionState ->
                val loggedIn = sessionState as? SessionState.LoggedIn
                val key = loggedIn?.let { LauncherBoundary(it.session.accountIdentity(), it.boundaryEpoch).key }
                if (_state.value.boundaryKey != key) suppressMedia = false
                _state.update { current ->
                    current.copy(
                        restoring = sessionState == SessionState.Restoring,
                        signedIn = loggedIn != null,
                        accountLabel = loggedIn?.session?.let { "${it.userName} · ${it.serverName}" }.orEmpty(),
                        serverUrl =
                            loggedIn?.session?.serverUrl
                                ?: (sessionState as? SessionState.LoggedOut)?.serverUrl.orEmpty(),
                        boundaryKey = key,
                        media =
                            media.state.value.takeIf { it.boundaryKey == key && !suppressMedia }
                                ?: LauncherMediaState(),
                        route = if (loggedIn != null && current.route == LauncherRoute.Login) LauncherRoute.Home else current.route,
                    )
                }
            }
        }
        viewModelScope.launch {
            media.state.collect { value ->
                _state.update { current ->
                    current.copy(
                        media =
                            value.takeIf { it.boundaryKey == current.boundaryKey && !suppressMedia }
                                ?: LauncherMediaState(),
                    )
                }
            }
        }
        viewModelScope.launch {
            apps.state.collect { value -> _state.update { it.copy(apps = value) } }
        }
        viewModelScope.launch {
            cleaner.cleanupPending.collect { value -> _state.update { it.copy(cleanupPending = value) } }
        }
        viewModelScope.launch {
            media.invalidSessions.collect { expected ->
                authMutation.withLock {
                    val current = sessions.sessionState.value as? SessionState.LoggedIn
                    if (current != null &&
                        current.session.accountIdentity() == expected.account &&
                        current.boundaryEpoch == expected.epoch
                    ) {
                        logoutLocked()
                    }
                }
            }
        }
    }

    fun foreground(started: Boolean) {
        foregroundJob?.cancel()
        foregroundJob = null
        if (started) {
            foregroundJob =
                viewModelScope.launch {
                    while (true) {
                        delay(15L * 60 * 1000)
                        media.refresh()
                    }
                }
        }
    }

    fun resumed(externalReturn: Boolean = false) {
        _state.update { it.copy(focusGeneration = it.focusGeneration + 1) }
        viewModelScope.launch {
            refreshApps()
            media.refresh(manual = externalReturn)
        }
    }

    fun refresh() {
        media.refresh(manual = true)
        viewModelScope.launch { refreshApps() }
    }

    fun rememberInvoker(key: String) {
        _state.update { it.copy(homeInvoker = key) }
    }

    fun appUnavailable() {
        showDialog(LauncherDialog.AppUnavailable)
        viewModelScope.launch { refreshApps() }
    }

    fun navigate(
        route: LauncherRoute,
        invoker: String? = null,
    ) {
        _state.update {
            it.copy(
                route = route,
                homeInvoker = invoker ?: it.homeInvoker,
                authError = null,
                focusGeneration = it.focusGeneration + 1,
            )
        }
    }

    fun backToHome() = navigate(LauncherRoute.Home)

    fun showDialog(dialog: LauncherDialog) {
        _state.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null, focusGeneration = it.focusGeneration + 1) }
    }

    fun login(
        server: String,
        user: String,
        password: String,
    ) {
        if (_state.value.busy || _state.value.restoring || _state.value.signedIn || _state.value.cleanupPending) return
        val generation = ++loginGeneration
        _state.update { it.copy(busy = true, authError = null) }
        loginJob =
            viewModelScope.launch {
                try {
                    authMutation.withLock {
                        if (generation != loginGeneration || sessions.sessionState.value is SessionState.LoggedIn) return@withLock
                        val validated = validateServer(server).getOrThrow()
                        loginAction(validated.serverUrl, user, password).getOrThrow()
                        if (generation == loginGeneration) navigate(LauncherRoute.Home)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == loginGeneration) _state.update { it.copy(authError = authError(failure)) }
                } finally {
                    if (generation == loginGeneration) _state.update { it.copy(busy = false) }
                }
            }
    }

    fun cancelLogin() {
        loginGeneration += 1
        val cancelledJob = loginJob
        cancelledJob?.cancel()
        navigate(LauncherRoute.Settings)
        // Exit UI immediately. Ordering invalidation with the old job prevents
        // it from cancelling a newer login; a commit that already won is honored.
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                authMutation.withLock {
                    coordinator.beginAuthenticationAttempt()
                    cancelledJob?.cancelAndJoin()
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun logout() {
        dismissDialog()
        viewModelScope.launch { authMutation.withLock { logoutLocked() } }
    }

    private suspend fun logoutLocked() {
        suppressMedia = true
        _state.update {
            it.copy(route = LauncherRoute.Home, homeInvoker = "apps:+", media = LauncherMediaState(), busy = true)
        }
        try {
            logoutAction().getOrThrow()
            _state.update { it.copy(cleanupPending = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("logout", failure)
            _state.update { it.copy(cleanupPending = true) }
            showDialog(LauncherDialog.CleanupFailed)
        } finally {
            _state.update { it.copy(busy = false, focusGeneration = it.focusGeneration + 1) }
        }
    }

    fun pin(key: String) = editApps { apps.pin(key) }

    fun unpin(key: String) = editApps { apps.unpin(key) }

    fun move(
        key: String,
        delta: Int,
    ) = editApps { apps.move(key, delta) }

    private suspend fun refreshApps() {
        try {
            apps.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("apps-refresh", failure)
        }
    }

    private fun editApps(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                launcherFailure("pins-write", failure)
                showDialog(LauncherDialog.AppUnavailable)
            }
        }
    }

    private fun authError(failure: Exception): LauncherAuthError =
        when (failure) {
            AuthError.InvalidUrl -> LauncherAuthError.InvalidServer
            AuthError.InvalidCredentials -> LauncherAuthError.Credentials
            AuthError.NotReachable -> LauncherAuthError.Unreachable
            is AuthError.ServerError -> LauncherAuthError.Server
            else -> LauncherAuthError.Cleanup
        }
}
