// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date

private enum class LauncherAccountPage { Main, Connection, Preferences }

@Composable
internal fun LauncherSettingsScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    model: LauncherViewModel,
    isDefaultHome: Boolean,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(LauncherAccountPage.Main) }
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    val bodyScroll = remember(page) { ScrollState(0) }
    val enabled = state.dialog == null
    val accountEnabled = enabled && !state.restoring && !state.busy
    val refreshEnabled = accountEnabled && state.signedIn
    val controls =
        if (!enabled) {
            emptyList()
        } else {
            when (page) {
                LauncherAccountPage.Main ->
                    (if (refreshEnabled) listOf("refresh") else emptyList()) +
                        listOf("connection", "preferences") +
                        (if (accountEnabled) listOf("account") else emptyList()) + "close"
                LauncherAccountPage.Connection ->
                    listOf("back-account", "connection-server", "connection-cache", "connection-playback", "close")
                LauncherAccountPage.Preferences -> listOf("back-account", "hero-toggle", "settings-home", "home-support", "close")
            }
        }
    val initialTarget =
        when {
            accountEnabled && state.cleanupPending -> "account"
            refreshEnabled -> "refresh"
            accountEnabled && !state.signedIn -> "account"
            else -> "close"
        }
    val back = {
        if (page == LauncherAccountPage.Main) {
            model.backToHome()
        } else {
            pendingTarget = if (page == LauncherAccountPage.Connection) "connection" else "preferences"
            page = LauncherAccountPage.Main
        }
    }
    val request = state.settingsFocusTarget
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(page, state.focusGeneration, controls, state.dialog, windowFocused) {
        if (enabled && windowFocused) {
            val target =
                pendingTarget?.takeIf { it in controls }
                    ?: if (request != null && page == LauncherAccountPage.Main) {
                        initialTarget
                    } else {
                        focus.current?.takeIf { it in controls }
                            ?: if (page == LauncherAccountPage.Main) initialTarget else "back-account"
                    }
            focus.request(target)
            pendingTarget = null
            request?.let(model::settingsFocusHandled)
        }
    }
    BackHandler(enabled = enabled, onBack = back)
    Column(
        modifier
            .padding(24.dp)
            .focusGroup()
            .onPreviewKeyEvent {
                val index = controls.indexOf(focus.current)
                if (it.isRepeatedLauncherActivation) {
                    true
                } else if (it.type != KeyEventType.KeyDown || index < 0) {
                    false
                } else {
                    when (it.key) {
                        Key.DirectionUp -> {
                            focus.request(controls.getOrNull(index - 1) ?: controls.first())
                            true
                        }
                        Key.DirectionDown -> {
                            controls.getOrNull(index + 1)?.let(focus::request)
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.launcher_jellyfin),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                colorFilter = ColorFilter.tint(LauncherStyle.headerIconTint),
            )
            Text(stringResource(R.string.server_account), style = LauncherStyle.heading)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page != LauncherAccountPage.Main) {
                LauncherAccountAction(R.string.back_to_account, focus, "back-account", enabled, back)
                Text(
                    stringResource(
                        if (page == LauncherAccountPage.Connection) R.string.connection_details else R.string.launcher_preferences,
                    ),
                    style = LauncherStyle.section,
                )
            }
            when (page) {
                LauncherAccountPage.Main -> {
                    Text(
                        when {
                            state.restoring -> stringResource(R.string.restoring)
                            state.signedIn -> state.accountLabel
                            else -> stringResource(R.string.signed_out)
                        },
                        style = LauncherStyle.header,
                    )
                    if (state.signedIn && !state.restoring) {
                        Text(stringResource(R.string.saved_session), color = LauncherStyle.muted)
                    }
                    if (state.busy && !state.restoring) Text(stringResource(R.string.connection_busy), color = LauncherStyle.muted)
                    if (state.cleanupPending) {
                        Text(stringResource(R.string.account_cleanup_pending), color = MaterialTheme.colorScheme.error)
                    }
                    LauncherAccountAction(R.string.refresh_library, focus, "refresh", refreshEnabled, model::refresh)
                    if (state.media.refreshing) Text(stringResource(R.string.refreshing), color = LauncherStyle.muted)
                    LauncherAccountAction(R.string.connection_details, focus, "connection", enabled) {
                        pendingTarget = "back-account"
                        page = LauncherAccountPage.Connection
                    }
                    LauncherAccountAction(R.string.launcher_preferences, focus, "preferences", enabled) {
                        pendingTarget = "back-account"
                        page = LauncherAccountPage.Preferences
                    }
                    LauncherAccountAction(
                        if (state.cleanupPending) {
                            R.string.retry_sign_out
                        } else if (state.signedIn) {
                            R.string.sign_out
                        } else {
                            R.string.sign_in
                        },
                        focus,
                        "account",
                        accountEnabled,
                    ) {
                        if (state.cleanupPending) {
                            model.logout()
                        } else if (state.signedIn) {
                            model.showDialog(LauncherDialog.Logout)
                        } else {
                            model.beginLogin(LauncherLoginOrigin.Settings)
                        }
                    }
                }
                LauncherAccountPage.Connection -> {
                    LauncherAccountInfo(focus, "connection-server", enabled) {
                        Text(stringResource(R.string.server_url), style = LauncherStyle.header)
                        Text(if (state.serverUrl.isEmpty()) stringResource(R.string.signed_out) else state.serverUrl)
                        if (state.serverUrl.isNotEmpty()) {
                            Text(
                                stringResource(
                                    if (state.serverUrl.startsWith("http://", true)) R.string.http_notice else R.string.https_notice,
                                ),
                            )
                        }
                    }
                    LauncherAccountInfo(focus, "connection-cache", enabled) {
                        Text(stringResource(state.media.configurationStatus.resource()))
                        Text(stringResource(R.string.cache_status, state.media.rows.sumOf { it.cards.size }))
                        if (state.media.refreshing) Text(stringResource(R.string.refreshing), color = LauncherStyle.muted)
                        Text(
                            state.media.lastRefreshMs?.let {
                                stringResource(
                                    R.string.last_refresh,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
                                )
                            } ?: stringResource(R.string.no_refresh_yet),
                        )
                    }
                    LauncherAccountInfo(focus, "connection-playback", enabled) { Text(stringResource(R.string.playback_pending)) }
                }
                LauncherAccountPage.Preferences -> {
                    LauncherHeroPreference(state.preferences, focus, enabled, model::toggleHero)
                    if (state.preferences.writing) Text(stringResource(R.string.hero_preference_saving), color = LauncherStyle.muted)
                    if (state.preferences.failed) {
                        Text(stringResource(R.string.hero_preference_failed), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(if (isDefaultHome) R.string.home_selected else R.string.home_not_selected))
                    LauncherAccountAction(R.string.choose_home, focus, "settings-home", enabled) {
                        model.showDialog(LauncherDialog.HomeSettings)
                    }
                    LauncherAccountInfo(focus, "home-support", enabled) { Text(stringResource(R.string.home_support)) }
                }
            }
        }
        LauncherAccountAction(R.string.close, focus, "close", enabled, model::backToHome)
    }
}

