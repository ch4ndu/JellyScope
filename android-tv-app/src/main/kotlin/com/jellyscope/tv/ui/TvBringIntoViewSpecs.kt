// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.tv.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

// Used where explicit navigation owns scrolling.
@Composable
internal fun rememberNoAutoScrollSpec(): BringIntoViewSpec =
    remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = 0f
        }
    }

// Explicitly reveal a section because the parent suppresses automatic scrolling.
@Composable
internal fun rememberScrollIntoViewOnFocusModifier(
    listState: LazyListState,
    index: Int,
): Modifier {
    val scope = rememberCoroutineScope()
    var hadFocus by remember { mutableStateOf(false) }

    return Modifier
        .focusGroup()
        .onFocusChanged { state ->
            if (state.hasFocus && !hadFocus) {
                scope.launch { listState.scrollSectionIntoView(index) }
            }
            hadFocus = state.hasFocus
        }
}

private suspend fun LazyListState.scrollSectionIntoView(index: Int) {
    val info = layoutInfo
    val target = info.visibleItemsInfo.firstOrNull { item -> item.index == index }
    if (target == null) {
        // Unmeasured sections require an indexed jump.
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

// Center-pivot horizontal ribbon scrolling.
@Composable
internal fun rememberMarioBringIntoViewSpec(): BringIntoViewSpec =
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

// Center focused rows while leaving same-row horizontal movement stationary.
@Composable
internal fun rememberRowAwareMarioBringIntoViewSpec(): BringIntoViewSpec =
    remember {
        object : BringIntoViewSpec {
            // Keep pace with held D-pad repeats.
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
                val distance = offset - (containerSize - size) / 2f
                val deadZone = size / 2f
                return if (distance > -deadZone && distance < deadZone) 0f else distance
            }
        }
    }
