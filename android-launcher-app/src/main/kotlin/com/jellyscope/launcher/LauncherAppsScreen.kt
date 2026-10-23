// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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

@Composable
internal fun LauncherAppsScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    model: LauncherViewModel,
    open: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = state.apps.catalog
    val openKeys = catalog.map { "${it.componentKey}:open" }
    val current = focus.current
    val focusedApp = catalog.firstOrNull { current?.startsWith("${it.componentKey}:") == true }
    val focusedPinIndex = state.apps.pins.indexOfFirst { it.componentKey == focusedApp?.componentKey }
    val restoreTarget =
        when {
            focusedApp == null && current !in listOf(null, "catalog", "settings", "system") ->
                focus.rowTarget("catalog", openKeys)
                    ?: "system"
            focusedApp != null &&
                (
                    current == "${focusedApp.componentKey}:earlier" &&
                        focusedPinIndex <= 0 ||
                        current == "${focusedApp.componentKey}:later" &&
                        focusedPinIndex !in 0 until state.apps.pins.lastIndex ||
                        current == "${focusedApp.componentKey}:pin" &&
                        focusedPinIndex < 0 &&
                        state.apps.pins.size >= MAX_APP_PINS
                ) -> "${focusedApp.componentKey}:open"
            else -> null
        }
    LaunchedEffect(state.focusGeneration) { focus.request(focus.current ?: openKeys.firstOrNull() ?: "system") }
    LaunchedEffect(state.apps) {
        restoreTarget?.let(focus::request)
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.apps), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.pin_limit, MAX_APP_PINS))
        if (catalog.isEmpty()) Text(stringResource(R.string.apps_empty))
        catalog.forEachIndexed { catalogIndex, app ->
            key(app.componentKey) {
                val pinIndex = state.apps.pins.indexOfFirst { it.componentKey == app.componentKey }
                val controls =
                    buildList {
                        add("${app.componentKey}:open")
                        if (pinIndex >= 0 || state.apps.pins.size < MAX_APP_PINS) add("${app.componentKey}:pin")
                        if (pinIndex > 0) add("${app.componentKey}:earlier")
                        if (pinIndex >= 0 && pinIndex < state.apps.pins.lastIndex) add("${app.componentKey}:later")
                    }
                val rememberApp = { focus.rememberRow("catalog", "${app.componentKey}:open", catalogIndex) }
                Column(
                    Modifier.fillMaxWidth().focusGroup().onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (it.key) {
                                Key.DirectionUp -> {
                                    focus.request(openKeys.getOrNull(catalogIndex - 1) ?: "settings")
                                    true
                                }
                                Key.DirectionDown -> {
                                    openKeys.getOrNull(catalogIndex + 1)?.let(focus::request)
                                    true
                                }
                                else -> false
                            }
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        app.icon?.let { Image(it, null, Modifier.size(40.dp)) }
                        Text(app.label, style = MaterialTheme.typography.titleLarge)
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).onPreviewKeyEvent {
                            val index = controls.indexOf(focus.current)
                            if (it.type != KeyEventType.KeyDown || index < 0) {
                                false
                            } else {
                                when (it.key) {
                                    Key.DirectionLeft -> {
                                        focus.request(controls[(index - 1).coerceAtLeast(0)])
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        focus.request(controls[(index + 1).coerceAtMost(controls.lastIndex)])
                                        true
                                    }
                                    else -> false
                                }
                            }
                        },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                open(app.componentKey)
                            },
                            modifier =
                                Modifier.launcherFocus(
                                    focus,
                                    "${app.componentKey}:open",
                                    rememberApp,
                                ),
                        ) { Text(stringResource(R.string.open_app)) }
                        Button(
                            onClick = {
                                if (pinIndex >=
                                    0
                                ) {
                                    model.unpin(app.componentKey)
                                } else {
                                    model.pin(app.componentKey)
                                }
                            },
                            enabled =
                                pinIndex >= 0 || state.apps.pins.size < MAX_APP_PINS,
                            modifier = Modifier.launcherFocus(focus, "${app.componentKey}:pin", rememberApp),
                        ) {
                            Text(
                                stringResource(
                                    if (pinIndex >=
                                        0
                                    ) {
                                        R.string.unpin
                                    } else {
                                        R.string.pin
                                    },
                                ),
                            )
                        }
                        Button(onClick = {
                            model.move(app.componentKey, -1)
                        }, enabled = pinIndex > 0, modifier = Modifier.launcherFocus(focus, "${app.componentKey}:earlier", rememberApp)) {
                            Text(stringResource(R.string.move_earlier))
                        }
                        Button(
                            onClick = {
                                model.move(app.componentKey, 1)
                            },
                            enabled = pinIndex >= 0 && pinIndex < state.apps.pins.lastIndex,
                            modifier =
                                Modifier.launcherFocus(
                                    focus,
                                    "${app.componentKey}:later",
                                    rememberApp,
                                ),
                        ) {
                            Text(stringResource(R.string.move_later))
                        }
                    }
                }
            }
        }
    }
}
