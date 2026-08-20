// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.jellyscope.ui.screen.detail.requestFocusSafely
import kotlinx.coroutines.delay
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner

@Composable
fun TvLoadingFocusPark(
    focusRequester: FocusRequester,
    onRequestRailFocus: () -> Boolean,
    modifier: Modifier = Modifier,
    requestFocusOnAttach: Boolean = true,
) {
    LaunchedEffect(focusRequester, requestFocusOnAttach) {
        if (requestFocusOnAttach) {
            requestTvFocusWithRetry { focusRequester.requestFocusSafely() }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TvSpinner()
        Box(
            modifier =
                Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event -> handleParkedLoadingKey(event, onRequestRailFocus) }
                    .focusable()
                    .clearAndSetSemantics {},
        )
    }
}

internal suspend fun requestTvFocusWithRetry(
    attempts: Int = TV_FOCUS_REQUEST_ATTEMPTS,
    requestFocus: () -> Boolean,
): Boolean {
    withFrameNanos { }
    repeat(attempts) { attempt ->
        if (requestFocus()) {
            return true
        }
        if (attempt < attempts - 1) {
            delay(TV_FOCUS_REQUEST_RETRY_DELAY_MS)
        }
    }
    return false
}

private fun handleParkedLoadingKey(
    event: KeyEvent,
    onRequestRailFocus: () -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    return when (event.key) {
        Key.DirectionLeft -> {
            onRequestRailFocus()
            true
        }
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.Spacebar,
        Key.MediaPlay,
        Key.MediaPlayPause,
        -> true
        else -> false
    }
}

private const val TV_FOCUS_REQUEST_ATTEMPTS = 4
private const val TV_FOCUS_REQUEST_RETRY_DELAY_MS = 50L
