// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleStyle

data class TvOfflinePlaybackUiState(
    val playerInstalled: Boolean = false,
    val playerIdentity: Long = 0L,
    val backend: PlayerBackend = PlayerBackend.VlcKit,
    val phase: TvPlaybackPhase = TvPlaybackPhase.Loading,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val itemId: String = "",
    val mediaSourceId: String? = null,
    val title: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    val chapters: List<TvChapter> = emptyList(),
    val audioTracks: List<TvTrackChoice> = emptyList(),
    val subtitleTracks: List<TvTrackChoice> = emptyList(),
    val playbackSpeed: Float = 1f,
    val playbackSpeedChoices: List<TvPlaybackSpeedChoice> = tvPlaybackSpeedChoices(1f),
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleStyleSupported: Boolean = false,
    val timingControlsAvailable: Boolean = false,
    val diagnostics: List<TvPlaybackDiagnosticRow> = emptyList(),
    val error: PlaybackError? = null,
)
