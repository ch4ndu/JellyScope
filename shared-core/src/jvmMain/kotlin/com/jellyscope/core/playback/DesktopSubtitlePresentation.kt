// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

/** mpv's native/default bottom margin, before any bottom player chrome offset. */
internal const val MPV_SUBTITLE_BASELINE_MARGIN_PX = MPV_DEFAULT_SUBTITLE_BOTTOM_MARGIN_PX
internal const val MPV_SUBTITLE_BASE_FONT_SIZE_PX = 28.5

/** Fixed scaled-pixel clearance retained while the bottom player chrome is active. */
internal const val MPV_SUBTITLE_CONTROLS_OFFSET_PX = 146

/**
 * Resolves the total mpv subtitle margin. VLC does not use this policy and
 * keeps its native subtitle placement.
 */
internal fun resolveMpvSubtitleBottomMargin(clearanceActive: Boolean): Int =
    MPV_SUBTITLE_BASELINE_MARGIN_PX +
        if (clearanceActive) MPV_SUBTITLE_CONTROLS_OFFSET_PX else 0
