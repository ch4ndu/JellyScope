// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.semantics
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
    homeSettings: () -> Unit,
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
                LauncherAccountPage.Preferences -> listOf("back-account", "settings-home", "home-support", "close")
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
                    Text(stringResource(if (isDefaultHome) R.string.home_selected else R.string.home_not_selected))
                    LauncherAccountAction(R.string.choose_home, focus, "settings-home", enabled, homeSettings)
                    LauncherAccountInfo(focus, "home-support", enabled) { Text(stringResource(R.string.home_support)) }
                }
            }
        }
        LauncherAccountAction(R.string.close, focus, "close", enabled, model::backToHome)
    }
}

@Composable
private fun LauncherAccountAction(
    label: Int,
    focus: LauncherFocus,
    target: String,
    enabled: Boolean,
    click: () -> Unit,
) {
    val focused = enabled && focus.hasFocus && focus.current == target
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .launcherFocus(focus, target)
            .background(if (focused) LauncherStyle.text.copy(alpha = 0.1f) else LauncherStyle.clear, shape)
            .border(1.dp, if (focused) LauncherStyle.focusOutline else LauncherStyle.clear, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(stringResource(label), color = if (enabled) LauncherStyle.text else LauncherStyle.muted, style = LauncherStyle.body)
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
