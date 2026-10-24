// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LauncherSettingsScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    model: LauncherViewModel,
    systemSettings: () -> Unit,
    isDefaultHome: Boolean,
    homeSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls =
        (if (!state.restoring && !state.busy) listOf("account") else emptyList()) + listOf("refresh", "settings-system", "settings-home")
    val enabledControls = controls + listOf("catalog", "settings", "system")
    LaunchedEffect(state.focusGeneration, controls) {
        focus.request(
            focus.current?.takeIf { it in enabledControls } ?: controls.first(),
        )
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(32.dp).focusGroup().onPreviewKeyEvent {
            val index = controls.indexOf(focus.current)
            if (it.type != KeyEventType.KeyDown || index < 0) {
                false
            } else {
                when (it.key) {
                    Key.DirectionUp -> {
                        focus.request(controls.getOrNull(index - 1) ?: "settings")
                        true
                    }
                    Key.DirectionDown -> {
                        controls.getOrNull(index + 1)?.let(focus::request)
                        true
                    }
                    else -> false
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.launcher_settings), style = MaterialTheme.typography.headlineMedium)
        Text(if (state.signedIn) state.accountLabel else stringResource(if (state.restoring) R.string.restoring else R.string.signed_out))
        if (state.serverUrl.isNotEmpty()) Text(state.serverUrl)
        Text(stringResource(state.media.configurationStatus.resource()))
        if (state.cleanupPending) {
            Text(stringResource(R.string.cleanup_failed_message), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(if (state.serverUrl.startsWith("http://", true)) R.string.http_notice else R.string.https_notice))
        Text(
            if (state.media.refreshing) {
                stringResource(
                    R.string.refreshing,
                )
            } else {
                stringResource(
                    R.string.cache_status,
                    state.media.rows.sumOf {
                        it.cards.size
                    },
                )
            },
        )
        Text(
            state.media.lastRefreshMs?.let {
                stringResource(R.string.last_refresh, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
            }
                ?: stringResource(R.string.no_refresh_yet),
        )
        Button(
            onClick = {
                if (state.cleanupPending) {
                    model.logout()
                } else if (state.signedIn) {
                    model.showDialog(LauncherDialog.Logout)
                } else {
                    model.beginLogin(LauncherLoginOrigin.Settings)
                }
            },
            enabled =
                !state.restoring && !state.busy,
            modifier = Modifier.launcherFocus(focus, "account"),
        ) {
            Text(
                stringResource(
                    if (state.cleanupPending) {
                        R.string.retry_sign_out
                    } else if (state.signedIn) {
                        R.string.sign_out
                    } else {
                        R.string.sign_in
                    },
                ),
            )
        }
        Button(onClick = model::refresh, modifier = Modifier.launcherFocus(focus, "refresh")) { Text(stringResource(R.string.refresh)) }
        Button(onClick = systemSettings, modifier = Modifier.launcherFocus(focus, "settings-system")) {
            Text(stringResource(R.string.system_settings))
        }
        Text(stringResource(if (isDefaultHome) R.string.home_selected else R.string.home_not_selected))
        Button(onClick = homeSettings, modifier = Modifier.launcherFocus(focus, "settings-home")) {
            Text(stringResource(R.string.choose_home))
        }
        Text(stringResource(R.string.home_support))
        Text(stringResource(R.string.playback_pending))
    }
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
