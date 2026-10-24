// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LauncherShell(
    model: LauncherViewModel,
    images: LauncherImages,
    openApp: (String) -> Unit,
    systemSettings: () -> Unit,
    openMedia: (String, String?) -> Unit,
    isDefaultHome: Boolean,
    homeSettings: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val imageScope by images.active.collectAsStateWithLifecycle()
    val essentialsFocus =
        rememberSaveable(state.boundaryKey, state.homeResetGeneration, saver = LauncherFocus.saver) { LauncherFocus() }
    val jellyfinFocus =
        rememberSaveable(state.boundaryKey, state.homeResetGeneration, saver = LauncherFocus.saver) { LauncherFocus() }
    val homeFocus = if (state.selectedTab == LauncherTab.Essentials) essentialsFocus else jellyfinFocus
    val homePages = rememberSaveableStateHolder()
    val pageScope = "${state.boundaryKey}:${state.homeResetGeneration}:"
    // Keep ordinary recreation/route returns, but drop both pages on a boundary or Home reset.
    var savedPageScope by rememberSaveable { mutableStateOf(pageScope) }
    LaunchedEffect(pageScope, homePages) {
        if (savedPageScope != pageScope) {
            LauncherTab.entries.forEach { homePages.removeState("$savedPageScope${it.name}") }
            savedPageScope = pageScope
        }
    }
    val appsFocus = rememberSaveable(saver = LauncherFocus.saver) { LauncherFocus() }
    val settingsFocus = rememberSaveable(saver = LauncherFocus.saver) { LauncherFocus() }
    val loginFocus = rememberSaveable(saver = LauncherFocus.saver) { LauncherFocus() }
    val focus =
        when (state.route) {
            LauncherRoute.Home -> homeFocus
            LauncherRoute.Apps -> appsFocus
            LauncherRoute.Settings -> settingsFocus
            LauncherRoute.Login -> loginFocus
        }
    val appKeys = state.apps.pins.map { "apps:${it.componentKey}" } + "apps:+"
    val belowApps =
        state.route == LauncherRoute.Home &&
            state.selectedTab == LauncherTab.Essentials &&
            homeFocus.current?.startsWith("row:") == true
    val jellyfinHome = state.route == LauncherRoute.Home && state.selectedTab == LauncherTab.Jellyfin
    val homeRootKeys = LauncherTab.entries.map { it.focusKey } + listOf("catalog", "settings", "system")
    val selectedHomeRoot = state.route == LauncherRoute.Home && !jellyfinHome && !belowApps && isDefaultHome
    BackHandler(
        enabled =
            state.dialog == null &&
                (
                    state.route == LauncherRoute.Apps ||
                        state.route == LauncherRoute.Settings ||
                        belowApps ||
                        jellyfinHome ||
                        selectedHomeRoot
                ),
    ) {
        if (jellyfinHome) {
            if (homeFocus.current != null && homeFocus.current !in homeRootKeys) {
                homeFocus.request(LauncherTab.Jellyfin.focusKey)
            } else {
                model.selectTab(LauncherTab.Essentials)
            }
        } else if (belowApps) {
            homeFocus.request(homeFocus.rowTarget("apps", appKeys) ?: "apps:+")
        } else if (state.route != LauncherRoute.Home) {
            model.backToHome()
        }
    }

    fun navigate(
        route: LauncherRoute,
        invoker: String,
    ) {
        if (state.route == LauncherRoute.Login) model.cancelLogin()
        model.navigate(route, invoker)
    }

    fun settings() {
        if (state.route == LauncherRoute.Home) model.rememberInvoker("system")
        systemSettings()
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        MaterialTheme(colorScheme = launcherColors) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    LauncherClock()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState(),
                        ).padding(horizontal = 32.dp, vertical = 12.dp)
                        .focusGroup()
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                val target =
                                    when (state.route) {
                                        LauncherRoute.Home -> state.selectedTab.focusKey
                                        LauncherRoute.Apps ->
                                            state.apps.catalog
                                                .firstOrNull()
                                                ?.let { "${it.componentKey}:open" } ?: "system"
                                        LauncherRoute.Settings -> if (state.busy || state.restoring) "refresh" else "account"
                                        LauncherRoute.Login -> "server"
                                    }
                                focus.request(target)
                                true
                            } else if (it.type == KeyEventType.KeyDown &&
                                it.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)
                            ) {
                                val controls = listOf("catalog", "settings", "system")
                                val index = controls.indexOf(focus.current).coerceAtLeast(0)
                                val destination =
                                    if (it.key == Key.DirectionLeft) {
                                        index - 1
                                    } else if (it.key == Key.DirectionRight) {
                                        index + 1
                                    } else {
                                        index
                                    }
                                focus.request(controls[destination.coerceIn(controls.indices)])
                                true
                            } else {
                                false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = { navigate(LauncherRoute.Apps, "catalog") },
                        modifier = Modifier.launcherFocus(focus, "catalog"),
                    ) { Text(stringResource(R.string.all_apps)) }
                    Button(
                        onClick = { navigate(LauncherRoute.Settings, "settings") },
                        modifier = Modifier.launcherFocus(focus, "settings"),
                    ) { Text(stringResource(R.string.launcher_settings)) }
                    Button(
                        onClick = ::settings,
                        modifier = Modifier.launcherFocus(focus, "system"),
                    ) { Text(stringResource(R.string.system_settings)) }
                }
                when (state.route) {
                    LauncherRoute.Home ->
                        LauncherHomeScreen(
                            state,
                            homeFocus,
                            imageScope?.takeIf { it.boundary.key == state.boundaryKey },
                            onApps = { navigate(LauncherRoute.Apps, "apps:+") },
                            onApp = {
                                model.rememberInvoker("apps:$it")
                                openApp(it)
                            },
                            onMedia = { target, itemId ->
                                model.rememberInvoker(target)
                                openMedia(itemId, state.boundaryKey)
                            },
                            pages = homePages,
                            onTab = model::selectTab,
                            onContentEntry = model::homeContentEntered,
                            onConnect = { model.beginLogin(LauncherLoginOrigin.Jellyfin) },
                            onSettings = {
                                navigate(
                                    LauncherRoute.Settings,
                                    if (state.cleanupPending) JELLYFIN_RECOVERY else JELLYFIN_SETTINGS,
                                )
                            },
                            onRefresh = model::refresh,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    LauncherRoute.Apps -> LauncherAppsScreen(state, appsFocus, model, openApp, Modifier.weight(1f).fillMaxWidth())
                    LauncherRoute.Settings ->
                        LauncherSettingsScreen(
                            state,
                            settingsFocus,
                            model,
                            ::settings,
                            isDefaultHome,
                            homeSettings,
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    LauncherRoute.Login -> LauncherLoginScreen(state, loginFocus, model, Modifier.weight(1f).fillMaxWidth())
                }
            }
            state.dialog?.let { LauncherDialogContent(it, model::dismissDialog, model::logout) }
        }
    }
}

