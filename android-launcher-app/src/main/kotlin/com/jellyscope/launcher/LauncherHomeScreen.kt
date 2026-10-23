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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appKeys = state.apps.pins.map { "apps:${it.componentKey}" } + "apps:+"
    val mediaRows = state.media.rows.filter { it.cards.isNotEmpty() }
    val rowKeys = listOf("apps") + mediaRows.map { it.focusKey }
    val ids = mapOf("apps" to appKeys) + mediaRows.associate { row -> row.focusKey to row.cards.map { "${row.focusKey}:${it.id}" } }
    val allKeys = ids.values.flatten() + listOf("catalog", "settings", "system")

    fun fallback(): String =
        focus.rowTarget(focus.homeRow, ids[focus.homeRow].orEmpty())
            ?: mediaRows.getOrNull(focus.homeRowIndex.coerceIn(0, (mediaRows.size - 1).coerceAtLeast(0)))?.let {
                focus.rowTarget(it.focusKey, ids[it.focusKey].orEmpty())
            }
            ?: focus.rowTarget("apps", appKeys) ?: "apps:+"
    val removedFocusTarget = if (focus.current != null && focus.current !in allKeys) fallback() else null
    LaunchedEffect(state.focusGeneration) {
        val target =
            focus.current?.takeIf { it in allKeys }
                ?: state.homeInvoker.takeIf { it in allKeys }
                ?: fallback()
        focus.request(target)
    }
    // Content removal also rescues focus using the previous semantic index.
    LaunchedEffect(allKeys) {
        removedFocusTarget?.let(focus::request)
    }
    LaunchedEffect(rowKeys) {
        val index = mediaRows.indexOfFirst { it.focusKey == focus.homeRow }
        if (index >= 0) focus.homeRowIndex = index
        focus.retainRows(
            state.media.rows
                .map { it.focusKey }
                .toSet() + "apps",
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
                        "settings"
                    } else {
                        rowKeys.getOrNull(rowIndex - 1)?.let { focus.rowTarget(it, ids[it].orEmpty()) }
                    }
                Key.DirectionDown -> rowKeys.getOrNull(rowIndex + 1)?.let { focus.rowTarget(it, ids[it].orEmpty()) }
                else -> return false
            }
        target?.let(focus::request)
        return true
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.apps), Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.titleLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            state.apps.pins.forEachIndexed { index, app ->
                key(app.componentKey) {
                    LauncherTile(app.label, focus, appKeys[index], {
                        focus.rememberRow("apps", appKeys[index], index)
                    }, { direction("apps", index, it) }, { onApp(app.componentKey) }) {
                        app.icon?.let { Image(it, null, Modifier.size(48.dp).align(Alignment.Center)) }
                    }
                }
            }
            LauncherTile(stringResource(R.string.add_apps), focus, "apps:+", {
                focus.rememberRow("apps", "apps:+", appKeys.lastIndex)
            }, { direction("apps", appKeys.lastIndex, it) }, onApps) {
                Text(
                    stringResource(R.string.add_apps_symbol),
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
        }
        if (!state.signedIn) {
            Text(
                stringResource(if (state.restoring) R.string.restoring else R.string.sign_in_for_media),
                Modifier.padding(horizontal = 32.dp),
            )
        }
        state.media.rows.forEach { row ->
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
                        stringResource(
                            if (row.status ==
                                LauncherRowStatus.Loading
                            ) {
                                R.string.loading
                            } else {
                                R.string.no_items
                            },
                        ),
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
                                focus.rememberRow(row.focusKey, target, index, mediaRows.indexOfFirst { it.id == row.id })
                            }, { direction(row.focusKey, index, it) }, { onMedia(target) }, row.layout.aspect) {
                                val context = LocalContext.current
                                val request =
                                    remember(images, card, row.layout.aspect) { images?.request(context, card, row.layout.aspect) }
                                if (request != null &&
                                    images != null
                                ) {
                                    AsyncImage(request, null, images.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                card.progress?.let { progress ->
                                    Box(
                                        Modifier
                                            .align(
                                                Alignment.BottomStart,
                                            ).fillMaxWidth(progress)
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
