// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.ui.geometry.Rect

internal sealed interface LauncherIntent {
    data class RestoreSelectedTab(
        val tab: LauncherTab?,
    ) : LauncherIntent

    data class Foreground(
        val started: Boolean,
    ) : LauncherIntent

    data class Resumed(
        val externalReturn: Boolean = false,
    ) : LauncherIntent

    data object Refresh : LauncherIntent

    data object ToggleHero : LauncherIntent

    data class RememberInvoker(
        val key: String,
    ) : LauncherIntent

    data class SelectTab(
        val tab: LauncherTab,
        val enterContent: Boolean = false,
    ) : LauncherIntent

    data class OpenSettings(
        val account: Boolean,
    ) : LauncherIntent

    data class SettingsFocusHandled(
        val target: LauncherSettingsFocusTarget,
    ) : LauncherIntent

    data object HomeContentEntered : LauncherIntent

    data class BeginLogin(
        val origin: LauncherLoginOrigin,
    ) : LauncherIntent

    data object AppUnavailable : LauncherIntent

    data class Navigate(
        val route: LauncherRoute,
        val invoker: String? = null,
    ) : LauncherIntent

    data object BackToHome : LauncherIntent

    data object HomeInvoked : LauncherIntent

    data class ShowDialog(
        val dialog: LauncherDialog,
    ) : LauncherIntent

    data object DismissDialog : LauncherIntent

    class Login(
        val server: String,
        val user: String,
        val password: String,
    ) : LauncherIntent

    data object CancelLogin : LauncherIntent

    data object Logout : LauncherIntent

    data class ShowAppMenu(
        val key: String,
        val anchor: Rect,
    ) : LauncherIntent

    data object DismissAppEdit : LauncherIntent

    data object MenuAppToOpen : LauncherIntent

    data object StartMoving : LauncherIntent

    data object FinishMoving : LauncherIntent

    data class MoveApp(
        val delta: Int,
    ) : LauncherIntent

    data class AppFocusHandled(
        val target: LauncherAppFocusTarget,
    ) : LauncherIntent
}
