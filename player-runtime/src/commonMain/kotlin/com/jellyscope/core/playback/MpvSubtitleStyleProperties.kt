// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleStyle

/** Projects shared styling into vendor-free mpv values; each platform supplies its subtitle clearance. */
internal fun SubtitleStyle.toMpvSubtitleProperties(scaledPixelMargin: Int = MPV_DEFAULT_SUBTITLE_BOTTOM_MARGIN_PX): Map<String, String> {
    val background = backgroundColor.toMpvColorOrNull()
    val edgeBackground =
        when {
            background != null -> background
            edgeStyle == SubtitleEdgeStyle.DropShadow -> MPV_DEFAULT_SUBTITLE_EDGE_COLOR
            else -> MPV_TRANSPARENT_COLOR
        }

    return linkedMapOf(
        "sub-ass-override" to "force",
        "sub-margin-y" to scaledPixelMargin.toString(),
        "sub-scale" to fontScale.coerceIn(0.1f, 5f).toString(),
        "sub-color" to (foregroundColor.toMpvColorOrNull() ?: MPV_DEFAULT_SUBTITLE_FOREGROUND),
        "sub-back-color" to edgeBackground,
        "sub-border-color" to MPV_DEFAULT_SUBTITLE_EDGE_COLOR,
        "sub-border-style" to
            if (background != null && edgeStyle == SubtitleEdgeStyle.None) {
                "background-box"
            } else {
                "outline-and-shadow"
            },
        "sub-border-size" to
            when (edgeStyle) {
                SubtitleEdgeStyle.Outline -> "2"
                SubtitleEdgeStyle.None,
                SubtitleEdgeStyle.DropShadow,
                -> "0"
            },
        "sub-shadow-offset" to
            when (edgeStyle) {
                SubtitleEdgeStyle.DropShadow -> "2"
                SubtitleEdgeStyle.None,
                SubtitleEdgeStyle.Outline,
                -> "0"
            },
    )
}

internal fun String?.toMpvColorOrNull(): String? {
    val hex = this?.removePrefix("#") ?: return null
    val normalized =
        when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
    if (!normalized.all { char -> char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' }) {
        return null
    }
    return "#${normalized.uppercase()}"
}

internal const val MPV_DEFAULT_SUBTITLE_BOTTOM_MARGIN_PX = 34
private const val MPV_DEFAULT_SUBTITLE_FOREGROUND = "#FFFFFFFF"
private const val MPV_DEFAULT_SUBTITLE_EDGE_COLOR = "#FF000000"
private const val MPV_TRANSPARENT_COLOR = "#00000000"
