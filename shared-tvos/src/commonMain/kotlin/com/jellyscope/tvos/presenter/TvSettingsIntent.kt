// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy

sealed interface TvSettingsIntent {
    data class SetDefaultMaxBitrate(
        val maxBitrateBps: Long?,
    ) : TvSettingsIntent

    data class SetDefaultQualityPolicy(
        val policy: PlaybackQualityPolicy,
    ) : TvSettingsIntent

    data class SetDefaultQualityChoice(
        val modeName: String,
        val maxBitrateBps: Long?,
    ) : TvSettingsIntent

    data class SetPreferredAudioLanguage(
        val code: String?,
    ) : TvSettingsIntent

    data class SetPreferredSubtitleLanguage(
        val code: String?,
    ) : TvSettingsIntent

    data class SetPlaybackWarningsEnabled(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetAutoPlayNext(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetStillWatchingPrompt(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetAutoPlayNextDelaySeconds(
        val seconds: Int,
    ) : TvSettingsIntent

    data class SetRememberLastLibraryViewEnabled(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetPlayerClockEnabled(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetSegmentPolicy(
        val type: MediaSegmentType,
        val policy: SegmentSkipPolicy,
    ) : TvSettingsIntent

    data class SetDiagnosticCollectionEnabled(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data class SetPlaybackInfoAtStartEnabled(
        val enabled: Boolean,
    ) : TvSettingsIntent

    data object RetryPlaybackInfoAtStart : TvSettingsIntent

    data object RetryLoad : TvSettingsIntent

    data object SendClientLogs : TvSettingsIntent

    data object SendPlaybackTimeline : TvSettingsIntent
}
