// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle

internal enum class TvPlayerLocalMenu {
    None,
    Chapters,
    Speed,
    SubtitleStyle,
    Resize,
}

internal data class SubtitleSizeChoice(
    val scale: Float,
    val labelRes: Int,
)

internal data class SubtitleColorChoice(
    val value: String?,
    val labelRes: Int,
)

internal data class SubtitleEdgeChoice(
    val edgeStyle: SubtitleEdgeStyle,
    val labelRes: Int,
)
