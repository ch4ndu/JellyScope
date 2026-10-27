// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.util.Log
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

    // Return the previous committed state for one-shot login-origin consumption.
    private fun reduce(change: LauncherChange): LauncherUiState =
        _state.getAndUpdate { current -> reduceLauncher(current, change, media.state.value, suppressMedia) }

    fun accept(intent: LauncherIntent) {
        when (intent) {
            is LauncherIntent.RestoreSelectedTab -> restoreSelectedTab(intent.tab)
            is LauncherIntent.Foreground -> foreground(intent.started)
            is LauncherIntent.Resumed -> resumed(intent.externalReturn)
            LauncherIntent.Refresh -> refresh()
            LauncherIntent.ToggleHero -> toggleHero()
            is LauncherIntent.RememberInvoker -> rememberInvoker(intent.key)
            is LauncherIntent.SelectTab -> selectTab(intent.tab, intent.enterContent)
            is LauncherIntent.OpenSettings -> openSettings(intent.account)
            is LauncherIntent.SettingsFocusHandled -> settingsFocusHandled(intent.target)
            LauncherIntent.HomeContentEntered -> homeContentEntered()
            is LauncherIntent.BeginLogin -> beginLogin(intent.origin)
            LauncherIntent.AppUnavailable -> appUnavailable()
            is LauncherIntent.Navigate -> navigate(intent.route, intent.invoker)
            LauncherIntent.BackToHome -> backToHome()
            LauncherIntent.HomeInvoked -> homeInvoked()
            is LauncherIntent.ShowDialog -> showDialog(intent.dialog)
            LauncherIntent.DismissDialog -> dismissDialog()
            is LauncherIntent.Login -> login(intent.server, intent.user, intent.password)
            LauncherIntent.CancelLogin -> cancelLogin()
            LauncherIntent.Logout -> logout()
            is LauncherIntent.ShowAppMenu -> showAppMenu(intent.key, intent.anchor)
            LauncherIntent.DismissAppEdit -> dismissAppEdit()
            LauncherIntent.MenuAppToOpen -> menuAppToOpen()
            LauncherIntent.StartMoving -> startMoving()
            LauncherIntent.FinishMoving -> finishMoving()
            is LauncherIntent.MoveApp -> moveApp(intent.delta)
            is LauncherIntent.AppFocusHandled -> appFocusHandled(intent.target)
        }
    }

    fun accept(intent: LauncherIntent.MenuAppToOpen): String? = menuAppToOpen()

    init {
        viewModelScope.launch {
            preferences.state.collect { value -> reduce(LauncherChange.PreferencesObserved(value)) }
        }
        viewModelScope.launch {
            sessions.sessionState.collect { sessionState ->
                val loggedIn = sessionState as? SessionState.LoggedIn
                val key = loggedIn?.let { LauncherBoundary(it.session.accountIdentity(), it.boundaryEpoch).key }
                val boundaryChanged = _state.value.boundaryKey != key
                if (boundaryChanged) suppressMedia = false
                reduce(LauncherChange.SessionObserved(sessionState, loggedIn, key))
                if (loggedIn != null) {
                    completeLogin(loginGeneration)
                    if (boundaryChanged) media.refresh(fresh = true)
                }
            }
        }
        viewModelScope.launch {
            media.state.collect { value ->
                reduce(LauncherChange.MediaObserved(value))
            }
        }
        viewModelScope.launch {
            apps.state.collect { value ->
                reduce(LauncherChange.AppsObserved(value))
            }
        }
        viewModelScope.launch {
            cleaner.cleanupPending.collect { value -> reduce(LauncherChange.CleanupObserved(value)) }
        }
    }

    private fun restoreSelectedTab(tab: LauncherTab?) {
        if (!tabRestorationEligible) return
        tabRestorationEligible = false
        if (tab == null) return
        reduce(LauncherChange.TabRestored(tab))
    }

    private fun foreground(started: Boolean) {
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

    private fun resumed(externalReturn: Boolean = false) {
        reduce(LauncherChange.Resumed(externalReturn))
        val returnTarget =
            when (state.value.appFocusTarget) {
                is LauncherAppFocusTarget.Content -> "media"
                is LauncherAppFocusTarget.App -> "app"
                is LauncherAppFocusTarget.Header -> "header"
                is LauncherAppFocusTarget.HomeContent -> "home"
                null -> "none"
            }
        Log.i("Launcher", "stage=focus-return event=resumed external=$externalReturn target=$returnTarget")
        media.refresh(manual = externalReturn)
        viewModelScope.launch { refreshApps() }
    }

    private fun refresh() {
        media.refresh(manual = true)
        viewModelScope.launch { refreshApps() }
    }

    private fun toggleHero() = preferences.toggleHero()

    private fun rememberInvoker(key: String) {
        reduce(LauncherChange.InvokerRemembered(key))
        val kind =
            if (key.startsWith("row:")) {
                "media"
            } else if (key.startsWith("apps:")) {
                "app"
            } else {
                "header"
            }
        Log.i("Launcher", "stage=focus-return event=invoker-recorded kind=$kind")
    }

    private fun selectTab(
        tab: LauncherTab,
        enterContent: Boolean = false,
    ) {
        tabRestorationEligible = false
        val previousTab = _state.value.selectedTab
        Log.i("Launcher", "stage=tab-selection event=requested changed=${previousTab != tab} enterContent=$enterContent")
        reduce(LauncherChange.TabSelected(tab, enterContent))
        if (previousTab != tab) media.refresh(fresh = true)
    }

    private fun openSettings(account: Boolean) {
        reduce(LauncherChange.SettingsOpened(account))
    }

    private fun settingsFocusHandled(target: LauncherSettingsFocusTarget) {
        reduce(LauncherChange.SettingsFocusHandled(target))
    }

    private fun homeContentEntered() {
        reduce(LauncherChange.HomeContentEntered)
    }

    private fun beginLogin(origin: LauncherLoginOrigin) {
        val current = _state.value
        if (current.busy ||
            current.restoring ||
            current.signedIn ||
            current.cleanupPending ||
            sessions.sessionState.value !is SessionState.LoggedOut
        ) {
            return
        }
        reduce(LauncherChange.LoginOpened(origin))
    }

    private fun appUnavailable() {
        showDialog(LauncherDialog.AppUnavailable)
        viewModelScope.launch { refreshApps() }
    }

    private fun navigate(
        route: LauncherRoute,
        invoker: String? = null,
    ) {
        reduce(LauncherChange.Navigated(route, invoker))
    }

    private fun backToHome() = navigate(LauncherRoute.Home)

    private fun homeInvoked() {
        if (_state.value.route == LauncherRoute.Login) {
            cancelLogin(homeReset = true)
        } else {
            resetHome()
        }
    }

    private fun resetHome() {
        tabRestorationEligible = false
        reduce(LauncherChange.HomeReset)
    }

    private fun showDialog(dialog: LauncherDialog) {
        reduce(LauncherChange.DialogShown(dialog))
    }

    private fun dismissDialog() {
        reduce(LauncherChange.DialogDismissed)
    }

    private fun login(
        server: String,
        user: String,
        password: String,
    ) {
        if (_state.value.busy || _state.value.restoring || _state.value.signedIn || _state.value.cleanupPending) return
        val generation = ++loginGeneration
        reduce(LauncherChange.LoginStarted)
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
                    if (generation == loginGeneration) reduce(LauncherChange.LoginFailed(authError(failure)))
                } finally {
                    if (generation == loginGeneration) reduce(LauncherChange.BusyChanged(false))
                }
            }
    }

    // Session publication may precede the action return. Consume the origin once
    // so both success paths agree without navigating again after cancellation.
    private fun completeLogin(generation: Long) {
        if (generation != loginGeneration || sessions.sessionState.value !is SessionState.LoggedIn) return
        val previous =
            reduce(LauncherChange.LoginCompleted)
        if (previous.route == LauncherRoute.Login && previous.loginOrigin != null) tabRestorationEligible = false
    }

    private fun cancelLogin() = cancelLogin(homeReset = false)

    private fun cancelLogin(homeReset: Boolean) {
        loginGeneration += 1
        val cancelledJob = loginJob
        cancelledJob?.cancel()
        if (homeReset) {
            resetHome()
        } else {
            tabRestorationEligible = false
            reduce(LauncherChange.LoginCancelled)
        }
        // Exit UI immediately. Ordering invalidation with the old job prevents
        // it from cancelling a newer login; a commit that already won is honored.
        reduce(LauncherChange.BusyChanged(true))
        viewModelScope.launch {
            withContext(NonCancellable) {
                authMutation.withLock {
                    coordinator.beginAuthenticationAttempt()
                    cancelledJob?.cancelAndJoin()
                    reduce(LauncherChange.BusyChanged(false))
                }
            }
        }
    }

    private fun logout() {
        dismissDialog()
        viewModelScope.launch { authMutation.withLock { logoutLocked() } }
    }

    private suspend fun logoutLocked() {
        suppressMedia = true
        reduce(LauncherChange.LogoutStarted)
        try {
            logoutAction().getOrThrow()
            reduce(LauncherChange.CleanupObserved(false))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("logout", failure)
            reduce(LauncherChange.CleanupObserved(true))
            showDialog(LauncherDialog.CleanupFailed)
        } finally {
            reduce(LauncherChange.LogoutFinished)
        }
    }

    private fun showAppMenu(
        key: String,
        anchor: Rect,
    ) {
        val current = _state.value
        if (current.dialog != null || current.apps.catalog.none { it.componentKey == key }) return
        if (current.route != LauncherRoute.Home || current.selectedTab != LauncherTab.Essentials) return
        reduce(LauncherChange.AppMenuShown(key, anchor))
    }

    private fun dismissAppEdit() {
        reduce(LauncherChange.AppEditDismissed)
    }

    private fun menuAppToOpen(): String? {
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

    private fun startMoving() {
        val current = _state.value
        val menu = current.appEdit as? LauncherAppEdit.Menu ?: return
        val key = menu.target.componentKey
        if (current.orderWriting || current.apps.catalog.size < 2 || current.apps.catalog.none { it.componentKey == key }) return
        tabRestorationEligible = false
        reduce(LauncherChange.MovingStarted(key))
    }

    private fun finishMoving() {
        if (_state.value.appEdit is LauncherAppEdit.Moving) dismissAppEdit()
    }

    private fun moveApp(delta: Int) {
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

    private fun appFocusHandled(target: LauncherAppFocusTarget) {
        reduce(LauncherChange.AppFocusHandled(target))
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
        reduce(LauncherChange.OrderWritingChanged(true))
        viewModelScope.launch {
            try {
                block()
                val actual = apps.state.value
                reduce(LauncherChange.OrderWritten(actual, epoch, success))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                launcherFailure("app-order-move-save", failure)
                dismissAppEdit()
                showDialog(LauncherDialog.AppUnavailable)
            } finally {
                reduce(LauncherChange.OrderWritingChanged(false))
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
