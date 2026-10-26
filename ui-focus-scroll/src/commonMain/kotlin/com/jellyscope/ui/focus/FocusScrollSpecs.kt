// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.focus

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

data class FocusRestoreRequest(
    val key: String,
    val fallbackSemanticIndex: Int,
)

sealed interface FocusRestoreTarget {
    data class Exact(
        val key: String,
        val semanticIndex: Int,
    ) : FocusRestoreTarget

    data class Fallback(
        val key: String,
        val semanticIndex: Int,
    ) : FocusRestoreTarget

    data object Deferred : FocusRestoreTarget

    data object Unavailable : FocusRestoreTarget
}

fun resolveFocusRestoreTarget(
    request: FocusRestoreRequest,
    semanticKeys: List<String>,
    contentReady: Boolean = true,
): FocusRestoreTarget {
    val exactIndex = semanticKeys.indexOf(request.key)
    if (exactIndex >= 0) {
        return FocusRestoreTarget.Exact(request.key, exactIndex)
    }
    if (!contentReady) {
        return FocusRestoreTarget.Deferred
    }
    if (semanticKeys.isEmpty()) {
        return FocusRestoreTarget.Unavailable
    }
    val fallbackIndex = request.fallbackSemanticIndex.coerceIn(semanticKeys.indices)
    return FocusRestoreTarget.Fallback(semanticKeys[fallbackIndex], fallbackIndex)
}

class RestoreAwareBringIntoViewSpec(
    private val delegate: BringIntoViewSpec,
    private val restoreHandoffActive: () -> Boolean,
) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = delegate.scrollAnimationSpec

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float =
        if (restoreHandoffActive()) {
            0f
        } else {
            delegate.calculateScrollDistance(offset, size, containerSize)
        }
}

@Composable
fun rememberRestoreAwareBringIntoViewSpec(
    delegate: BringIntoViewSpec,
    restoreHandoffActive: Boolean,
): BringIntoViewSpec {
    val currentHandoff by rememberUpdatedState(restoreHandoffActive)
    return remember(delegate) {
        RestoreAwareBringIntoViewSpec(delegate) { currentHandoff }
    }
}

@Composable
fun rememberNoAutoScrollSpec(): BringIntoViewSpec =
    remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = 0f
        }
    }

@Composable
fun rememberScrollIntoViewOnFocusModifier(
    listState: LazyListState,
    index: Int,
    enabled: Boolean = true,
): Modifier {
    val scope = rememberCoroutineScope()
    var hadFocus by remember { mutableStateOf(false) }

    return if (enabled) {
        Modifier
            .focusGroup()
            .onFocusChanged { state ->
                if (state.hasFocus && !hadFocus) {
                    scope.launch { listState.scrollSectionIntoView(index) }
                }
                hadFocus = state.hasFocus
            }
    } else {
        Modifier
    }
}

suspend fun LazyListState.scrollSectionIntoView(index: Int) {
    val info = layoutInfo
    val target = info.visibleItemsInfo.firstOrNull { item -> item.index == index }
    if (target == null) {
        animateScrollToItem(index)
        return
    }
    val itemStart = target.offset
    val itemEnd = target.offset + target.size
    val delta =
        when {
            itemEnd > info.viewportEndOffset -> (itemEnd - info.viewportEndOffset).toFloat()
            itemStart < info.viewportStartOffset -> (itemStart - info.viewportStartOffset).toFloat()
            else -> 0f
        }
    if (delta != 0f) {
        animateScrollBy(delta)
    }
}

@Composable
fun rememberCenterPivotBringIntoViewSpec(): BringIntoViewSpec =
    remember {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                spring(
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.5f,
                )

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = offset - (containerSize - size) / 2f
        }
    }

/**
 * Like [rememberCenterPivotBringIntoViewSpec] but with a snappier,
 * non-overshooting spring and a dead zone: a focused item already within half a
 * card of centre is not nudged. Off-screen / far items still centre when
 * navigating horizontally.
 */
@Composable
fun rememberDeadZoneCenterPivotBringIntoViewSpec(): BringIntoViewSpec =
    remember {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                spring(
                    stiffness = Spring.StiffnessMedium,
                    visibilityThreshold = 0.5f,
                )

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                val toCenter = offset - (containerSize - size) / 2f
                return if (abs(toCenter) <= size / 2f) 0f else toCenter
            }
        }
    }
