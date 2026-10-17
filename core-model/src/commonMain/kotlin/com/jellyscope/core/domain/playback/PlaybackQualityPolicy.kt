// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Product-level quality intent. This type never contains the wire sentinel. */
enum class PlaybackQualityMode {
    Auto,
    Original,
    Fixed,
}

/**
 * A normalized quality policy. Only [PlaybackQualityMode.Fixed] may carry a
 * bitrate, and that bitrate is always a positive integer-safe value.
 */
data class PlaybackQualityPolicy(
    val mode: PlaybackQualityMode = PlaybackQualityMode.Auto,
    val maxBitrateBps: Long? = null,
) {
    fun normalized(): PlaybackQualityPolicy =
        when (mode) {
            PlaybackQualityMode.Auto,
            PlaybackQualityMode.Original,
            -> copy(maxBitrateBps = null)

            PlaybackQualityMode.Fixed ->
                maxBitrateBps
                    ?.takeIf { bitrate -> bitrate in 1L..Int.MAX_VALUE.toLong() }
                    ?.let { bitrate -> copy(maxBitrateBps = bitrate) }
                    ?: Auto
        }

    companion object {
        val Auto = PlaybackQualityPolicy(PlaybackQualityMode.Auto)
        val Original = PlaybackQualityPolicy(PlaybackQualityMode.Original)

        fun fixed(maxBitrateBps: Long): PlaybackQualityPolicy = PlaybackQualityPolicy(PlaybackQualityMode.Fixed, maxBitrateBps).normalized()
    }
}

/** Explicit internal wire/planner origin; protocol sentinels stay out of UI policy. */
sealed interface PlaybackBitrateConstraint {
    val bitrateBps: Long?

    data object NoClientLimit : PlaybackBitrateConstraint {
        override val bitrateBps: Long = Int.MAX_VALUE.toLong()
    }

    data class ExactUserLimit(
        override val bitrateBps: Long,
    ) : PlaybackBitrateConstraint {
        init {
            require(bitrateBps in 1L..Int.MAX_VALUE.toLong())
        }

        companion object {
            fun of(bitrateBps: Long): ExactUserLimit? =
                bitrateBps
                    .takeIf { bitrate -> bitrate in 1L..Int.MAX_VALUE.toLong() }
                    ?.let(::ExactUserLimit)
        }
    }

    data class AutoSessionLimit(
        override val bitrateBps: Long,
    ) : PlaybackBitrateConstraint {
        init {
            require(bitrateBps in 1L..Int.MAX_VALUE.toLong())
        }

        companion object {
            fun of(bitrateBps: Long): AutoSessionLimit? =
                bitrateBps
                    .takeIf { bitrate -> bitrate in 1L..Int.MAX_VALUE.toLong() }
                    ?.let(::AutoSessionLimit)
        }
    }
}

enum class PlaybackRecoveryIntent {
    Initial,
    Compatibility,
    Quality,
}

fun PlaybackQualityPolicy.toBitrateConstraint(): PlaybackBitrateConstraint =
    when (val normalized = normalized()) {
        PlaybackQualityPolicy.Auto,
        PlaybackQualityPolicy.Original,
        -> PlaybackBitrateConstraint.NoClientLimit

        else ->
            normalized.maxBitrateBps?.let { bitrate -> PlaybackBitrateConstraint.ExactUserLimit.of(bitrate) }
                ?: PlaybackBitrateConstraint.NoClientLimit
    }

fun PlaybackBitrateConstraint.diagnosticName(): String =
    when (this) {
        PlaybackBitrateConstraint.NoClientLimit -> "NoClientLimit"
        is PlaybackBitrateConstraint.ExactUserLimit -> "ExactUserLimit"
        is PlaybackBitrateConstraint.AutoSessionLimit -> "AutoSessionLimit"
    }

fun Long?.toExactBitrateConstraint(): PlaybackBitrateConstraint =
    this?.let { bitrate -> PlaybackBitrateConstraint.ExactUserLimit.of(bitrate) }
        ?: PlaybackBitrateConstraint.NoClientLimit
