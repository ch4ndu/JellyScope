// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.ui.geometry.Rect
import com.jellyscope.core.domain.model.SessionState

internal sealed interface LauncherChange {
    data class PreferencesObserved(
        val value: LauncherPreferenceState,
    ) : LauncherChange

    data class SessionObserved(
        val sessionState: SessionState,
        val loggedIn: SessionState.LoggedIn?,
        val key: String?,
    ) : LauncherChange

    data class MediaObserved(
        val value: LauncherMediaState,
    ) : LauncherChange

    data class AppsObserved(
        val value: LauncherAppsState,
    ) : LauncherChange

    data class CleanupObserved(
        val value: Boolean,
    ) : LauncherChange

    data class TabRestored(
        val tab: LauncherTab,
    ) : LauncherChange

    data class Resumed(
        val externalReturn: Boolean,
    ) : LauncherChange

    data class InvokerRemembered(
        val key: String,
    ) : LauncherChange

    data class TabSelected(
        val tab: LauncherTab,
        val enterContent: Boolean,
    ) : LauncherChange

    data class SettingsOpened(
        val account: Boolean,
    ) : LauncherChange

    data class SettingsFocusHandled(
        val target: LauncherSettingsFocusTarget,
    ) : LauncherChange

    data object HomeContentEntered : LauncherChange

    data class LoginOpened(
        val origin: LauncherLoginOrigin,
    ) : LauncherChange

    data class Navigated(
        val route: LauncherRoute,
        val invoker: String?,
    ) : LauncherChange

    data object HomeReset : LauncherChange

    data class DialogShown(
        val dialog: LauncherDialog,
    ) : LauncherChange

    data object DialogDismissed : LauncherChange

    data object LoginStarted : LauncherChange

    data class LoginFailed(
        val error: LauncherAuthError,
    ) : LauncherChange

    data class BusyChanged(
        val busy: Boolean,
    ) : LauncherChange

    data object LoginCompleted : LauncherChange

    data object LoginCancelled : LauncherChange

    data object LogoutStarted : LauncherChange

    data object LogoutFinished : LauncherChange

    data class AppMenuShown(
        val key: String,
        val anchor: Rect,
    ) : LauncherChange

    data object AppEditDismissed : LauncherChange

    data class MovingStarted(
        val key: String,
    ) : LauncherChange

    data class AppFocusHandled(
        val target: LauncherAppFocusTarget,
    ) : LauncherChange

    data class OrderWritingChanged(
        val writing: Boolean,
    ) : LauncherChange

    data class OrderWritten(
        val actual: LauncherAppsState,
        val epoch: Int,
        val success: (LauncherUiState) -> LauncherUiState,
    ) : LauncherChange
}

