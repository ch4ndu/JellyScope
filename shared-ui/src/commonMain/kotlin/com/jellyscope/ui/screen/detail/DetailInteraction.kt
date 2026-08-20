// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent

enum class DetailInteractionMode {
    Dpad,
    Touch,
}

val LocalDetailInteractionMode =
    staticCompositionLocalOf { DetailInteractionMode.Touch }

val LocalDetailFocusZoomEnabled =
    staticCompositionLocalOf { true }

@Composable
internal fun isDetailDpadMode(): Boolean = LocalDetailInteractionMode.current == DetailInteractionMode.Dpad

@Composable
internal fun Modifier.detailFocusGroup(): Modifier =
    if (isDetailDpadMode()) {
        focusGroup()
    } else {
        this
    }

@Composable
internal fun Modifier.detailFocusRestorer(): Modifier =
    if (isDetailDpadMode()) {
        focusRestorer()
    } else {
        this
    }

/**
 * Focus restorer with a deterministic [fallback] child used when nothing has been
 * focused in the group yet (e.g. the first entry after load) or the remembered
 * child is gone — so focus never dead-ends on entry.
 */
@Composable
internal fun Modifier.detailFocusRestorer(fallback: FocusRequester): Modifier =
    if (isDetailDpadMode()) {
        focusRestorer(fallback)
    } else {
        this
    }

@Composable
internal fun Modifier.detailFocusable(enabled: Boolean = true): Modifier =
    if (isDetailDpadMode()) {
        focusable(enabled = enabled)
    } else {
        this
    }

@Composable
internal fun Modifier.detailFocusRequester(focusRequester: FocusRequester?): Modifier =
    if (isDetailDpadMode() && focusRequester != null) {
        focusRequester(focusRequester)
    } else {
        this
    }

@Composable
internal fun Modifier.detailOnFocusChanged(onFocusChanged: (FocusState) -> Unit): Modifier =
    if (isDetailDpadMode()) {
        onFocusChanged(onFocusChanged)
    } else {
        this
    }

@Composable
internal fun Modifier.detailFocusProperties(scope: FocusProperties.() -> Unit): Modifier =
    if (isDetailDpadMode()) {
        focusProperties(scope)
    } else {
        this
    }

@Composable
internal fun Modifier.detailOnPreviewKeyEvent(onPreviewKeyEvent: (KeyEvent) -> Boolean): Modifier =
    if (isDetailDpadMode()) {
        onPreviewKeyEvent(onPreviewKeyEvent)
    } else {
        this
    }

@Composable
internal fun DetailBringIntoViewProvider(
    spec: BringIntoViewSpec,
    content: @Composable () -> Unit,
) {
    if (isDetailDpadMode()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            content()
        }
    } else {
        content()
    }
}
