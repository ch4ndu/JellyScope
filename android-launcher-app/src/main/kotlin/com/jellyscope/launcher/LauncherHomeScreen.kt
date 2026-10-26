// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.jellyscope.ui.focus.rememberNoAutoScrollSpec
import com.jellyscope.ui.focus.verticalScrollDurationMs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LauncherHomeScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    model: LauncherViewModel,
    images: LauncherImageScope?,
    onApp: (String) -> Unit,
    onMedia: (String, String) -> Unit,
    pages: SaveableStateHolder,
    onContentEntry: () -> Unit,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showApps = state.selectedTab == LauncherTab.Essentials
    val appKeys = if (showApps) state.apps.catalog.map { "apps:${it.componentKey}" } else emptyList()
    val rows = state.homeRows
    val mediaRows = rows.filter { it.cards.isNotEmpty() }
    val actionKeys = state.homeActions(state.selectedTab)
    val rowKeys =
        (if (appKeys.isNotEmpty()) listOf("apps") else emptyList()) + mediaRows.map { it.focusKey } +
            if (actionKeys.isNotEmpty()) listOf("jellyfin-actions") else emptyList()
    val ids =
        buildMap {
            if (showApps) put("apps", appKeys)
            mediaRows.forEach { row -> put(row.focusKey, row.cards.map { "${row.focusKey}:${it.id}" }) }
            if (actionKeys.isNotEmpty()) put("jellyfin-actions", actionKeys)
        }
    val allKeys = ids.values.flatten()
    val moving = state.appEdit as? LauncherAppEdit.Moving
    val modal = state.backgroundSuppressed
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val request = state.appFocusTarget
    val horizontalSpec = LocalBringIntoViewSpec.current
    val noAutoScrollSpec = rememberNoAutoScrollSpec()
    val cinematic = state.preferences.heroEnabled == true && state.signedIn && state.boundaryKey != null && mediaRows.isNotEmpty()
    val heroHeight = launcherHeroHeight(state.selectedTab)

    fun fallback(): String? =
        focus.rowTarget(focus.homeRow, ids[focus.homeRow].orEmpty())
            ?: mediaRows.getOrNull(focus.homeRowIndex.coerceIn(0, (mediaRows.size - 1).coerceAtLeast(0)))?.let {
                focus.rowTarget(it.focusKey, ids[it.focusKey].orEmpty())
            }
            ?: if (showApps) focus.rowTarget("apps", appKeys) else actionKeys.firstOrNull()
    val removedFocus = focus.current != null && !focus.isParentTarget(focus.current) && focus.current !in allKeys
    // The generation owns this one-shot request. Consuming it must not rerun the
    // effect and replace a request whose tile is still waiting to attach.
    LaunchedEffect(state.focusGeneration, focus, modal, state.apps.loaded, windowFocused) {
        if (!modal && windowFocused) {
            if (request == null && focus.awaitingTarget(allKeys)) {
                focus.retryPending()
                return@LaunchedEffect
            }
            val target =
                when (request) {
                    is LauncherAppFocusTarget.App ->
                        request.key.takeIf { it in appKeys }
                            ?: focus.rowTarget("apps", appKeys)
                    is LauncherAppFocusTarget.HomeContent -> if (request.appsOnly) focus.rowTarget("apps", appKeys) else fallback()
                    is LauncherAppFocusTarget.Header -> request.key
                    null ->
                        if (state.homeTabActivation) {
                            state.selectedTab.focusKey
                        } else {
                            focus.current?.takeIf { focus.isParentTarget(it) || it in allKeys }
                                ?: state.homeInvoker.takeIf { it in allKeys }
                                ?: state.homeInvoker.takeIf(focus::isParentTarget)
                                ?: fallback()
                        }
                }
            focus.request(target ?: state.selectedTab.focusKey)
            if (request !is LauncherAppFocusTarget.HomeContent || !request.appsOnly || state.apps.loaded) {
                request?.let(model::appFocusHandled)
            }
        }
    }
    // Content removal also rescues focus using the previous semantic index.
    LaunchedEffect(allKeys, focus, modal, windowFocused) {
        if (!modal && windowFocused && request == null && !focus.awaitingTarget(allKeys) && !state.homeTabActivation && removedFocus) {
            focus.request(fallback() ?: state.selectedTab.focusKey)
        }
    }
    LaunchedEffect(rowKeys, focus, modal) {
        if (modal) return@LaunchedEffect
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
        if (modal) return false
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
    pages.SaveableStateProvider("${state.boundaryKey}:${state.homeResetGeneration}:${state.selectedTab.name}") {
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val rowPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
        var viewportHeight by remember { mutableStateOf(0) }
        var viewportTop by remember { mutableStateOf(0f) }
        val heroBottom = with(density) { heroHeight.roundToPx() }
        val focusedRow = rowKeys.firstOrNull { key -> focus.current in ids[key].orEmpty() }
        var mediaOwnsHero by remember { mutableStateOf(false) }
        LaunchedEffect(focusedRow, cinematic, modal, windowFocused) {
            if (!cinematic) {
                mediaOwnsHero = false
            } else if (!modal && windowFocused && focusedRow != null) {
                mediaOwnsHero = focusedRow != "apps"
            }
        }
        val appsHidden = cinematic && mediaOwnsHero
        val appsAlpha by animateFloatAsState(
            if (appsHidden) 0f else 1f,
            tween(LauncherStyle.HERO_CONTENT_FADE_MILLIS),
            label = "launcher-apps",
        )
        val selectedPosition = focusedRow?.let { rowPositions[it]?.first }
        val lastRow = rows.lastOrNull()?.focusKey
        val lastHeight = lastRow?.let { rowPositions[it]?.second } ?: 0
        val trailing = if (cinematic && lastHeight > 0) (viewportHeight - heroBottom - lastHeight).coerceAtLeast(0) else 0
        // Dwell and decoded artwork never schedule a second scroll movement.
        LaunchedEffect(
            focusedRow,
            selectedPosition,
            cinematic,
            heroBottom,
            viewportHeight,
            lastHeight,
            scroll.maxValue,
            modal,
            windowFocused,
        ) {
            if (!cinematic || modal || !windowFocused || !focus.hasFocus || focus.parentFocused) return@LaunchedEffect
            val destination = if (focusedRow == "apps") 0 else selectedPosition?.minus(heroBottom) ?: return@LaunchedEffect
            // Wait for the measured trailing fill before beginning the one alignment.
            if (viewportHeight == 0 ||
                lastHeight == 0 ||
                scroll.maxValue == Int.MAX_VALUE ||
                scroll.maxValue < destination
            ) {
                return@LaunchedEffect
            }
            val clamped = destination.coerceIn(0, scroll.maxValue)
            if (clamped != scroll.value) {
                scroll.animateScrollTo(clamped, tween(verticalScrollDurationMs((clamped - scroll.value).toFloat(), density.density)))
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides if (cinematic) noAutoScrollSpec else horizontalSpec) {
            Column(
                modifier
                    .fillMaxWidth()
                    .onSizeChanged { viewportHeight = it.height }
                    .onGloballyPositioned { viewportTop = it.positionInRoot().y }
                    .verticalScroll(scroll)
                    .padding(top = LauncherStyle.contentTop),
                verticalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
            ) {
                if (cinematic && !showApps) {
                    // Padding and the following gap complete the hero-bottom reservation.
                    Spacer(Modifier.height((heroHeight - LauncherStyle.contentTop - LauncherStyle.appGap).coerceAtLeast(0.dp)))
                }
                if (showApps) {
                    Column(
                        Modifier
                            .graphicsLayer { alpha = appsAlpha }
                            .then(
                                if (appsHidden) {
                                    Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(
                                                    androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                                ).changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        verticalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
                    ) {
                        Text(
                            stringResource(R.string.apps),
                            Modifier.padding(horizontal = LauncherStyle.inset),
                            style = LauncherStyle.section,
                        )
                        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
                            Row(
                                Modifier
                                    .horizontalScroll(
                                        rememberScrollState(),
                                    ).padding(horizontal = LauncherStyle.inset, vertical = LauncherStyle.appRowPadding)
                                    .focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
                            ) {
                                state.apps.catalog.forEachIndexed { index, app ->
                                    key(app.componentKey) {
                                        LauncherAppTile(
                                            app,
                                            app.label,
                                            focus,
                                            appKeys[index],
                                            index,
                                            state.appEditEpoch,
                                            clickLabel = stringResource(if (moving != null) R.string.finish_move else R.string.open_app),
                                            onFocused = {
                                                if (!modal) {
                                                    onContentEntry()
                                                    focus.rememberRow("apps", appKeys[index], index)
                                                }
                                            },
                                            direction = {
                                                if (moving != null) {
                                                    when (it) {
                                                        Key.DirectionLeft -> {
                                                            model.moveApp(-1)
                                                            true
                                                        }
                                                        Key.DirectionRight -> {
                                                            model.moveApp(1)
                                                            true
                                                        }
                                                        Key.DirectionUp, Key.DirectionDown -> true
                                                        else -> false
                                                    }
                                                } else {
                                                    direction("apps", index, it)
                                                }
                                            },
                                            onClick = { if (moving != null) model.finishMoving() else onApp(app.componentKey) },
                                            onLongClick =
                                                if (moving == null) {
                                                    { anchor -> model.showAppMenu(app.componentKey, anchor) }
                                                } else {
                                                    null
                                                },
                                            moving = moving?.componentKey == app.componentKey,
                                            selected = (state.appEdit as? LauncherAppEdit.Menu)?.target?.componentKey == app.componentKey,
                                        )
                                    }
                                }
                            }
                        }
                        if (moving !=
                            null
                        ) {
                            Text(
                                stringResource(R.string.moving_app, state.editedApp?.label.orEmpty()),
                                Modifier.padding(horizontal = LauncherStyle.inset),
                                color = LauncherStyle.text,
                                style = LauncherStyle.body,
                            )
                            Text(
                                stringResource(if (state.orderWriting) R.string.saving_app_order else R.string.move_instructions),
                                Modifier.padding(horizontal = LauncherStyle.inset),
                                color = LauncherStyle.muted,
                                style = LauncherStyle.body,
                            )
                        }
                        if (state.orderWriting && moving == null) {
                            Text(
                                stringResource(R.string.saving_app_order),
                                Modifier.padding(horizontal = LauncherStyle.inset),
                                style = LauncherStyle.body,
                            )
                        }
                        if (state.apps.loaded && state.apps.catalog.isEmpty()) {
                            Text(
                                stringResource(R.string.apps_empty),
                                Modifier.padding(horizontal = LauncherStyle.inset),
                                style = LauncherStyle.body,
                            )
                        }
                        if (!state.apps.durable) {
                            Text(
                                stringResource(R.string.app_order_unsaved),
                                Modifier.padding(horizontal = LauncherStyle.inset),
                                style = LauncherStyle.body,
                            )
                        }
                    }
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
                    Text(stringResource(title), Modifier.padding(horizontal = LauncherStyle.inset), style = LauncherStyle.heading)
                    description?.let {
                        Text(
                            stringResource(it),
                            Modifier.padding(horizontal = LauncherStyle.inset),
                            color = LauncherStyle.muted,
                        )
                    }
                    Row(
                        Modifier.padding(horizontal = LauncherStyle.inset),
                        horizontalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
                    ) {
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
                                            if (!modal) {
                                                onContentEntry()
                                                focus.rememberRow("jellyfin-actions", target, index)
                                            }
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
                        Column(
                            Modifier.onGloballyPositioned {
                                rowPositions[row.focusKey] =
                                    (it.positionInRoot().y - viewportTop + scroll.value).roundToInt() to it.size.height
                            },
                            verticalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = LauncherStyle.inset),
                                horizontalArrangement = Arrangement.spacedBy(LauncherStyle.appGap),
                            ) {
                                Text(
                                    row.localTitleResource?.let { stringResource(it) } ?: row.layout.title,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                row.status.statusResource()?.let {
                                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (row.cards.isEmpty()) {
                                Text(
                                    stringResource(if (row.status == LauncherRowStatus.Loading) R.string.loading else R.string.no_items),
                                    Modifier.padding(horizontal = LauncherStyle.inset),
                                )
                            }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
                                Row(
                                    Modifier
                                        .horizontalScroll(
                                            rememberScrollState(),
                                        ).padding(horizontal = LauncherStyle.inset, vertical = LauncherStyle.appRowPadding)
                                        .focusGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(LauncherStyle.mediaGap),
                                ) {
                                    row.cards.forEachIndexed { index, card ->
                                        val target = "${row.focusKey}:${card.id}"
                                        key(target) {
                                            LauncherTile(
                                                card.title,
                                                focus,
                                                target,
                                                {
                                                    if (!modal) {
                                                        onContentEntry()
                                                        focus.rememberRow(
                                                            row.focusKey,
                                                            target,
                                                            index,
                                                            mediaRows.indexOfFirst {
                                                                it.id ==
                                                                    row.id
                                                            },
                                                        )
                                                    }
                                                },
                                                { direction(row.focusKey, index, it) },
                                                { onMedia(target, card.id) },
                                                row.layout.aspect,
                                            ) {
                                                val context = LocalContext.current
                                                val request =
                                                    remember(
                                                        images,
                                                        card,
                                                        row.layout.aspect,
                                                    ) { images?.request(context, card, row.layout.aspect) }
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
                                                            .height(LauncherStyle.mediaProgressHeight)
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
                if (trailing > 0) Spacer(Modifier.height(with(density) { trailing.toDp() }))
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
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        if (focused) LauncherStyle.MEDIA_FOCUS_SCALE else 1f,
        tween(LauncherStyle.FOCUS_MILLIS),
        label = "launcher-card",
    )
    Column(
        Modifier
            .width(aspect.width.dp)
            .launcherFocus(focus, target, onFocused)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && direction(it.key) }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .size(aspect.width.dp, aspect.height.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.border(
                    if (focused) LauncherStyle.focusBorder else LauncherStyle.noBorder,
                    if (focused) LauncherStyle.text else LauncherStyle.clear,
                    RoundedCornerShape(LauncherStyle.radius),
                ).clip(RoundedCornerShape(LauncherStyle.radius))
                .background(MaterialTheme.colorScheme.surface),
            content = image,
        )
        Text(
            title,
            Modifier.padding(top = LauncherStyle.mediaLabelGap),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun LauncherRowStatus.statusResource(): Int? =
    when (this) {
        LauncherRowStatus.Loading -> R.string.loading
        LauncherRowStatus.Fresh -> null
        LauncherRowStatus.Cached -> R.string.cached
        LauncherRowStatus.Failed -> R.string.refresh_failed
        LauncherRowStatus.Denied -> R.string.access_denied
    }