internal fun reduceLauncher(
    current: LauncherUiState,
    change: LauncherChange,
    latestMedia: LauncherMediaState,
    suppressMedia: Boolean,
): LauncherUiState =
    when (change) {
        is LauncherChange.PreferencesObserved ->
            with(change) {
                current.copy(preferences = value)
            }
        is LauncherChange.SessionObserved ->
            with(change) {
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
                        latestMedia.takeIf { it.boundaryKey == key && !suppressMedia }
                            ?: LauncherMediaState(),
                )
            }
        is LauncherChange.MediaObserved ->
            with(change) {
                current.copy(
                    media =
                        value.takeIf { it.boundaryKey == current.boundaryKey && !suppressMedia }
                            ?: LauncherMediaState(),
                )
            }
        is LauncherChange.AppsObserved ->
            with(change) {
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
        is LauncherChange.CleanupObserved ->
            with(change) {
                current.copy(cleanupPending = value)
            }
        is LauncherChange.TabRestored ->
            with(change) {
                current.copy(
                    selectedTab = tab,
                    homeInvoker =
                        when {
                            tab != LauncherTab.Jellyfin -> current.homeInvoker
                            current.signedIn -> tab.focusKey
                            else -> JELLYFIN_CONNECT
                        },
                )
            }
        is LauncherChange.Resumed ->
            with(change) {
                val generation = current.focusGeneration + 1
                val target =
                    if (externalReturn &&
                        current.route == LauncherRoute.Home &&
                        current.dialog == null &&
                        current.appEdit == null &&
                        current.appFocusTarget == null
                    ) {
                        when {
                            current.homeInvoker.startsWith("apps:") ->
                                LauncherAppFocusTarget.App(current.homeInvoker.removePrefix("apps:"), generation)
                            current.homeInvoker.startsWith("row:") -> LauncherAppFocusTarget.Content(current.homeInvoker, generation)
                            current.homeInvoker in launcherHeaderKeys -> LauncherAppFocusTarget.Header(current.homeInvoker, generation)
                            else -> null
                        }
                    } else {
                        current.appFocusTarget
                    }
                current.copy(focusGeneration = generation, homeTabActivation = false, appFocusTarget = target)
            }
        is LauncherChange.InvokerRemembered ->
            with(change) {
                current.copy(homeInvoker = key, homeTabActivation = false)
            }
        is LauncherChange.TabSelected ->
            with(change) {
                current.copy(
                    route = LauncherRoute.Home,
                    selectedTab = tab,
                    homeInvoker = if (enterContent) HOME_CONTENT else tab.focusKey,
                    homeTabActivation = !enterContent,
                    loginOrigin = null,
                    authError = null,
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = if (enterContent) LauncherAppFocusTarget.HomeContent(current.focusGeneration + 1) else null,
                    settingsFocusTarget = null,
                    focusGeneration = current.focusGeneration + 1,
                    homeResetGeneration = current.homeResetGeneration + if (current.selectedTab != tab) 1 else 0,
                )
            }
        is LauncherChange.SettingsOpened ->
            with(change) {
                current.copy(
                    route = LauncherRoute.Settings,
                    homeInvoker =
                        if (current.route ==
                            LauncherRoute.Home
                        ) {
                            (if (account) "header-account" else "settings")
                        } else {
                            current.homeInvoker
                        },
                    homeTabActivation = false,
                    loginOrigin = null,
                    authError = null,
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = null,
                    settingsFocusTarget = if (account) LauncherSettingsFocusTarget(current.focusGeneration + 1) else null,
                    focusGeneration = current.focusGeneration + 1,
                )
            }
        is LauncherChange.SettingsFocusHandled ->
            with(change) {
                if (current.settingsFocusTarget == target) current.copy(settingsFocusTarget = null) else current
            }
        LauncherChange.HomeContentEntered ->
            run {
                if (current.homeTabActivation) current.copy(homeTabActivation = false) else current
            }
        is LauncherChange.LoginOpened ->
            with(change) {
                current.copy(
                    route = LauncherRoute.Login,
                    loginOrigin = origin,
                    authError = null,
                    homeTabActivation = false,
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = null,
                    settingsFocusTarget = null,
                    focusGeneration = current.focusGeneration + 1,
                )
            }
        is LauncherChange.Navigated ->
            with(change) {
                current.copy(
                    route = route,
                    homeInvoker = invoker ?: current.homeInvoker,
                    authError = null,
                    loginOrigin = if (route == LauncherRoute.Login) current.loginOrigin else null,
                    homeTabActivation = false,
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget =
                        if (route == LauncherRoute.Home && (invoker ?: current.homeInvoker) in launcherHeaderKeys) {
                            LauncherAppFocusTarget.Header(invoker ?: current.homeInvoker, current.focusGeneration + 1)
                        } else {
                            null
                        },
                    settingsFocusTarget = null,
                    focusGeneration = current.focusGeneration + 1,
                )
            }
        LauncherChange.HomeReset ->
            run {
                current.copy(
                    route = LauncherRoute.Home,
                    dialog = null,
                    authError = null,
                    homeInvoker = HOME_CONTENT,
                    selectedTab = LauncherTab.Essentials,
                    homeTabActivation = false,
                    loginOrigin = null,
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = LauncherAppFocusTarget.HomeContent(current.focusGeneration + 1, appsOnly = true),
                    settingsFocusTarget = null,
                    focusGeneration = current.focusGeneration + 1,
                    homeResetGeneration = current.homeResetGeneration + 1,
                )
            }
        is LauncherChange.DialogShown ->
            with(change) {
                current.copy(dialog = dialog, appEdit = null, appEditEpoch = current.appEditEpoch + 1)
            }
        LauncherChange.DialogDismissed ->
            run {
                current.copy(dialog = null, focusGeneration = current.focusGeneration + 1)
            }
        LauncherChange.LoginStarted ->
            run {
                current.copy(busy = true, authError = null)
            }
        is LauncherChange.LoginFailed ->
            with(change) {
                current.copy(authError = error)
            }
        is LauncherChange.BusyChanged ->
            with(change) {
                current.copy(busy = busy)
            }
        LauncherChange.LoginCompleted ->
            run {
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
        LauncherChange.LoginCancelled ->
            run {
                val jellyfin = current.loginOrigin == LauncherLoginOrigin.Jellyfin
                current.copy(
                    route = if (jellyfin) LauncherRoute.Home else LauncherRoute.Settings,
                    selectedTab = if (jellyfin) LauncherTab.Jellyfin else current.selectedTab,
                    homeInvoker = if (jellyfin) JELLYFIN_CONNECT else current.homeInvoker,
                    homeTabActivation = false,
                    loginOrigin = null,
                    authError = null,
                    focusGeneration = current.focusGeneration + 1,
                )
            }
        LauncherChange.LogoutStarted ->
            run {
                current.copy(
                    route = LauncherRoute.Home,
                    homeInvoker = if (current.selectedTab == LauncherTab.Essentials) HOME_CONTENT else JELLYFIN_CONNECT,
                    homeTabActivation = false,
                    loginOrigin = null,
                    media = LauncherMediaState(),
                    appEdit = null,
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = null,
                    settingsFocusTarget = null,
                    busy = true,
                )
            }
        LauncherChange.LogoutFinished ->
            run {
                current.copy(busy = false, focusGeneration = current.focusGeneration + 1)
            }
        is LauncherChange.AppMenuShown ->
            with(change) {
                current.copy(
                    appEdit = LauncherAppEdit.Menu(LauncherAppTarget(key, anchor)),
                    appEditEpoch = current.appEditEpoch + 1,
                    appFocusTarget = null,
                )
            }
        LauncherChange.AppEditDismissed ->
            run {
                current.closeAppEdit()
            }
        is LauncherChange.MovingStarted ->
            with(change) {
                current
                    .copy(
                        route = LauncherRoute.Home,
                        selectedTab = LauncherTab.Essentials,
                        appEdit = LauncherAppEdit.Moving(key),
                        appEditEpoch =
                            current.appEditEpoch + 1,
                    ).withAppFocus(key)
            }
        is LauncherChange.AppFocusHandled ->
            with(change) {
                if (current.appFocusTarget == target) current.copy(appFocusTarget = null) else current
            }
        is LauncherChange.OrderWritingChanged ->
            with(change) {
                current.copy(orderWriting = writing)
            }
        is LauncherChange.OrderWritten ->
            with(change) {
                val updated = current.copy(apps = actual)
                if (current.appEditEpoch == epoch) success(updated) else updated
            }
    }

internal fun LauncherUiState.withAppFocus(key: String): LauncherUiState {
    val target = LauncherAppFocusTarget.App(key, focusGeneration + 1)
    return copy(
        appFocusTarget = target,
        focusGeneration = target.generation,
        homeTabActivation = false,
        homeInvoker = target.key,
    )
}

internal fun LauncherUiState.closeAppEdit(): LauncherUiState {
    val edit = appEdit ?: return this
    val key =
        when (edit) {
            is LauncherAppEdit.Menu -> edit.target.componentKey
            is LauncherAppEdit.Moving -> edit.componentKey
        }
    return copy(appEdit = null, appEditEpoch = appEditEpoch + 1).withAppFocus(key)
}
