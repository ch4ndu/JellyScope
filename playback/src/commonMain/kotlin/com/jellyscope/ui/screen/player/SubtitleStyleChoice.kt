// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_subtitle_large
import com.jellyscope.ui.generated.resources.player_subtitle_normal
import com.jellyscope.ui.generated.resources.player_subtitle_small
import org.jetbrains.compose.resources.StringResource

internal data class SubtitleStyleChoice(
    val scale: Float,
    val label: StringResource,
)

internal fun subtitleStyleChoices(): List<SubtitleStyleChoice> =
    listOf(
        SubtitleStyleChoice(0.85f, Res.string.player_subtitle_small),
        SubtitleStyleChoice(1f, Res.string.player_subtitle_normal),
        SubtitleStyleChoice(1.2f, Res.string.player_subtitle_large),
    )