@Composable
private fun LauncherClock() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by produceState(initialValue = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), key1 = lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                delay(1000)
            }
        }
    }
    Text(clock, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun LauncherDialogContent(
    dialog: LauncherDialog,
    dismiss: () -> Unit,
    logout: () -> Unit,
) {
    val focus = remember(dialog) { LauncherFocus() }
    val title =
        when (dialog) {
            LauncherDialog.SystemSettings -> R.string.settings_unavailable_title
            LauncherDialog.HomeSettingsUnavailable -> R.string.home_settings_unavailable_title
            LauncherDialog.AppUnavailable -> R.string.app_unavailable_title
            LauncherDialog.PlaybackUnavailable -> R.string.playback_unavailable_title
            LauncherDialog.PlaybackFailed -> R.string.playback_failed_title
            LauncherDialog.PlaybackStale -> R.string.playback_stale_title
            LauncherDialog.Logout -> R.string.sign_out
            LauncherDialog.CleanupFailed -> R.string.cleanup_failed_title
        }
    val message =
        when (dialog) {
            LauncherDialog.SystemSettings -> R.string.settings_unavailable_message
            LauncherDialog.HomeSettingsUnavailable -> R.string.home_settings_unavailable_message
            LauncherDialog.AppUnavailable -> R.string.app_unavailable_message
            LauncherDialog.PlaybackUnavailable -> R.string.playback_unavailable_message
            LauncherDialog.PlaybackFailed -> R.string.playback_failed_message
            LauncherDialog.PlaybackStale -> R.string.playback_stale_message
            LauncherDialog.Logout -> R.string.sign_out_message
            LauncherDialog.CleanupFailed -> R.string.cleanup_failed_message
        }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        LaunchedEffect(dialog) { focus.request("dismiss") }
        Column(
            Modifier
                .widthIn(
                    max = 520.dp,
                ).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(message), color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = dismiss,
                    modifier =
                        Modifier.launcherFocus(focus, "dismiss").focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            if (dialog != LauncherDialog.Logout) {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                        },
                ) { Text(stringResource(if (dialog == LauncherDialog.Logout) R.string.cancel else R.string.dismiss)) }
                if (dialog ==
                    LauncherDialog.Logout
                ) {
                    Button(
                        onClick = logout,
                        modifier =
                            Modifier.launcherFocus(focus, "confirm").focusProperties {
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                    ) { Text(stringResource(R.string.sign_out)) }
                }
            }
        }
    }
}

private val launcherColors =
    darkColorScheme(
        primary = Color(0xFF22B8D4),
        onPrimary = Color(0xFF081420),
        background = Color(0xFF081420),
        onBackground = Color.White,
        surface = Color(0xFF12293D),
        onSurface = Color.White,
    )
