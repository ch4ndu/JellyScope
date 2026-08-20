// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeUiState
import com.jellyscope.ui.screen.home.RowState
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun Modifier.tvHomeAmbientBackground(presentation: TvHeroAmbientPresentation): Modifier {
    // Read animated color in draw, not composition.
    val fallbackBrush = LocalAppBackgroundBrush.current
    val ambientNavy = LocalJellyfinPalette.current.navy
    val ambientGradientBottom = LocalJellyfinPalette.current.gradientBottom
    val ambientAnimated = remember { Animatable(Color.Transparent) }
    LaunchedEffect(
        ambientAnimated,
        presentation.animationOwnerKey,
        presentation.clearWhenColorMissing,
        presentation.color,
    ) {
        val color = presentation.color
        // Preserve tint while the selected owner's color resolves.
        if (color == null && !presentation.clearWhenColorMissing) return@LaunchedEffect
        ambientAnimated.animateTo(
            targetValue = color ?: Color.Transparent,
            animationSpec = tween(durationMillis = 700),
        )
    }
    return drawBehind {
        drawRect(brush = fallbackBrush)
        val tint = ambientAnimated.value
        if (tint.alpha > 0.004f) {
            drawRect(
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to tint,
                            0.48f to tint.copy(alpha = tint.alpha * 0.44f).compositeOver(ambientNavy),
                            1f to ambientGradientBottom,
                        ),
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeContent(
    session: Session,
    state: HomeUiState,
    focusedItem: MediaCardUi?,
    restoredFocusItemId: String?,
    restoredFocusRow: String?,
    restoredFocusIndex: Int,
    restoredFocusViewAll: Boolean,
    ambientPresentation: TvHeroAmbientPresentation,
    onRetry: (HomeRow) -> Unit,
    onFocusedItem: (MediaCardUi, HomeRow) -> Unit,
    onPosterLoaded: (String, Bitmap) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onViewAllSelected: (HomeRow) -> Unit,
    modifier: Modifier = Modifier,
    focusRescueTick: Int = 0,
) {
    val continueWatchingTitle = stringResource(R.string.tv_continue_watching)
    val favoritesTitle = stringResource(R.string.tv_favorites)
    val nextUpTitle = stringResource(R.string.tv_next_up)
    val recentlyAddedTitle = stringResource(R.string.tv_recently_added)
    val rows =
        remember(
            state.continueWatching,
            state.favorites,
            state.nextUp,
            state.recentlyAdded,
            continueWatchingTitle,
            favoritesTitle,
            nextUpTitle,
            recentlyAddedTitle,
        ) {
            listOf(
                TvHomeRowSpec(
                    HomeRow.ContinueWatching,
                    continueWatchingTitle,
                    state.continueWatching,
                    false,
                ),
                TvHomeRowSpec(HomeRow.Favorites, favoritesTitle, state.favorites, false),
                TvHomeRowSpec(HomeRow.NextUp, nextUpTitle, state.nextUp, false),
                TvHomeRowSpec(
                    HomeRow.RecentlyAdded,
                    recentlyAddedTitle,
                    state.recentlyAdded,
                    false,
                ),
            )
        }

    val visibleRows = remember(rows) { rows.filter { spec -> spec.rowState != RowState.Empty } }
    val restoreConsumed = remember { mutableStateOf(false) }

    // Expire restore when another card gains focus.
    val restoreExpired = remember { mutableStateOf(false) }

    fun TvHomeRowSpec.containsItem(itemId: String): Boolean =
        (rowState as? RowState.Content)?.items?.any { item -> item.id == itemId } == true

    fun TvHomeRowSpec.defaultFocusTarget(): TvHomeFocusTarget? =
        when (val state = rowState) {
            is RowState.Content ->
                state.items.firstOrNull()?.let { item -> TvHomeFocusTarget.Item(row, item.id) }
            RowState.Loading,
            RowState.Error,
            -> TvHomeFocusTarget.RowSlot(row)
            RowState.Empty -> null
        }

    fun focusableRowAfter(row: HomeRow): HomeRow? {
        val currentIndex = rows.indexOfFirst { spec -> spec.row == row }
        val candidates =
            if (currentIndex >= 0) {
                rows.drop(currentIndex + 1) + rows.take(currentIndex)
            } else {
                rows
            }
        return candidates.firstOrNull { spec -> spec.rowState != RowState.Empty }?.row
    }

    // Scope restore by row because an item can appear in multiple ribbons.
    val hostedRailController = LocalTvHostedRailController.current
    val hostedRail = hostedRailController.enabled
    val hostedRailVisible = hostedRailController.visible
    val contentAutofocusSuppressed = hostedRail && hostedRailController.contentAutofocusSuppressed
    val hostedRailContentRegistrationKey = remember { hostedRailController.contentRegistrationKey }
    val restoreExpiredValue = restoreExpired.value
    val focusTarget: TvHomeFocusTarget? =
        remember(
            rows,
            visibleRows,
            restoredFocusItemId,
            restoredFocusRow,
            restoredFocusIndex,
            restoredFocusViewAll,
            restoreExpiredValue,
            contentAutofocusSuppressed,
        ) {
            if (restoreConsumed.value || restoreExpiredValue || contentAutofocusSuppressed) {
                null
            } else {
                val restoredRow = HomeRow.entries.firstOrNull { row -> row.name == restoredFocusRow }
                val restoredViewAll =
                    restoredRow
                        ?.takeIf { restoredFocusViewAll }
                        ?.let { row ->
                            rows
                                .firstOrNull { spec -> spec.row == row && spec.rowState != RowState.Empty }
                                ?.let { TvHomeFocusTarget.ViewAll(row) }
                        }
                val restoredItem =
                    restoredFocusItemId
                        ?.takeUnless { restoredFocusViewAll }
                        ?.let { itemId ->
                            if (restoredRow != null) {
                                rows
                                    .firstOrNull { spec ->
                                        spec.row == restoredRow &&
                                            spec.rowState != RowState.Empty
                                    }?.let { spec ->
                                        when (val rowState = spec.rowState) {
                                            is RowState.Content ->
                                                homeRestoreItemIndex(
                                                    itemIds = rowState.items.map { item -> item.id },
                                                    targetItemId = itemId,
                                                    preferredIndex = restoredFocusIndex,
                                                )?.let { index ->
                                                    TvHomeFocusTarget.Item(spec.row, rowState.items[index].id)
                                                }
                                            RowState.Loading -> TvHomeFocusTarget.Item(spec.row, itemId)
                                            else -> null
                                        }
                                    }
                            } else {
                                rows
                                    .firstOrNull { spec -> spec.containsItem(itemId) }
                                    ?.let { spec -> TvHomeFocusTarget.Item(spec.row, itemId) }
                            }
                        }
                // If the item is gone, stay in its row before trying another row.
                val restoredRowFallback: TvHomeFocusTarget? =
                    restoredRow
                        ?.takeIf { restoredFocusItemId != null && !restoredFocusViewAll }
                        ?.let { row ->
                            rows.firstOrNull { spec -> spec.row == row }?.let { spec ->
                                val content = spec.rowState as? RowState.Content
                                content
                                    ?.items
                                    ?.getOrNull(restoredFocusIndex.coerceIn(0, content.items.lastIndex.coerceAtLeast(0)))
                                    ?.let { item -> TvHomeFocusTarget.Item(row, item.id) }
                                    ?: spec.defaultFocusTarget()
                            }
                                ?: focusableRowAfter(row)?.let { nextRow ->
                                    rows.firstOrNull { spec -> spec.row == nextRow }?.defaultFocusTarget()
                                }
                        }
                restoredViewAll
                    ?: restoredItem
                    ?: restoredRowFallback
                    ?: visibleRows.firstNotNullOfOrNull { spec -> spec.defaultFocusTarget() }
            }
        }
    var focusedLoadingRow by remember { mutableStateOf<HomeRow?>(null) }
    var collapsedRowFocusTarget by remember { mutableStateOf<HomeRow?>(null) }
    var collapsedRowFocusTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(rows, focusedLoadingRow) {
        val row = focusedLoadingRow ?: return@LaunchedEffect
        if (rows.firstOrNull { spec -> spec.row == row }?.rowState == RowState.Empty) {
            focusedLoadingRow = null
            focusableRowAfter(row)?.let { nextRow ->
                collapsedRowFocusTarget = nextRow
                collapsedRowFocusTick++
            }
        }
    }
    val homeFocusScope = rememberTvFocusScopeNode(listOf("home"))
    val shelfFocusRequester = homeFocusScope.entryRequester
    val railHasFocus = hostedRailController.railHasFocus
    var lastFocusedRow by remember { mutableStateOf<HomeRow?>(null) }
    var contentFocusEstablished by remember { mutableStateOf(false) }
    var focusFirstRibbonTick by remember { mutableIntStateOf(0) }
    val firstVisibleRow =
        remember(visibleRows) {
            visibleRows.firstOrNull()?.row
        }
    // Rescue removed focused cards within their row first.
    val rescueRow =
        lastFocusedRow?.takeIf { row -> visibleRows.any { spec -> spec.row == row } } ?: firstVisibleRow

    LaunchedEffect(focusedItem) {
        if (focusedItem != null) {
            contentFocusEstablished = true
        }
    }

    val currentContentAutofocusSuppressed = rememberUpdatedState(contentAutofocusSuppressed)
    val currentContentFocusEstablished = rememberUpdatedState(contentFocusEstablished)
    val focusContent =
        remember(shelfFocusRequester) {
            {
                if (currentContentAutofocusSuppressed.value && !currentContentFocusEstablished.value) {
                    focusFirstRibbonTick++
                    true
                } else {
                    if (shelfFocusRequester.requestFocusSafely()) {
                        true
                    } else {
                        focusFirstRibbonTick++
                        true
                    }
                }
            }
        }

    LaunchedEffect(hostedRailVisible, hostedRailContentRegistrationKey, shelfFocusRequester, focusContent) {
        if (hostedRailVisible) {
            hostedRailController.setContentRightFocusRequester(
                hostedRailContentRegistrationKey,
                shelfFocusRequester,
            )
            hostedRailController.setContentRightFocusAction(
                hostedRailContentRegistrationKey,
                focusContent,
            )
        }
    }

    fun onRailFocused() {
        if (focusedLoadingRow != null) {
            restoreExpired.value = true
        }
        focusedLoadingRow = null
    }

    LaunchedEffect(hostedRailVisible, railHasFocus) {
        if (hostedRailVisible && railHasFocus) {
            onRailFocused()
        }
    }

    fun requestRailFocus(): Boolean =
        if (hostedRail) {
            hostedRailController.requestRailFocus()
        } else {
            false
        }

    // BACK moves from lower ribbon to first ribbon to rail, then exits.
    BackHandler(enabled = !railHasFocus) {
        if (firstVisibleRow != null && lastFocusedRow != firstVisibleRow) {
            focusFirstRibbonTick++
        } else {
            requestRailFocus()
        }
    }

    // Inset interactive content, not the full-bleed backdrop.
    val contentStartPadding =
        if (hostedRailVisible) {
            TvDimens.drawerContentStartPadding
        } else {
            TvDimens.overscanHorizontal
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .tvHomeAmbientBackground(ambientPresentation),
    ) {
        AmbientLayer(
            ambientColor = ambientPresentation.color,
            animationOwnerKey = ambientPresentation.animationOwnerKey,
            clearWhenColorMissing = ambientPresentation.clearWhenColorMissing,
        )
        TvHeroBackdrop(
            session = session,
            item = focusedItem,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        TvClock(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = TvDimens.overscanVertical,
                        end = TvDimens.overscanHorizontal,
                    ),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = TvDimens.overscanVertical,
                    ),
        ) {
            TvHeroZone(
                item = focusedItem,
                modifier =
                    Modifier
                        .padding(
                            start = contentStartPadding,
                            end = TvDimens.overscanHorizontal,
                        ).fillMaxWidth()
                        .height(TvDimens.heroHeight),
            )

            // Explicit vertical pinning coexists with each ribbon's horizontal scroll.
            val scrollState = rememberScrollState()
            val scrollScope = rememberCoroutineScope()
            val density = LocalDensity.current
            val rowPositions = remember { mutableStateMapOf<HomeRow, Int>() }
            val horizontalBringIntoViewSpec =
                remember {
                    object : BringIntoViewSpec {
                        override val scrollAnimationSpec: AnimationSpec<Float> =
                            spring(
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = 0.5f,
                            )

                        // Center-pivot ribbon scrolling.
                        override fun calculateScrollDistance(
                            offset: Float,
                            size: Float,
                            containerSize: Float,
                        ): Float = offset - (containerSize - size) / 2f
                    }
                }
            val noAutoScrollSpec =
                remember {
                    object : BringIntoViewSpec {
                        override fun calculateScrollDistance(
                            offset: Float,
                            size: Float,
                            containerSize: Float,
                        ): Float = 0f
                    }
                }

            CompositionLocalProvider(LocalBringIntoViewSpec provides noAutoScrollSpec) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            // Restore the shelf's last focused card.
                            .focusRequester(shelfFocusRequester)
                            .tvFocusScope(homeFocusScope) {
                                visibleRows.map { row -> "row:${row.row.name}" }
                            }.verticalScroll(scrollState)
                            .padding(bottom = TvDimens.rowGap + TvDimens.overscanVertical),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
                ) {
                    visibleRows.forEach { rowSpec ->
                        val homeRow = rowSpec.row
                        key(homeRow) {
                            TvHomeRow(
                                row = rowSpec,
                                session = session,
                                parentFocusScope = homeFocusScope,
                                focusTarget = focusTarget?.takeIf { target -> target.row == homeRow },
                                focusFirstSlotTick =
                                    (if (homeRow == firstVisibleRow) focusFirstRibbonTick else 0) +
                                        (if (homeRow == rescueRow) focusRescueTick else 0) +
                                        (if (homeRow == collapsedRowFocusTarget) collapsedRowFocusTick else 0),
                                loadingPlaceholderHadFocus = focusedLoadingRow == homeRow,
                                contentStartPadding = contentStartPadding,
                                horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                                onRowFocused = {
                                    scrollScope.launch {
                                        // Scale duration by distance for constant-speed row motion.
                                        val target = rowPositions[homeRow] ?: return@launch
                                        val distance = (target - scrollState.value).toFloat()
                                        if (distance != 0f) {
                                            scrollState.animateScrollTo(
                                                value = target,
                                                animationSpec =
                                                    tween(
                                                        durationMillis =
                                                            verticalScrollDurationMs(
                                                                distancePx = distance,
                                                                density = density.density,
                                                            ),
                                                        easing = FastOutSlowInEasing,
                                                    ),
                                            )
                                        }
                                    }
                                },
                                onRetry = onRetry,
                                onFocusedItem = { item ->
                                    val target = focusTarget
                                    val focusedTarget = TvHomeFocusTarget.Item(homeRow, item.id)
                                    if (target == focusedTarget) {
                                        // Restore completes only when the target owns focus.
                                        restoreConsumed.value = true
                                    } else if (
                                        shouldExpireHomeRestore(
                                            target = target,
                                            focused = focusedTarget,
                                            restoreConsumed = restoreConsumed.value,
                                        )
                                    ) {
                                        restoreExpired.value = true
                                    }
                                    focusedLoadingRow = null
                                    lastFocusedRow = homeRow
                                    contentFocusEstablished = true
                                    onFocusedItem(item, homeRow)
                                },
                                onLoadingPlaceholderFocused = {
                                    val target = focusTarget
                                    if (target == null ||
                                        target.row != homeRow ||
                                        target is TvHomeFocusTarget.RowSlot
                                    ) {
                                        restoreExpired.value = true
                                    }
                                    focusedLoadingRow = homeRow
                                    lastFocusedRow = homeRow
                                    contentFocusEstablished = true
                                },
                                onLoadingPlaceholderReplaced = {
                                    if (focusedLoadingRow == homeRow) {
                                        focusedLoadingRow = null
                                    }
                                },
                                onRetryFocused = {
                                    focusedLoadingRow = null
                                    lastFocusedRow = homeRow
                                    contentFocusEstablished = true
                                },
                                onViewAllFocused = {
                                    val target = focusTarget
                                    val focusedTarget = TvHomeFocusTarget.ViewAll(homeRow)
                                    if (target == focusedTarget) {
                                        restoreConsumed.value = true
                                    } else if (
                                        shouldExpireHomeRestore(
                                            target = target,
                                            focused = focusedTarget,
                                            restoreConsumed = restoreConsumed.value,
                                        )
                                    ) {
                                        restoreExpired.value = true
                                    }
                                    focusedLoadingRow = null
                                    lastFocusedRow = homeRow
                                    contentFocusEstablished = true
                                },
                                onPosterLoaded = onPosterLoaded,
                                onItemSelected = onItemSelected,
                                onItemPlayDirect = onItemPlayDirect,
                                onViewAllSelected = { onViewAllSelected(homeRow) },
                                modifier =
                                    Modifier.onGloballyPositioned { coords ->
                                        rowPositions[homeRow] = coords.positionInParent().y.roundToInt()
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
