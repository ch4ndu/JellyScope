// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.jellyscope.ui.adaptive.adaptiveScreenPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.settings_open
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

// Auto-hiding top-bar chrome slides vertically: DOWN from the top edge to reveal,
// UP off the top edge to hide (instead of the default expand/shrink toward a
// corner). Shared by the Library and Discover tabs so their overlaid chrome
// animates identically.
val TopBarSlideEnter: EnterTransition =
    slideInVertically(initialOffsetY = { height -> -height }) + fadeIn()

val TopBarSlideExit: ExitTransition =
    slideOutVertically(targetOffsetY = { height -> -height }) + fadeOut()

@Composable
fun AppTopBar(
    title: String,
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val horizontalContentPadding = adaptiveScreenPadding()

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = APP_TOP_BAR_BACKGROUND_ALPHA),
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ).heightIn(min = Dimensions.minTouchTarget),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.zero),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    val backLabel = stringResource(Res.string.detail_back)
                    TooltipIconButton(
                        label = backLabel,
                        onClick = onBack,
                        modifier = Modifier.size(Dimensions.minTouchTarget),
                        diagnosticTarget = InputDiagnosticTarget.BACK,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                }
                if (titleContent != null) {
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(
                                    start =
                                        if (onBack == null) {
                                            horizontalContentPadding
                                        } else {
                                            Dimensions.zero
                                        },
                                    end = Dimensions.inlineSpacing,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        titleContent()
                    }
                } else {
                    Text(
                        text = title,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(
                                    start =
                                        if (onBack == null) {
                                            horizontalContentPadding
                                        } else {
                                            Dimensions.zero
                                        },
                                    end = Dimensions.inlineSpacing,
                                ),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                actions()
                if (onSettingsClick != null) {
                    val settingsLabel = stringResource(Res.string.settings_open)
                    TooltipIconButton(
                        label = settingsLabel,
                        onClick = onSettingsClick,
                        modifier =
                            Modifier
                                .size(Dimensions.minTouchTarget),
                        diagnosticTarget = InputDiagnosticTarget.SETTINGS,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun rememberAutoHidingTopBarVisible(
    listState: LazyListState,
    resetKey: Any? = Unit,
): Boolean =
    rememberAutoHidingTopBarVisible(
        positionProvider =
            remember(listState) {
                TopBarPositionProvider {
                    TopBarScrollPosition(
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                    )
                }
            },
        resetKey = resetKey,
    )

@Composable
fun rememberAutoHidingTopBarVisible(
    gridState: LazyGridState,
    resetKey: Any? = Unit,
): Boolean =
    rememberAutoHidingTopBarVisible(
        positionProvider =
            remember(gridState) {
                TopBarPositionProvider {
                    TopBarScrollPosition(
                        index = gridState.firstVisibleItemIndex,
                        offset = gridState.firstVisibleItemScrollOffset,
                    )
                }
            },
        resetKey = resetKey,
    )

@Composable
fun rememberAutoHidingTopBarVisible(
    gridState: LazyStaggeredGridState,
    resetKey: Any? = Unit,
): Boolean =
    rememberAutoHidingTopBarVisible(
        positionProvider =
            remember(gridState) {
                TopBarPositionProvider {
                    TopBarScrollPosition(
                        index = gridState.firstVisibleItemIndex,
                        offset = gridState.firstVisibleItemScrollOffset,
                    )
                }
            },
        resetKey = resetKey,
    )

private data class TopBarScrollPosition(
    val index: Int,
    val offset: Int,
) {
    companion object {
        val Initial = TopBarScrollPosition(index = 0, offset = 0)
    }
}

private fun interface TopBarPositionProvider {
    fun currentPosition(): TopBarScrollPosition
}

@Composable
private fun rememberAutoHidingTopBarVisible(
    positionProvider: TopBarPositionProvider,
    resetKey: Any?,
): Boolean {
    var visible by remember { mutableStateOf(true) }
    var lastPosition by remember { mutableStateOf(TopBarScrollPosition.Initial) }

    LaunchedEffect(resetKey) {
        visible = true
        lastPosition = TopBarScrollPosition.Initial
    }

    LaunchedEffect(positionProvider) {
        snapshotFlow { positionProvider.currentPosition() }
            .collect { position ->
                visible =
                    reduceAutoHidingTopBarVisibility(
                        lastPosition = lastPosition,
                        currentPosition = position,
                    )
                lastPosition = position
            }
    }

    return visible
}

internal fun reduceAutoHidingTopBarVisibility(
    lastPosition: Pair<Int, Int>,
    currentPosition: Pair<Int, Int>,
): Boolean =
    reduceAutoHidingTopBarVisibility(
        lastPosition = TopBarScrollPosition(lastPosition.first, lastPosition.second),
        currentPosition = TopBarScrollPosition(currentPosition.first, currentPosition.second),
    )

private fun reduceAutoHidingTopBarVisibility(
    lastPosition: TopBarScrollPosition,
    currentPosition: TopBarScrollPosition,
): Boolean {
    val scrollingForward =
        currentPosition.index > lastPosition.index ||
            (currentPosition.index == lastPosition.index && currentPosition.offset > lastPosition.offset)
    return if (currentPosition.index == 0 && currentPosition.offset == 0) {
        true
    } else {
        !scrollingForward
    }
}

// Total height of the auto-hiding topBar (status bar + the min-touch-target row).
// Use this to rest edge-to-edge content (e.g. the featured carousel) flush below
// the bar with no extra gap.
@Composable
fun appTopBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        Dimensions.minTouchTarget

// topBar height plus the screen-padding gap; use for normal content so it doesn't
// sit flush against the bar.
@Composable
fun appTopBarContentPadding(): Dp = appTopBarHeight() + Dimensions.screenPadding

@Composable
fun appNavigationBarContentPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        Dimensions.screenPadding

private const val APP_TOP_BAR_BACKGROUND_ALPHA = 0.78f
