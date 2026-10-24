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
                        homeInvoker =
                            when {
                                current.boundaryKey == key -> current.homeInvoker
                                current.selectedTab == LauncherTab.Essentials -> "apps:+"
                                loggedIn == null -> JELLYFIN_CONNECT
                                else -> LauncherTab.Jellyfin.focusKey
                            },
                        homeTabActivation = current.homeTabActivation && current.boundaryKey == key,
                        media =
                            media.state.value.takeIf { it.boundaryKey == key && !suppressMedia }
                                ?: LauncherMediaState(),
                    )
                }
                if (loggedIn != null) completeLogin(loginGeneration)
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
        _state.update { it.copy(focusGeneration = it.focusGeneration + 1, homeTabActivation = false) }
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
        _state.update { it.copy(homeInvoker = key, homeTabActivation = false) }
    }

    fun selectTab(tab: LauncherTab) {
        _state.update {
            if (it.route != LauncherRoute.Home) {
                it
            } else {
                it.copy(selectedTab = tab, homeInvoker = tab.focusKey, homeTabActivation = true, focusGeneration = it.focusGeneration + 1)
            }
        }
    }

    fun homeContentEntered() {
        _state.update { if (it.homeTabActivation) it.copy(homeTabActivation = false) else it }
    }

    fun beginLogin(origin: LauncherLoginOrigin) {
        val current = _state.value
        if (current.busy ||
            current.restoring ||
            current.signedIn ||
            current.cleanupPending ||
            sessions.sessionState.value !is SessionState.LoggedOut
        ) {
            return
        }
        _state.update {
            it.copy(
                route = LauncherRoute.Login,
                loginOrigin = origin,
                authError = null,
                homeTabActivation = false,
                focusGeneration = it.focusGeneration + 1,
            )
        }
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
                loginOrigin = if (route == LauncherRoute.Login) it.loginOrigin else null,
                homeTabActivation = false,
                focusGeneration = it.focusGeneration + 1,
            )
        }
    }

    fun backToHome() = navigate(LauncherRoute.Home)

    fun homeInvoked() {
        if (_state.value.route == LauncherRoute.Login) {
            cancelLogin(homeReset = true)
        } else {
            resetHome()
        }
    }

    private fun resetHome() {
        _state.update {
            it.copy(
                route = LauncherRoute.Home,
                dialog = null,
                authError = null,
                homeInvoker = "apps:+",
                selectedTab = LauncherTab.Essentials,
                homeTabActivation = false,
                loginOrigin = null,
                focusGeneration = it.focusGeneration + 1,
                homeResetGeneration = it.homeResetGeneration + 1,
            )
        }
    }

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
                        completeLogin(generation)
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

    // Session publication may precede the action return. Consume the origin once
    // so both success paths agree without navigating again after cancellation.
    private fun completeLogin(generation: Long) {
        if (generation != loginGeneration || sessions.sessionState.value !is SessionState.LoggedIn) return
        _state.update { current ->
            val origin = current.loginOrigin
            if (current.route != LauncherRoute.Login || origin == null) {
                current
            } else {
                val tab = if (origin == LauncherLoginOrigin.Jellyfin) LauncherTab.Jellyfin else current.selectedTab
                current.copy(
                    route = LauncherRoute.Home,
                    selectedTab = tab,
                    homeInvoker = tab.focusKey,
                    homeTabActivation = true,
                    loginOrigin = null,
                    authError = null,
                    focusGeneration = current.focusGeneration + 1,
                )
            }
        }
    }

    fun cancelLogin() = cancelLogin(homeReset = false)

    private fun cancelLogin(homeReset: Boolean) {
        loginGeneration += 1
        val cancelledJob = loginJob
        cancelledJob?.cancel()
        if (homeReset) {
            resetHome()
        } else {
            _state.update {
                val jellyfin = it.loginOrigin == LauncherLoginOrigin.Jellyfin
                it.copy(
                    route = if (jellyfin) LauncherRoute.Home else LauncherRoute.Settings,
                    selectedTab = if (jellyfin) LauncherTab.Jellyfin else it.selectedTab,
                    homeInvoker = if (jellyfin) JELLYFIN_CONNECT else it.homeInvoker,
                    homeTabActivation = false,
                    loginOrigin = null,
                    authError = null,
                    focusGeneration = it.focusGeneration + 1,
                )
            }
        }
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
            it.copy(
                route = LauncherRoute.Home,
                homeInvoker = if (it.selectedTab == LauncherTab.Essentials) "apps:+" else JELLYFIN_CONNECT,
                homeTabActivation = false,
                loginOrigin = null,
                media = LauncherMediaState(),
                busy = true,
            )
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
