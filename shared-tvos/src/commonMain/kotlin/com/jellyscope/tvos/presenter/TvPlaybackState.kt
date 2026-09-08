// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleStyle

enum class TvPlaybackPhase {
    Loading,
    Active,
    Failed,
    Completed,
}

enum class TvPlaybackNoticeKind {
    LocalSubtitleUnavailable,
}

data class TvPlaybackNotice(
    val token: Long,
    val kind: TvPlaybackNoticeKind,
)

data class TvPlaybackUiState(
    val playerInstalled: Boolean = false,
    val playerIdentity: Long = 0L,
    val backend: PlayerBackend = PlayerBackend.Auto,
    val phase: TvPlaybackPhase = TvPlaybackPhase.Loading,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    val isTranscode: Boolean = false,
    val itemId: String = "",
    val mediaSourceId: String? = null,
    val planEpoch: Long = 0L,
    val prepareEpoch: Long = 0L,
    val title: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val artworkUrl: String? = null,
    val chapters: List<TvChapter> = emptyList(),
    val audioTracks: List<TvTrackChoice> = emptyList(),
    val subtitleTracks: List<TvTrackChoice> = emptyList(),
    val localSubtitleSelected: Boolean = false,
    val qualityChoices: List<TvQualityChoice> = emptyList(),
    val inheritedQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
    val inheritedQualityResolutionHeight: Int? = null,
    val inheritedQualityUsesVlcSetting: Boolean = false,
    val activeSegment: TvActiveSegment? = null,
    val nextEpisode: TvMediaCard? = null,
    val upNextVisible: Boolean = false,
    val queue: TvPlaybackQueueState = TvPlaybackQueueState(),
    val nextUpCountdownSeconds: Int? = null,
    val nextUpDismissed: Boolean = false,
    val stillWatchingVisible: Boolean = false,
    val playbackSpeed: Float = 1f,
    val playbackSpeedChoices: List<TvPlaybackSpeedChoice> = tvPlaybackSpeedChoices(1f),
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleStyleSupported: Boolean = false,
    val timingControlsAvailable: Boolean = false,
    val diagnostics: List<TvPlaybackDiagnosticRow> = emptyList(),
    val playbackGuidance: PlaybackHealthGuidance? = null,
    val playbackActionNotice: PlaybackActionNotice? = null,
    val playbackActions: List<PlaybackAction> = emptyList(),
    val subtitleNotice: TvPlaybackNotice? = null,
    val preferences: PlaybackPreferences = PlaybackPreferences(),
    val error: PlaybackError? = null,
) {
    val keepsPlayerMounted: Boolean
        get() =
            nextUpCountdownSeconds != null ||
                stillWatchingVisible ||
                (status == PlaybackStatus.Completed && queue.isPending)
}

internal fun TvPlaybackUiState.hasSwiftVisibleChange(current: TvPlaybackUiState): Boolean =
    copy(
        positionMs = current.positionMs,
        bufferedPositionMs = current.bufferedPositionMs,
    ) != current
