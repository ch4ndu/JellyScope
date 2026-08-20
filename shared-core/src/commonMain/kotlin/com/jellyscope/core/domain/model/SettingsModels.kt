// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.isVlcFamily
import com.jellyscope.core.domain.playback.normalizeVlcTranscodeBitrate

enum class AppColorThemeId {
    Ocean,
    Midnight,
    Ember,
}

data class PlaybackPreferences(
    val defaultPlayerBackend: PlayerBackend = PlayerBackend.AVPlayer,
    val defaultQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
    /** Legacy storage/API compatibility; use [defaultQualityPolicy]. */
    val defaultMaxBitrateBps: Long? = null,
    /** Per-server VLC-family default quality; null inherits [defaultQualityPolicy]. */
    val vlcTranscodeMaxBitrateBps: Long? = null,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val autoPlayNext: Boolean = true,
    val stillWatchingPrompt: Boolean = true,
    val playbackWarningsEnabled: Boolean = false,
    val autoPlayNextDelaySeconds: Int = DEFAULT_AUTOPLAY_NEXT_DELAY_SECONDS,
    val introSkip: SegmentSkipPolicy = SegmentSkipPolicy.Ask,
    val outroSkip: SegmentSkipPolicy = SegmentSkipPolicy.Ask,
    val recapSkip: SegmentSkipPolicy = SegmentSkipPolicy.Ask,
    val previewSkip: SegmentSkipPolicy = SegmentSkipPolicy.Ask,
    val commercialSkip: SegmentSkipPolicy = SegmentSkipPolicy.Ask,
) {
    /** Normalizes values loaded from older or externally edited persisted state. */
    fun normalized(): PlaybackPreferences =
        copy(
            defaultQualityPolicy = effectiveDefaultQualityPolicy(),
            defaultMaxBitrateBps = effectiveDefaultQualityPolicy().maxBitrateBps,
            vlcTranscodeMaxBitrateBps = normalizeVlcTranscodeBitrate(vlcTranscodeMaxBitrateBps),
            autoPlayNextDelaySeconds =
                autoPlayNextDelaySeconds.coerceIn(
                    MIN_AUTOPLAY_NEXT_DELAY_SECONDS,
                    MAX_AUTOPLAY_NEXT_DELAY_SECONDS,
                ),
        )

    /** Normalized policy used by new callers while older callers migrate. */
    fun effectiveDefaultQualityPolicy(): PlaybackQualityPolicy {
        val legacyPolicy =
            defaultMaxBitrateBps
                ?.takeIf { bitrate -> bitrate > 0L }
                ?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
        return when {
            legacyPolicy != null -> legacyPolicy
            defaultQualityPolicy.mode == PlaybackQualityMode.Fixed -> defaultQualityPolicy.normalized()
            else -> defaultQualityPolicy.normalized()
        }
    }

    /** Backend-specific default used when the current playback has no session override. */
    fun effectiveDefaultQualityPolicy(backend: PlayerBackend): PlaybackQualityPolicy =
        when {
            backend.isVlcFamily() && vlcTranscodeMaxBitrateBps != null ->
                PlaybackQualityPolicy.fixed(vlcTranscodeMaxBitrateBps)
            else -> effectiveDefaultQualityPolicy()
        }

    fun usesVlcDefaultQuality(backend: PlayerBackend): Boolean = backend.isVlcFamily() && vlcTranscodeMaxBitrateBps != null

    /** Unknown segment types are always ignored; known types follow their setting. */
    fun policyFor(type: MediaSegmentType): SegmentSkipPolicy =
        when (type) {
            MediaSegmentType.Intro -> introSkip
            MediaSegmentType.Outro -> outroSkip
            MediaSegmentType.Recap -> recapSkip
            MediaSegmentType.Preview -> previewSkip
            MediaSegmentType.Commercial -> commercialSkip
            MediaSegmentType.Unknown -> SegmentSkipPolicy.Ignore
        }
}

const val DEFAULT_AUTOPLAY_NEXT_DELAY_SECONDS = 10
const val MIN_AUTOPLAY_NEXT_DELAY_SECONDS = 0
const val MAX_AUTOPLAY_NEXT_DELAY_SECONDS = 60

enum class SegmentSkipPolicy {
    AutoSkip,
    Ask,
    Ignore,
}
