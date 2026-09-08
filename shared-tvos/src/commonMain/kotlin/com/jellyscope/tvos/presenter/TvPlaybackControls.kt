// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.MAX_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.MIN_PLAYBACK_SPEED
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

internal val tvPlaybackSpeedValues =
    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        .filter { speed -> speed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED }

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
