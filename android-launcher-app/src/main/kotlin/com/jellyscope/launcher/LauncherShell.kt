// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    val headerFocus = rememberSaveable(saver = LauncherFocus.saver) { LauncherFocus() }
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
    val suppressed = state.backgroundSuppressed
    headerFocus.enabled = !suppressed
    listOf(essentialsFocus, jellyfinFocus).forEach {
        it.enabled = !suppressed
        it.bindHeader(headerFocus, launcherHeaderKeys)
    }
    val contentKeys = state.homeContentKeys(state.selectedTab)
    val appKeys = state.apps.catalog.map { "apps:${it.componentKey}" }
    val belowApps =
        state.route == LauncherRoute.Home &&
            state.selectedTab == LauncherTab.Essentials &&
            homeFocus.current?.startsWith("row:") == true
    val jellyfinHome = state.route == LauncherRoute.Home && state.selectedTab == LauncherTab.Jellyfin
    val selectedHomeRoot = state.route == LauncherRoute.Home && !jellyfinHome && !belowApps && isDefaultHome
    val moving = state.appEdit is LauncherAppEdit.Moving
    BackHandler(
        enabled =
            !suppressed &&
                (
                    moving ||
                        belowApps ||
                        jellyfinHome ||
                        selectedHomeRoot
                ),
    ) {
        if (moving) {
            model.finishMoving()
        } else if (jellyfinHome) {
            if (!homeFocus.parentFocused) {
                headerFocus.request(LauncherTab.Jellyfin.focusKey)
            } else {
                model.selectTab(LauncherTab.Essentials)
            }
        } else if (belowApps) {
            val app = homeFocus.rowTarget("apps", appKeys)
            if (app != null) homeFocus.request(app) else headerFocus.request(LauncherTab.Essentials.focusKey)
        }
    }

    LauncherTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(LauncherStyle.background)
                .background(LauncherStyle.backgroundDepth)
                .then(if (suppressed) Modifier.clearAndSetSemantics {} else Modifier)
                .onPreviewKeyEvent { suppressed || it.isRepeatedLauncherActivation },
        ) {
            LauncherHeader(state, headerFocus, homeFocus, model, model::selectTab, { model.openSettings(account = true) }, systemSettings)
            Box(Modifier.weight(1f).fillMaxWidth().launcherFocusScope(homeFocus) { contentKeys }) {
                LauncherHomeScreen(
                    state,
                    homeFocus,
                    model,
                    imageScope?.takeIf { it.boundary.key == state.boundaryKey },
                    onApp = {
                        if (!suppressed) {
                            model.rememberInvoker("apps:$it")
                            openApp(it)
                        }
                    },
                    onMedia = { target, itemId ->
                        if (!suppressed) {
                            model.rememberInvoker(target)
                            openMedia(itemId, state.boundaryKey)
                        }
                    },
                    pages = homePages,
                    onContentEntry = { if (!suppressed) model.homeContentEntered() },
                    onConnect = { if (!suppressed) model.beginLogin(LauncherLoginOrigin.Jellyfin) },
                    onSettings = {
                        if (!suppressed) {
                            model.navigate(
                                LauncherRoute.Settings,
                                if (state.cleanupPending) JELLYFIN_RECOVERY else JELLYFIN_SETTINGS,
                            )
                        }
                    },
                    onRefresh = { if (!suppressed) model.refresh() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (state.route != LauncherRoute.Home) {
            val modalFocus = remember(state.route) { LauncherFocus() }
            modalFocus.enabled = state.dialog == null
            val dismiss: () -> Unit = {
                if (state.route == LauncherRoute.Login) model.cancelLogin() else model.backToHome()
            }
            Dialog(
                onDismissRequest = dismiss,
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                        dismissOnClickOutside = false,
                        dismissOnBackPress = false,
                    ),
            ) {
                val settingsPanel = state.route == LauncherRoute.Settings
                val modalModifier =
                    if (settingsPanel) {
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                    } else {
                        Modifier
                            .widthIn(max = 640.dp)
                            .heightIn(max = 480.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    }
                Box(
                    modalModifier
                        .then(if (state.dialog != null) Modifier.clearAndSetSemantics {} else Modifier)
                        .onPreviewKeyEvent { state.dialog != null || it.isRepeatedLauncherActivation },
                    contentAlignment = if (settingsPanel) Alignment.CenterEnd else Alignment.TopStart,
                ) {
                    when (state.route) {
                        LauncherRoute.Settings ->
                            LauncherSettingsScreen(
                                state,
                                modalFocus,
                                model,
                                isDefaultHome,
                                homeSettings,
                                Modifier
                                    .widthIn(max = 360.dp)
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
                            )
                        LauncherRoute.Login -> LauncherLoginScreen(state, modalFocus, model, Modifier.fillMaxWidth())
                        LauncherRoute.Home -> Unit
                    }
                }
            }
        }
        LauncherAppActions(state, model) { model.menuAppToOpen()?.let(openApp) }
        state.dialog?.let { LauncherDialogContent(it, model::dismissDialog, model::logout) }
    }
}

@Composable
private fun LauncherHeader(
    state: LauncherUiState,
    focus: LauncherFocus,
    content: LauncherFocus,
    model: LauncherViewModel,
    selectTab: (LauncherTab, Boolean) -> Unit,
    account: () -> Unit,
    systemSettings: () -> Unit,
) {
    val controls = LauncherTab.entries.map { it.focusKey } + listOf("header-account", "settings")
    Row(
        Modifier
            .fillMaxWidth()
            .height(
                LauncherStyle.headerHeight,
            ).padding(horizontal = LauncherStyle.inset)
            .launcherFocusScope(focus) { controls }
            .onPreviewKeyEvent {
                if (state.backgroundSuppressed) {
                    true
                } else if (it.type != KeyEventType.KeyDown ||
                    it.key !in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
                ) {
                    false
                } else if (state.appEdit is LauncherAppEdit.Moving) {
                    true
                } else if (it.key == Key.DirectionDown) {
                    val tab = LauncherTab.entries.firstOrNull { tab -> tab.focusKey == focus.current }
                    if (tab != null) {
                        val available = state.homeContentKeys(tab).isNotEmpty()
                        if (state.route == LauncherRoute.Home && tab == state.selectedTab && available && content.enter()) {
                            model.homeContentEntered()
                        } else if (state.route != LauncherRoute.Home || tab != state.selectedTab || available) {
                            // One update owns both tab selection and deferred entry.
                            selectTab(tab, available)
                        }
                    } else {
                        content.enter()
                    }
                    true
                } else {
                    val index = controls.indexOf(focus.current).coerceAtLeast(0)
                    val destination =
                        when (it.key) {
                            Key.DirectionLeft -> index - 1
                            Key.DirectionRight -> index + 1
                            else -> index
                        }
                    focus.request(controls[destination.coerceIn(controls.indices)])
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(LauncherStyle.headerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LauncherTab.entries.forEach { tab ->
            LauncherHeaderControl(
                stringResource(if (tab == LauncherTab.Essentials) R.string.home else R.string.jellyfin),
                focus,
                tab.focusKey,
                state,
                model,
                tab = tab,
                active = state.selectedTab == tab,
            ) { selectTab(tab, false) }
        }
        Spacer(Modifier.weight(1f))
        LauncherHeaderControl(
            stringResource(R.string.server_account),
            focus,
            "header-account",
            state,
            model,
            icon = R.drawable.launcher_jellyfin,
            click = account,
        )
        LauncherHeaderControl(
            stringResource(R.string.system_settings),
            focus,
            "settings",
            state,
            model,
            icon = R.drawable.launcher_settings,
            click = systemSettings,
        )
        LauncherClock()
    }
}

@Composable
private fun LauncherHeaderControl(
    label: String,
    focus: LauncherFocus,
    target: String,
    state: LauncherUiState,
    model: LauncherViewModel,
    active: Boolean = false,
    tab: LauncherTab? = null,
    icon: Int? = null,
    click: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var entryEpoch by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) }
    LaunchedEffect(
        focused,
        state.focusGeneration,
        state.appEditEpoch,
        state.route,
        state.selectedTab,
        state.dialog,
        state.boundaryKey,
        state.restoring,
        state.backgroundSuppressed,
    ) {
        val epoch = entryEpoch
        if (focused &&
            tab != null &&
            !active &&
            !state.backgroundSuppressed &&
            state.dialog == null &&
            state.appEdit == null &&
            state.appFocusTarget == null &&
            epoch == Triple(state.focusGeneration, state.appEditEpoch, state.restoring)
        ) {
            delay(LauncherStyle.TAB_DWELL_MILLIS)
            val latest = model.state.value
            if (focus.hasFocus &&
                focus.current == target &&
                !latest.backgroundSuppressed &&
                latest.dialog == null &&
                latest.appEdit == null &&
                latest.appFocusTarget == null &&
                latest.selectedTab != tab &&
                epoch == Triple(latest.focusGeneration, latest.appEditEpoch, latest.restoring)
            ) {
                model.selectTab(tab)
            }
        }
    }
    Box(
        (if (icon == null) Modifier.height(LauncherStyle.headerControl) else Modifier.size(LauncherStyle.headerControl))
            .launcherFocus(focus, target)
            .onFocusChanged {
                if (it.isFocused && !focused) {
                    entryEpoch =
                        if (state.appFocusTarget == null) Triple(state.focusGeneration, state.appEditEpoch, state.restoring) else null
                }
                if (!it.isFocused) entryEpoch = null
                focused = it.isFocused
            }.drawBehind {
                if (active || focused) {
                    val thickness = (if (focused) LauncherStyle.focusedUnderline else LauncherStyle.tabUnderline).toPx()
                    drawLine(
                        if (focused) LauncherStyle.text else LauncherStyle.muted,
                        Offset(0f, size.height - thickness / 2),
                        Offset(size.width, size.height - thickness / 2),
                        thickness,
                    )
                }
            }.semantics {
                contentDescription = label
                if (tab != null) selected = active
            }.combinedClickable(
                enabled = !state.backgroundSuppressed,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = if (tab != null) Role.Tab else Role.Button,
                onClick = {
                    if (!state.backgroundSuppressed) {
                        focus.request(target)
                        click()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            Text(
                label,
                color = if (focused || active) LauncherStyle.text else LauncherStyle.muted,
                style =
                    LauncherStyle.header.copy(
                        fontWeight =
                            if (focused) {
                                FontWeight.Bold
                            } else if (active) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Normal
                            },
                    ),
            )
        } else {
            Image(
                painterResource(icon),
                null,
                Modifier.size(LauncherStyle.headerIcon),
                colorFilter =
                    if (icon ==
                        R.drawable.launcher_jellyfin
                    ) {
                        null
                    } else {
                        ColorFilter.tint(if (focused) LauncherStyle.text else LauncherStyle.headerIconTint)
                    },
            )
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
    Text(clock, style = LauncherStyle.header, color = LauncherStyle.text)
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
            LauncherDialog.DetailsUnsupported -> R.string.details_unsupported_title
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
            LauncherDialog.DetailsUnsupported -> R.string.details_unsupported_message
            LauncherDialog.PlaybackFailed -> R.string.playback_failed_message
            LauncherDialog.PlaybackStale -> R.string.playback_stale_message
            LauncherDialog.Logout -> R.string.sign_out_message
            LauncherDialog.CleanupFailed -> R.string.cleanup_failed_message
        }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(dialog, windowFocused) { if (windowFocused) focus.request("dismiss") }
        Column(
            Modifier
                .widthIn(
                    max = 520.dp,
                ).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .focusGroup()
                .onPreviewKeyEvent { it.isRepeatedLauncherActivation },
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
