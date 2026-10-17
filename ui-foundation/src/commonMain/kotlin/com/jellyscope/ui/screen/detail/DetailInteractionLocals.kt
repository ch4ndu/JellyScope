// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.runtime.staticCompositionLocalOf

enum class DetailInteractionMode {
    Dpad,
    Touch,
}

val LocalDetailInteractionMode =
    staticCompositionLocalOf { DetailInteractionMode.Touch }

val LocalDetailFocusZoomEnabled =
    staticCompositionLocalOf { true }