@Composable
private fun LauncherHeroPreference(
    state: LauncherPreferenceState,
    focus: LauncherFocus,
    enabled: Boolean,
    toggle: () -> Unit,
) {
    val focused = enabled && focus.hasFocus && focus.current == "hero-toggle"
    val shape = RoundedCornerShape(8.dp)
    val description =
        stringResource(
            when (state.heroEnabled) {
                null -> R.string.loading
                true -> R.string.hero_on
                false -> R.string.hero_off
            },
        )
    val checked =
        when (state.heroEnabled) {
            true -> ToggleableState.On
            false -> ToggleableState.Off
            null -> ToggleableState.Indeterminate
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .launcherFocus(focus, "hero-toggle")
            .background(if (focused) LauncherStyle.text.copy(alpha = 0.16f) else LauncherStyle.tile, shape)
            .border(1.dp, if (focused) LauncherStyle.focusOutline else LauncherStyle.text.copy(alpha = 0.14f), shape)
            .semantics { stateDescription = description }
            .triStateToggleable(
                state = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = toggle,
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.show_cinematic_hero), Modifier.weight(1f), style = LauncherStyle.body)
        Text(description, color = LauncherStyle.muted, style = LauncherStyle.body)
        Canvas(Modifier.size(40.dp, 24.dp)) {
            drawRoundRect(
                color = if (state.heroEnabled == true) LauncherStyle.focusOutline else LauncherStyle.muted.copy(alpha = 0.35f),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(size.height / 2),
            )
            drawCircle(
                LauncherStyle.text,
                radius = 8.dp.toPx(),
                center = Offset(if (state.heroEnabled == true) size.width - 12.dp.toPx() else 12.dp.toPx(), size.height / 2),
            )
        }
    }
}

@Composable
internal fun LauncherAccountAction(
    label: Int,
    focus: LauncherFocus,
    target: String,
    enabled: Boolean,
    click: () -> Unit,
) {
    val focused = enabled && focus.hasFocus && focus.current == target
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .launcherFocus(focus, target)
            .background(
                if (focused) LauncherStyle.text.copy(alpha = 0.16f) else LauncherStyle.tile.copy(alpha = if (enabled) 1f else 0.4f),
                shape,
            ).border(
                1.dp,
                if (focused) LauncherStyle.focusOutline else LauncherStyle.text.copy(alpha = if (enabled) 0.14f else 0.06f),
                shape,
            ).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(label),
            Modifier.weight(1f),
            color = if (enabled) LauncherStyle.text else LauncherStyle.muted,
            style = LauncherStyle.body,
        )
        if (target == "connection" || target == "preferences") {
            Canvas(Modifier.size(16.dp)) {
                val color = LauncherStyle.muted
                drawLine(
                    color,
                    Offset(size.width * 0.35f, size.height * 0.2f),
                    Offset(size.width * 0.65f, size.height * 0.5f),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.65f, size.height * 0.5f),
                    Offset(size.width * 0.35f, size.height * 0.8f),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun LauncherAccountInfo(
    focus: LauncherFocus,
    target: String,
    enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focused = enabled && focus.hasFocus && focus.current == target
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .launcherFocus(focus, target)
            .background(if (focused) LauncherStyle.text.copy(alpha = 0.1f) else LauncherStyle.clear, shape)
            .border(1.dp, if (focused) LauncherStyle.focusOutline else LauncherStyle.clear, shape)
            .focusable(enabled = enabled)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun LauncherConfigurationStatus.resource(): Int =
    when (this) {
        LauncherConfigurationStatus.Current -> R.string.layout_current
        LauncherConfigurationStatus.Cached -> R.string.layout_cached
        LauncherConfigurationStatus.Defaults -> R.string.layout_defaults
        LauncherConfigurationStatus.Unavailable -> R.string.layout_unavailable
        LauncherConfigurationStatus.Invalid -> R.string.layout_invalid
        LauncherConfigurationStatus.Unsupported -> R.string.layout_unsupported
    }
