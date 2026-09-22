// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.platform

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class PlatformCapabilities(
    val supportsKidsPlayback: Boolean = false,
    val playerOrientationAnimation: Boolean = false,
    val playerKeyboardShortcuts: Boolean = false,
    val desktopScrollInput: Boolean = false,
    val desktopResizeModes: Boolean = false,
    val desktopPlayerControls: Boolean = false,
    val playerFullscreenControl: Boolean = false,
    /** Null hides the iOS-only Downloads continuation note on other platforms. */
    val backgroundDownloadsAvailable: Boolean? = null,
) {
    companion object {
        val Default = PlatformCapabilities()

        val Mobile =
            PlatformCapabilities(
                supportsKidsPlayback = true,
                playerOrientationAnimation = true,
                playerKeyboardShortcuts = true,
            )

        val Desktop =
            PlatformCapabilities(
                playerKeyboardShortcuts = true,
                desktopScrollInput = true,
                desktopResizeModes = true,
                desktopPlayerControls = true,
                playerFullscreenControl = true,
            )
    }
}

@Immutable
data class FullscreenToggleState(
    val isFullscreen: Boolean = false,
    val toggleFullscreen: () -> Unit = {},
    val exitFullscreen: () -> Unit = {},
)

@Immutable
data class PlayerCursorState(
    val setVisible: (Boolean) -> Unit = {},
)

val LocalPlatformCapabilities = staticCompositionLocalOf { PlatformCapabilities.Default }

val LocalFullscreenToggle = staticCompositionLocalOf { FullscreenToggleState() }

val LocalPlayerCursorState = staticCompositionLocalOf { PlayerCursorState() }
