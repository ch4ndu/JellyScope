// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.tv.R

internal fun subtitleSizeChoices(): List<SubtitleSizeChoice> =
    listOf(
        SubtitleSizeChoice(0.85f, R.string.tv_subtitle_size_small),
        SubtitleSizeChoice(1f, R.string.tv_subtitle_size_normal),
        SubtitleSizeChoice(1.2f, R.string.tv_subtitle_size_large),
    )

internal fun subtitleColorChoices(): List<SubtitleColorChoice> =
    listOf(
        SubtitleColorChoice(null, R.string.tv_subtitle_style_default),
        SubtitleColorChoice("#FFFFFFFF", R.string.tv_subtitle_color_white),
        SubtitleColorChoice("#FFFFEB3B", R.string.tv_subtitle_color_yellow),
        SubtitleColorChoice("#FF22B8D4", R.string.tv_subtitle_color_cyan),
    )

internal fun subtitleBackgroundChoices(): List<SubtitleColorChoice> =
    listOf(
        SubtitleColorChoice(null, R.string.tv_subtitle_background_none),
        SubtitleColorChoice("#CC000000", R.string.tv_subtitle_background_black),
        SubtitleColorChoice("#80000000", R.string.tv_subtitle_background_dim),
    )

internal fun subtitleEdgeChoices(): List<SubtitleEdgeChoice> =
    listOf(
        SubtitleEdgeChoice(SubtitleEdgeStyle.None, R.string.tv_subtitle_edge_none),
        SubtitleEdgeChoice(SubtitleEdgeStyle.Outline, R.string.tv_subtitle_edge_outline),
        SubtitleEdgeChoice(SubtitleEdgeStyle.DropShadow, R.string.tv_subtitle_edge_shadow),
    )
