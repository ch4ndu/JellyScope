// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PLAYBACK_SPEED_CHOICES
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle

data class TvPlaybackSpeedChoice(
    val speed: Float,
    val selected: Boolean,
)

enum class TvPlaybackDiagnosticField {
    Backend,
    PlayMethod,
    Status,
    RuntimeFormat,
    DroppedFrames,
    Bandwidth,
    BufferedAhead,
    Rebuffers,
    AudioUnderruns,
}

data class TvPlaybackDiagnosticRow(
    val field: TvPlaybackDiagnosticField,
    val value: String?,
)

internal val tvPlaybackSpeedValues = PLAYBACK_SPEED_CHOICES

internal fun tvPlaybackSpeedChoices(selectedSpeed: Float): List<TvPlaybackSpeedChoice> =
    tvPlaybackSpeedValues.map { speed ->
        TvPlaybackSpeedChoice(
            speed = speed,
            selected = speed == selectedSpeed,
        )
    }

internal fun subtitleEdgeStyle(name: String): SubtitleEdgeStyle =
    when (name) {
        SubtitleEdgeStyle.Outline.name -> SubtitleEdgeStyle.Outline
        SubtitleEdgeStyle.DropShadow.name -> SubtitleEdgeStyle.DropShadow
        else -> SubtitleEdgeStyle.None
    }
