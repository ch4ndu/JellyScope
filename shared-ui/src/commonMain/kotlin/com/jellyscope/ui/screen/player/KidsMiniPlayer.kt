// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.kids_close_player
import com.jellyscope.ui.generated.resources.kids_restore_player
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min

/**
 * Docked corner and drag offset for the Kids mini-player. Positions are resolved during
 * placement so dragging and settling never recompose the browsing screen underneath.
 */
@Stable
internal class KidsMiniPlayerState(
    private val scope: CoroutineScope,
) {
    // First minimization docks bottom-right; later minimizations keep the chosen corner.
    private var dockedRight by mutableStateOf(true)
    private var dockedBottom by mutableStateOf(true)
    private var dragging by mutableStateOf(false)
    private var dragOffset by mutableStateOf(Offset.Zero)
    private val settlingOffset = Animatable(Offset.Zero, Offset.VectorConverter)
    private var settlingJob: Job? = null

    // Last placed geometry; plain fields so placement writes never invalidate.
    private var placedBounds = Rect.Zero
    private var placedSize = Size.Zero

    /** Placement-time top-left, clamped so rotation and keyboard changes keep the window inside. */
    fun topLeft(
        bounds: Rect,
        size: Size,
    ): Offset {
        placedBounds = bounds
        placedSize = size
        val offset = if (dragging) dragOffset else settlingOffset.value
        return clamp(anchor(bounds, size) + offset, bounds, size)
    }

    fun drag(delta: Offset) {
        if (!dragging) {
            settlingJob?.cancel()
            dragOffset = settlingOffset.value
            dragging = true
        }
        val anchor = anchor(placedBounds, placedSize)
        dragOffset = clamp(anchor + dragOffset + delta, placedBounds, placedSize) - anchor
    }

    /** Snaps to the nearest safe corner; cancelled drags settle the same way. */
    fun settle() {
        if (!dragging) return
        val released = clamp(anchor(placedBounds, placedSize) + dragOffset, placedBounds, placedSize)
        dockedRight = released.x + placedSize.width / 2f >= placedBounds.center.x
        dockedBottom = released.y + placedSize.height / 2f >= placedBounds.center.y
        val releasedOffset = released - anchor(placedBounds, placedSize)
        settlingJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settlingOffset.snapTo(releasedOffset)
                dragging = false
                settlingOffset.animateTo(Offset.Zero, tween(PLAYER_LAYOUT_ANIMATION_MS))
            }
    }

    private fun anchor(
        bounds: Rect,
        size: Size,
    ): Offset =
        Offset(
            x = if (dockedRight) bounds.right - size.width else bounds.left,
            y = if (dockedBottom) bounds.bottom - size.height else bounds.top,
        )

    private fun clamp(
        position: Offset,
        bounds: Rect,
        size: Size,
    ): Offset =
        Offset(
            x = position.x.coerceIn(bounds.left, max(bounds.left, bounds.right - size.width)),
            y = position.y.coerceIn(bounds.top, max(bounds.top, bounds.bottom - size.height)),
        )
}

/**
 * Usable mini-player rectangle in window pixels. The navigation rail, bottom bar and safe
 * insets (including the keyboard) overlap at the same edges, so each edge takes the largest.
 */
internal fun Density.kidsMiniPlayerBounds(
    width: Int,
    height: Int,
    safeDrawing: WindowInsets,
    layoutDirection: LayoutDirection,
    railWidth: Dp,
    bottomPadding: Dp,
): Rect {
    val railPx = railWidth.toPx()
    val safeLeft = safeDrawing.getLeft(this, layoutDirection).toFloat()
    val safeRight = safeDrawing.getRight(this, layoutDirection).toFloat()
    val leftInset = if (layoutDirection == LayoutDirection.Ltr) max(safeLeft, railPx) else safeLeft
    val rightInset = if (layoutDirection == LayoutDirection.Rtl) max(safeRight, railPx) else safeRight
    val topInset = safeDrawing.getTop(this).toFloat()
    val bottomInset = max(safeDrawing.getBottom(this).toFloat(), bottomPadding.toPx())
    val margin = Dimensions.kidsMiniPlayerMargin.toPx()
    val left = leftInset + margin
    val top = topInset + margin
    return Rect(
        left = left,
        top = top,
        right = max(left, width - rightInset - margin),
        bottom = max(top, height - bottomInset - margin),
    )
}

/**
 * 16:9 mini-player size. Every window prefers the same fraction of the usable width (softly
 * capped by a fraction of its height), never below the width that fits the play and close
 * targets side by side inside their insets. The usable rectangle is a hard clamp, so only
 * windows too small for that minimum shrink below it; at the narrowest widths the compact
 * buffering spinner may still touch the close inset while touch targets stay full size.
 */
internal fun Density.kidsMiniPlayerSize(bounds: Rect): Size {
    val aspectRatio = Dimensions.videoCardAspectRatio
    val target = Dimensions.minTouchTarget.toPx()
    val inset = Dimensions.contentSpacing.toPx()
    val controlsMinWidth = max(2 * target + 2 * inset, (target + 2 * inset) * aspectRatio)
    val preferredWidth =
        max(
            controlsMinWidth,
            min(
                bounds.width * Dimensions.kidsMiniPlayerWidthFraction,
                bounds.height * Dimensions.kidsMiniPlayerMaxHeightFraction * aspectRatio,
            ),
        )
    val width = preferredWidth.coerceAtMost(min(bounds.width, bounds.height * aspectRatio)).coerceAtLeast(0f)
    return Size(width, width / aspectRatio)
}

/** Mini-player input: tap or accessibility click restores, drag moves, local controls stay on top. */
@Composable
internal fun KidsMiniPlayerOverlay(
    state: KidsMiniPlayerState,
    playbackStateFlow: StateFlow<PlaybackState>,
    showPlaybackControl: Boolean,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val restoreLabel = stringResource(Res.string.kids_restore_player)
    val currentOnRestore by rememberUpdatedState(onRestore)
    // A drag interrupted by restore or a forced watch page still settles into a corner.
    DisposableEffect(state) {
        onDispose { state.settle() }
    }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .semantics {
                    role = Role.Button
                    contentDescription = restoreLabel
                    onClick(label = restoreLabel) {
                        currentOnRestore()
                        true
                    }
                }.pointerInput(state) {
                    detectTapGestures(onTap = { currentOnRestore() })
                }.pointerInput(state) {
                    detectDragGestures(
                        onDragEnd = state::settle,
                        onDragCancel = state::settle,
                    ) { change, dragAmount ->
                        change.consume()
                        state.drag(dragAmount)
                    }
                },
    ) {
        val target = Dimensions.minTouchTarget
        val inset = Dimensions.contentSpacing
        // Centered only while its right edge clears the inset close target; otherwise it moves start-side.
        val centeredControlFits = maxWidth >= target * 3 + inset * 2
        if (showPlaybackControl) {
            KidsPlayPauseControl(
                playbackStateFlow = playbackStateFlow,
                controlsVisible = true,
                onPlay = onPlay,
                onPause = onPause,
                onReplay = onReplay,
                primary = false,
                modifier =
                    if (centeredControlFits) {
                        Modifier.align(Alignment.Center)
                    } else {
                        Modifier.align(Alignment.CenterStart).padding(start = inset)
                    },
            )
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(inset)) {
            KidsControlButton(Icons.Filled.Close, stringResource(Res.string.kids_close_player), onClose)
        }
    }
}
