// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
internal fun LauncherHomeScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    images: LauncherImageScope?,
    onApps: () -> Unit,
    onApp: (String) -> Unit,
    onMedia: (String, String) -> Unit,
    pages: SaveableStateHolder,
    onTab: (LauncherTab) -> Unit,
    onContentEntry: () -> Unit,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showApps = state.selectedTab == LauncherTab.Essentials
    val appKeys = if (showApps) state.apps.pins.map { "apps:${it.componentKey}" } + "apps:+" else emptyList()
    val rows = state.homeRows
    val mediaRows = rows.filter { it.cards.isNotEmpty() }
    val actionKeys =
        when {
            showApps || mediaRows.isNotEmpty() || state.restoring -> emptyList()
            state.cleanupPending -> listOf(JELLYFIN_RECOVERY)
            !state.signedIn && state.busy -> listOf(JELLYFIN_SETTINGS)
            !state.signedIn -> listOf(JELLYFIN_CONNECT)
            else -> listOf(JELLYFIN_REFRESH, JELLYFIN_SETTINGS)
        }
    val rowKeys =
        (if (showApps) listOf("apps") else emptyList()) + mediaRows.map { it.focusKey } +
            if (actionKeys.isNotEmpty()) listOf("jellyfin-actions") else emptyList()
    val ids =
        buildMap {
            if (showApps) put("apps", appKeys)
            mediaRows.forEach { row -> put(row.focusKey, row.cards.map { "${row.focusKey}:${it.id}" }) }
            if (actionKeys.isNotEmpty()) put("jellyfin-actions", actionKeys)
        }
    val tabKeys = LauncherTab.entries.map { it.focusKey }
    val allKeys = ids.values.flatten() + tabKeys + listOf("catalog", "settings", "system")

    fun fallback(): String =
        focus.rowTarget(focus.homeRow, ids[focus.homeRow].orEmpty())
            ?: mediaRows.getOrNull(focus.homeRowIndex.coerceIn(0, (mediaRows.size - 1).coerceAtLeast(0)))?.let {
                focus.rowTarget(it.focusKey, ids[it.focusKey].orEmpty())
            }
            ?: if (showApps) focus.rowTarget("apps", appKeys) ?: "apps:+" else actionKeys.firstOrNull() ?: state.selectedTab.focusKey
    val removedFocusTarget = if (focus.current != null && focus.current !in allKeys) fallback() else null
    // Selecting a tab keeps focus on its control; Down explicitly enters its page.
    LaunchedEffect(state.focusGeneration, focus) {
        val target =
            if (state.homeTabActivation) {
                state.selectedTab.focusKey
            } else {
                focus.current?.takeIf { it in allKeys } ?: state.homeInvoker.takeIf { it in allKeys } ?: fallback()
            }
        focus.request(target)
    }
    // Content removal also rescues focus using the previous semantic index.
    LaunchedEffect(allKeys, focus) {
        if (!state.homeTabActivation) removedFocusTarget?.let(focus::request)
    }
    LaunchedEffect(rowKeys, focus) {
        val index = mediaRows.indexOfFirst { it.focusKey == focus.homeRow }
        if (index >= 0) focus.homeRowIndex = index
        focus.retainRows(
            rows
                .map { it.focusKey }
                .toSet() + listOf("apps", "jellyfin-actions"),
        )
    }

    fun direction(
        row: String,
        index: Int,
        key: Key,
    ): Boolean {
        val keys = ids[row].orEmpty()
        val rowIndex = rowKeys.indexOf(row)
        val target =
            when (key) {
                Key.DirectionLeft -> keys.getOrNull((index - 1).coerceAtLeast(0))
                Key.DirectionRight -> keys.getOrNull((index + 1).coerceAtMost(keys.lastIndex))
                Key.DirectionUp ->
                    if (rowIndex ==
                        0
                    ) {
                        state.selectedTab.focusKey
                    } else {
                        rowKeys.getOrNull(rowIndex - 1)?.let { focus.rowTarget(it, ids[it].orEmpty()) }
                    }
                Key.DirectionDown -> rowKeys.getOrNull(rowIndex + 1)?.let { focus.rowTarget(it, ids[it].orEmpty()) }
                else -> return false
            }
        target?.let(focus::request)
        return true
    }
    Column(modifier) {
        Row(
            Modifier.padding(horizontal = 32.dp, vertical = 8.dp).focusGroup().onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val index = tabKeys.indexOf(focus.current).coerceAtLeast(0)
                    when (it.key) {
                        Key.DirectionLeft -> {
                            focus.request(tabKeys[(index - 1).coerceAtLeast(0)])
                            true
                        }
                        Key.DirectionRight -> {
                            focus.request(tabKeys[(index + 1).coerceAtMost(tabKeys.lastIndex)])
                            true
                        }
                        Key.DirectionUp -> {
                            focus.request("settings")
                            true
                        }
                        Key.DirectionDown -> {
                            onContentEntry()
                            focus.request(fallback())
                            true
                        }
                        else -> false
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LauncherTab.entries.forEach { tab ->
                val active = tab == state.selectedTab
                Button(
                    onClick = { onTab(tab) },
                    modifier =
                        Modifier
                            .launcherFocus(focus, tab.focusKey)
                            .semantics {
                                selected = active
                                role = Role.Tab
                            }.border(
                                if (active) 2.dp else 0.dp,
                                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            ),
                ) {
                    Text(stringResource(if (tab == LauncherTab.Essentials) R.string.essentials else R.string.jellyfin))
                }
            }
        }
        pages.SaveableStateProvider("${state.boundaryKey}:${state.homeResetGeneration}:${state.selectedTab.name}") {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showApps) {
                    Text(stringResource(R.string.apps), Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.titleLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp).focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        state.apps.pins.forEachIndexed { index, app ->
                            key(app.componentKey) {
                                LauncherTile(app.label, focus, appKeys[index], {
                                    onContentEntry()
                                    focus.rememberRow("apps", appKeys[index], index)
                                }, { direction("apps", index, it) }, { onApp(app.componentKey) }) {
                                    app.icon?.let { Image(it, null, Modifier.size(48.dp).align(Alignment.Center)) }
                                }
                            }
                        }
                        LauncherTile(stringResource(R.string.add_apps), focus, "apps:+", {
                            onContentEntry()
                            focus.rememberRow("apps", "apps:+", appKeys.lastIndex)
                        }, { direction("apps", appKeys.lastIndex, it) }, onApps) {
                            Text(
                                stringResource(R.string.add_apps_symbol),
                                Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.headlineLarge,
                            )
                        }
                    }
                    if (state.restoring) Text(stringResource(R.string.restoring), Modifier.padding(horizontal = 32.dp))
                }
                if (!showApps && mediaRows.isEmpty()) {
                    val title =
                        when {
                            state.restoring -> R.string.restoring
                            state.cleanupPending -> R.string.connection_cleanup_title
                            !state.signedIn && state.busy -> R.string.connection_busy
                            !state.signedIn -> R.string.connect_jellyfin
                            state.media.refreshing -> R.string.loading
                            else -> R.string.jellyfin_empty_title
                        }
                    val description =
                        when {
                            state.restoring -> null
                            state.cleanupPending -> R.string.cleanup_failed_message
                            !state.signedIn && state.busy -> R.string.connection_busy_message
                            !state.signedIn -> R.string.connect_jellyfin_message
                            rows.any { it.status == LauncherRowStatus.Failed || it.status == LauncherRowStatus.Denied } ->
                                R.string.jellyfin_load_failed
                            else -> R.string.jellyfin_empty_message
                        }
                    Text(stringResource(title), Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.titleLarge)
                    description?.let { Text(stringResource(it), Modifier.padding(horizontal = 32.dp)) }
                    Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        actionKeys.forEachIndexed { index, target ->
                            Button(
                                onClick = {
                                    focus.request(target)
                                    when (target) {
                                        JELLYFIN_CONNECT -> onConnect()
                                        JELLYFIN_REFRESH -> onRefresh()
                                        else -> onSettings()
                                    }
                                },
                                modifier =
                                    Modifier
                                        .launcherFocus(focus, target) {
                                            onContentEntry()
                                            focus.rememberRow("jellyfin-actions", target, index)
                                        }.onPreviewKeyEvent {
                                            it.type == KeyEventType.KeyDown && direction("jellyfin-actions", index, it.key)
                                        },
                            ) {
                                Text(
                                    stringResource(
                                        when (target) {
                                            JELLYFIN_CONNECT -> R.string.connect_server
                                            JELLYFIN_REFRESH -> R.string.refresh
                                            else -> R.string.launcher_settings
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                (if (!showApps && mediaRows.isEmpty()) emptyList() else rows).forEach { row ->
                    key(row.focusKey) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                row.localTitleResource?.let { stringResource(it) } ?: row.layout.title,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(stringResource(row.status.statusResource()), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (row.cards.isEmpty()) {
                            Text(
                                stringResource(if (row.status == LauncherRowStatus.Loading) R.string.loading else R.string.no_items),
                                Modifier.padding(horizontal = 32.dp),
                            )
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp).focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            row.cards.forEachIndexed { index, card ->
                                val target = "${row.focusKey}:${card.id}"
                                key(target) {
                                    LauncherTile(card.title, focus, target, {
                                        onContentEntry()
                                        focus.rememberRow(row.focusKey, target, index, mediaRows.indexOfFirst { it.id == row.id })
                                    }, { direction(row.focusKey, index, it) }, { onMedia(target, card.id) }, row.layout.aspect) {
                                        val context = LocalContext.current
                                        val request =
                                            remember(images, card, row.layout.aspect) { images?.request(context, card, row.layout.aspect) }
                                        if (request != null && images != null) {
                                            AsyncImage(
                                                request,
                                                null,
                                                images.loader,
                                                Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        card.progress?.let { progress ->
                                            Box(
                                                Modifier
                                                    .align(Alignment.BottomStart)
                                                    .fillMaxWidth(progress)
                                                    .height(4.dp)
                                                    .background(MaterialTheme.colorScheme.primary),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherTile(
    title: String,
    focus: LauncherFocus,
    target: String,
    onFocused: () -> Unit,
    direction: (Key) -> Boolean,
    onClick: () -> Unit,
    aspect: LauncherAspect = LauncherAspect.Landscape,
    image: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(180), label = "launcher-card")
    val reveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .width(aspect.width.dp)
            .launcherFocus(focus, target) {
                onFocused()
                scope.launch { reveal.bringIntoView() }
            }.bringIntoViewRequester(reveal)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && direction(it.key) }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(aspect.width.dp, aspect.height.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            content = image,
        )
        Text(
            title,
            Modifier.padding(top = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun LauncherRowStatus.statusResource(): Int =
    when (this) {
        LauncherRowStatus.Loading -> R.string.loading
        LauncherRowStatus.Fresh -> R.string.up_to_date
        LauncherRowStatus.Cached -> R.string.cached
        LauncherRowStatus.Failed -> R.string.refresh_failed
        LauncherRowStatus.Denied -> R.string.access_denied
    }
