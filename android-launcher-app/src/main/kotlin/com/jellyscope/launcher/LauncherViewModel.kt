// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.ui.geometry.Rect
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
    private val preferences: LauncherPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(LauncherUiState())
    val state = _state.asStateFlow()
    private var loginJob: Job? = null
    private var loginGeneration = 0L
    private var foregroundJob: Job? = null
    private var suppressMedia = false
    private var tabRestorationEligible = true

    init {
        viewModelScope.launch {
            preferences.state.collect { value -> _state.update { it.copy(preferences = value) } }
        }
        viewModelScope.launch {
            sessions.sessionState.collect { sessionState ->
                val loggedIn = sessionState as? SessionState.LoggedIn
                val key = loggedIn?.let { LauncherBoundary(it.session.accountIdentity(), it.boundaryEpoch).key }
                val boundaryChanged = _state.value.boundaryKey != key
                if (boundaryChanged) suppressMedia = false
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
                                current.selectedTab == LauncherTab.Essentials -> HOME_CONTENT
                                loggedIn == null -> JELLYFIN_CONNECT
                                else -> LauncherTab.Jellyfin.focusKey
                            },
                        homeTabActivation = current.homeTabActivation && current.boundaryKey == key,
                        appEdit = current.appEdit.takeIf { current.boundaryKey == key },
                        appEditEpoch = current.appEditEpoch + if (current.boundaryKey == key) 0 else 1,
                        appFocusTarget =
                            current.appFocusTarget.takeIf {
                                current.boundaryKey == key || it is LauncherAppFocusTarget.HomeContent && it.appsOnly
                            },
                        settingsFocusTarget = current.settingsFocusTarget.takeIf { current.boundaryKey == key },
                        media =
                            media.state.value.takeIf { it.boundaryKey == key && !suppressMedia }
                                ?: LauncherMediaState(),
                    )
                }
                if (loggedIn != null) {
                    completeLogin(loginGeneration)
                    if (boundaryChanged) media.refresh(fresh = true)
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
            apps.state.collect { value ->
                _state.update { current ->
                    val edit = current.appEdit
                    val missing =
                        when (edit) {
                            is LauncherAppEdit.Menu ->
                                value.catalog.none { it.componentKey == edit.target.componentKey }
                            is LauncherAppEdit.Moving -> value.catalog.none { it.componentKey == edit.componentKey }
                            else -> false
                        }
                    val updated = current.copy(apps = value)
                    if (missing) updated.closeAppEdit() else updated
                }
            }
        }
        viewModelScope.launch {
            cleaner.cleanupPending.collect { value -> _state.update { it.copy(cleanupPending = value) } }
        }
    }

    fun restoreSelectedTab(tab: LauncherTab?) {
        if (!tabRestorationEligible) return
        tabRestorationEligible = false
        if (tab == null) return
        _state.update {
            it.copy(
                selectedTab = tab,
                homeInvoker =
                    when {
                        tab != LauncherTab.Jellyfin -> it.homeInvoker
                        it.signedIn -> tab.focusKey
                        else -> JELLYFIN_CONNECT
                    },
            )
        }
    }

    fun foreground(started: Boolean) {
        foregroundJob?.cancel()
        foregroundJob = null
        if (started) {
            foregroundJob =
                viewModelScope.launch {
                    while (true) {
                        delay(TimeUnit.MINUTES.toMillis(LAUNCHER_REFRESH_INTERVAL_MINUTES))
                        media.refresh()
                    }
                }
        }
    }

    fun resumed(externalReturn: Boolean = false) {
        _state.update { it.copy(focusGeneration = it.focusGeneration + 1, homeTabActivation = false) }
        media.refresh(manual = externalReturn)
        viewModelScope.launch { refreshApps() }
    }

    fun refresh() {
        media.refresh(manual = true)
        viewModelScope.launch { refreshApps() }
    }

    fun toggleHero() = preferences.toggleHero()

    fun rememberInvoker(key: String) {
        _state.update { it.copy(homeInvoker = key, homeTabActivation = false) }
    }

    fun selectTab(
        tab: LauncherTab,
        enterContent: Boolean = false,
    ) {
        tabRestorationEligible = false
        val previousTab = _state.value.selectedTab
        _state.update {
            it.copy(
                route = LauncherRoute.Home,
                selectedTab = tab,
                homeInvoker = if (enterContent) HOME_CONTENT else tab.focusKey,
                homeTabActivation = !enterContent,
                loginOrigin = null,
                authError = null,
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = if (enterContent) LauncherAppFocusTarget.HomeContent(it.focusGeneration + 1) else null,
                settingsFocusTarget = null,
                focusGeneration = it.focusGeneration + 1,
                homeResetGeneration = it.homeResetGeneration + if (it.selectedTab != tab) 1 else 0,
            )
        }
        if (previousTab != tab) media.refresh(fresh = true)
    }

    fun openSettings(account: Boolean) {
        _state.update {
            it.copy(
                route = LauncherRoute.Settings,
                homeInvoker = if (it.route == LauncherRoute.Home) (if (account) "header-account" else "settings") else it.homeInvoker,
                homeTabActivation = false,
                loginOrigin = null,
                authError = null,
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = null,
                settingsFocusTarget = if (account) LauncherSettingsFocusTarget(it.focusGeneration + 1) else null,
                focusGeneration = it.focusGeneration + 1,
            )
        }
    }

    fun settingsFocusHandled(target: LauncherSettingsFocusTarget) {
        _state.update { if (it.settingsFocusTarget == target) it.copy(settingsFocusTarget = null) else it }
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
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = null,
                settingsFocusTarget = null,
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
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget =
                    if (route == LauncherRoute.Home && (invoker ?: it.homeInvoker) in launcherHeaderKeys) {
                        LauncherAppFocusTarget.Header(invoker ?: it.homeInvoker, it.focusGeneration + 1)
                    } else {
                        null
                    },
                settingsFocusTarget = null,
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
        tabRestorationEligible = false
        _state.update {
            it.copy(
                route = LauncherRoute.Home,
                dialog = null,
                authError = null,
                homeInvoker = HOME_CONTENT,
                selectedTab = LauncherTab.Essentials,
                homeTabActivation = false,
                loginOrigin = null,
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = LauncherAppFocusTarget.HomeContent(it.focusGeneration + 1, appsOnly = true),
                settingsFocusTarget = null,
                focusGeneration = it.focusGeneration + 1,
                homeResetGeneration = it.homeResetGeneration + 1,
            )
        }
    }

    fun showDialog(dialog: LauncherDialog) {
        _state.update { it.copy(dialog = dialog, appEdit = null, appEditEpoch = it.appEditEpoch + 1) }
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
        val previous =
            _state.getAndUpdate { current ->
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
        if (previous.route == LauncherRoute.Login && previous.loginOrigin != null) tabRestorationEligible = false
    }

    fun cancelLogin() = cancelLogin(homeReset = false)

    private fun cancelLogin(homeReset: Boolean) {
        loginGeneration += 1
        val cancelledJob = loginJob
        cancelledJob?.cancel()
        if (homeReset) {
            resetHome()
        } else {
            tabRestorationEligible = false
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
                homeInvoker = if (it.selectedTab == LauncherTab.Essentials) HOME_CONTENT else JELLYFIN_CONNECT,
                homeTabActivation = false,
                loginOrigin = null,
                media = LauncherMediaState(),
                appEdit = null,
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = null,
                settingsFocusTarget = null,
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

    fun showAppMenu(
        key: String,
        anchor: Rect,
    ) {
        val current = _state.value
        if (current.dialog != null || current.apps.catalog.none { it.componentKey == key }) return
        if (current.route != LauncherRoute.Home || current.selectedTab != LauncherTab.Essentials) return
        _state.update {
            it.copy(
                appEdit = LauncherAppEdit.Menu(LauncherAppTarget(key, anchor)),
                appEditEpoch = it.appEditEpoch + 1,
                appFocusTarget = null,
            )
        }
    }

    fun dismissAppEdit() {
        _state.update { it.closeAppEdit() }
    }

    fun menuAppToOpen(): String? {
        val menu = _state.value.appEdit as? LauncherAppEdit.Menu ?: return null
        if (_state.value.apps.catalog
                .none { it.componentKey == menu.target.componentKey }
        ) {
            appUnavailable()
            return null
        }
        dismissAppEdit()
        return menu.target.componentKey
    }

    fun startMoving() {
        val current = _state.value
        val menu = current.appEdit as? LauncherAppEdit.Menu ?: return
        val key = menu.target.componentKey
        if (current.orderWriting || current.apps.catalog.size < 2 || current.apps.catalog.none { it.componentKey == key }) return
        tabRestorationEligible = false
        _state.update {
            it
                .copy(
                    route = LauncherRoute.Home,
                    selectedTab = LauncherTab.Essentials,
                    appEdit = LauncherAppEdit.Moving(key),
                    appEditEpoch =
                        it.appEditEpoch + 1,
                ).withAppFocus(key)
        }
    }

    fun finishMoving() {
        if (_state.value.appEdit is LauncherAppEdit.Moving) dismissAppEdit()
    }

    fun moveApp(delta: Int) {
        val current = _state.value
        val move = current.appEdit as? LauncherAppEdit.Moving ?: return
        val index = current.apps.catalog.indexOfFirst { it.componentKey == move.componentKey }
        if (current.orderWriting || delta !in listOf(-1, 1) || index < 0 || index + delta !in current.apps.catalog.indices) return
        writeOrder({
            apps.move(move.componentKey, delta)
            check(
                apps.state.value.catalog
                    .any { it.componentKey == move.componentKey },
            )
        }) { it.withAppFocus(move.componentKey) }
    }

    fun appFocusHandled(target: LauncherAppFocusTarget) {
        _state.update { if (it.appFocusTarget == target) it.copy(appFocusTarget = null) else it }
    }

    private fun LauncherUiState.withAppFocus(key: String): LauncherUiState {
        val target = LauncherAppFocusTarget.App(key, focusGeneration + 1)
        return copy(
            appFocusTarget = target,
            focusGeneration = target.generation,
            homeTabActivation = false,
            homeInvoker = target.key,
        )
    }

    private fun LauncherUiState.closeAppEdit(): LauncherUiState {
        val edit = appEdit ?: return this
        val key =
            when (edit) {
                is LauncherAppEdit.Menu -> edit.target.componentKey
                is LauncherAppEdit.Moving -> edit.componentKey
            }
        return copy(appEdit = null, appEditEpoch = appEditEpoch + 1).withAppFocus(key)
    }

    private suspend fun refreshApps() {
        try {
            apps.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("apps-refresh", failure)
        }
    }

    private fun writeOrder(
        block: suspend () -> Unit,
        success: (LauncherUiState) -> LauncherUiState,
    ) {
        if (_state.value.orderWriting) return
        val epoch = _state.value.appEditEpoch
        _state.update { it.copy(orderWriting = true) }
        viewModelScope.launch {
            try {
                block()
                val actual = apps.state.value
                _state.update {
                    val updated = it.copy(apps = actual)
                    if (it.appEditEpoch == epoch) success(updated) else updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                launcherFailure("app-order-move-save", failure)
                dismissAppEdit()
                showDialog(LauncherDialog.AppUnavailable)
            } finally {
                _state.update { it.copy(orderWriting = false) }
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
