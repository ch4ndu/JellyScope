// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import kotlin.math.abs

enum class DesktopScrollOrientation {
    Vertical,
    Horizontal,
}

@Composable
fun Modifier.desktopScrollInput(
    state: ScrollableState,
    orientation: DesktopScrollOrientation,
): Modifier {
    if (!LocalPlatformCapabilities.current.desktopScrollInput) {
        return this
    }

    val density = LocalDensity.current
    val dragThresholdPx = with(density) { Dimensions.desktopDragScrollThreshold.toPx() }
    val wheelStepPx = with(density) { Dimensions.desktopWheelScrollStep.toPx() }

    return this
        .desktopShiftWheelScroll(
            state = state,
            orientation = orientation,
            wheelStepPx = wheelStepPx,
        ).desktopDragScroll(
            state = state,
            orientation = orientation,
            dragThresholdPx = dragThresholdPx,
        )
}

private fun Modifier.desktopShiftWheelScroll(
    state: ScrollableState,
    orientation: DesktopScrollOrientation,
    wheelStepPx: Float,
): Modifier =
    pointerInput(state, orientation, wheelStepPx) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (
                    orientation == DesktopScrollOrientation.Horizontal &&
                    event.type == PointerEventType.Scroll &&
                    event.keyboardModifiers.isShiftPressed
                ) {
                    val verticalDelta = event.changes.totalScrollDelta().y
                    if (verticalDelta != 0f) {
                        state.dispatchRawDelta(verticalDelta * wheelStepPx)
                        event.changes.forEach { change -> change.consume() }
                    }
                }
            }
        }
    }

private fun Modifier.desktopDragScroll(
    state: ScrollableState,
    orientation: DesktopScrollOrientation,
    dragThresholdPx: Float,
): Modifier =
    pointerInput(state, orientation, dragThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) {
                return@awaitEachGesture
            }

            var previousPosition = down.position
            // Once the drag passes the threshold we lock it to its dominant axis so
            // a horizontal ribbon drag doesn't also drive the parent vertical scroll
            // (and vice versa). Only the scroller whose orientation matches the
            // dominant axis engages and consumes the gesture.
            var axisDecided = false
            var active = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
                if (!change.pressed) {
                    break
                }

                val totalOffset = change.position - down.position
                if (!axisDecided && totalOffset.getDistance() >= dragThresholdPx) {
                    axisDecided = true
                    val horizontalDominant = abs(totalOffset.x) > abs(totalOffset.y)
                    active =
                        when (orientation) {
                            DesktopScrollOrientation.Horizontal -> horizontalDominant
                            DesktopScrollOrientation.Vertical -> !horizontalDominant
                        }
                }

                if (active) {
                    val dragDelta = change.position - previousPosition
                    val scrollDelta =
                        when (orientation) {
                            DesktopScrollOrientation.Vertical -> -dragDelta.y
                            DesktopScrollOrientation.Horizontal -> -dragDelta.x
                        }
                    if (scrollDelta != 0f) {
                        state.dispatchRawDelta(scrollDelta)
                    }
                    change.consume()
                }

                previousPosition = change.position
            }
        }
    }

private fun List<androidx.compose.ui.input.pointer.PointerInputChange>.totalScrollDelta(): Offset =
    fold(Offset.Zero) { total, change -> total + change.scrollDelta }
