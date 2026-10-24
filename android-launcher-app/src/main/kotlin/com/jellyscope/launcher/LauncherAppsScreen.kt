// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.res.stringResource
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
    val keys = catalog.map { "${it.componentKey}:open" }
    val allKeys = keys
    val request = (state.appFocusTarget as? LauncherAppFocusTarget.App)?.takeIf { it.origin == LauncherAppOrigin.Catalog }
    val restore =
        focus.current?.takeIf { !focus.isParentTarget(it) && it !in allKeys }?.let {
            focus.rowTarget("catalog", keys)
                ?: "settings"
        }
    val modal = state.dialog != null || state.appEdit is LauncherAppEdit.Menu
    // Consuming a typed target must not supersede its pending attach request.
    LaunchedEffect(state.focusGeneration, focus, modal) {
        if (!modal) {
            if (request == null && focus.awaitingTarget(keys)) return@LaunchedEffect
            focus.request(
                request?.key?.takeIf { it in allKeys } ?: focus.current?.takeIf { focus.isParentTarget(it) || it in allKeys }
                    ?: focus.rowTarget("catalog", keys)
                    ?: "settings",
            )
            request?.let(model::appFocusHandled)
        }
    }
    LaunchedEffect(keys, modal) { if (!modal && request == null && !focus.awaitingTarget(keys)) restore?.let(focus::request) }
    BoxWithConstraints(modifier) {
        val columns =
            ((maxWidth - LauncherStyle.inset * 2 + LauncherStyle.appGap) / (LauncherStyle.appWidth + LauncherStyle.appGap))
                .toInt()
                .coerceAtLeast(
                    1,
                )
        val rows = remember(catalog, columns) { catalog.chunked(columns) }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = LauncherStyle.inset, vertical = LauncherStyle.appRowPadding),
            verticalArrangement = Arrangement.spacedBy(LauncherStyle.appRowPadding),
        ) {
            Text(stringResource(R.string.all_apps), color = LauncherStyle.text, style = LauncherStyle.heading)
            if (catalog.isEmpty()) Text(stringResource(R.string.apps_empty), style = LauncherStyle.body)
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(LauncherStyle.appGap)) {
                    row.forEachIndexed { column, app ->
                        val index = rowIndex * columns + column
                        key(app.componentKey) {
                            LauncherAppTile(
                                app,
                                app.label,
                                focus,
                                keys[index],
                                index,
                                state.appEditEpoch,
                                clickLabel = stringResource(R.string.open_app),
                                onFocused = { focus.rememberRow("catalog", keys[index], index) },
                                direction = { direction ->
                                    if (direction == Key.DirectionDown && rowIndex == rows.lastIndex) {
                                        true
                                    } else {
                                        val destination =
                                            when (direction) {
                                                Key.DirectionLeft -> (index - 1).coerceAtLeast(rowIndex * columns)
                                                Key.DirectionRight -> (index + 1).coerceAtMost((rowIndex * columns + row.lastIndex))
                                                Key.DirectionUp -> index - columns
                                                Key.DirectionDown -> (index + columns).coerceAtMost(catalog.lastIndex)
                                                else -> null
                                            }
                                        if (destination == null) {
                                            false
                                        } else {
                                            focus.request(keys.getOrNull(destination) ?: "settings")
                                            true
                                        }
                                    }
                                },
                                onClick = { open(app.componentKey) },
                                onLongClick = { model.showAppMenu(app.componentKey, LauncherAppOrigin.Catalog, it) },
                                selected = (state.appEdit as? LauncherAppEdit.Menu)?.target?.componentKey == app.componentKey,
                            )
                        }
                    }
                }
            }
        }
    }
}
