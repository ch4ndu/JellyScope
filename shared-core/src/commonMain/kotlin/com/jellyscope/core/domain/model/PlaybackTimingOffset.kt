// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

const val PLAYBACK_TIMING_SCHEMA_VERSION = 1

// Widened from 10s: badly muxed sources can need more than a 10s correction.
// No schema bump — every stored value is inside the wider range, and normalized()
// re-clamps on read.
const val PLAYBACK_TIMING_OFFSET_LIMIT_MS = 20_000L

enum class PlaybackTimingKind {
    Audio,
    Subtitle,
}

data class PlaybackTimingKey(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val trackId: String,
    val kind: PlaybackTimingKind,
) {
    init {
        require(serverId.isNotBlank()) { "serverId must not be blank." }
        require(userId.isNotBlank()) { "userId must not be blank." }
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
        require(trackId.isNotBlank()) { "trackId must not be blank." }
    }
}

data class PlaybackTimingOffset(
    val key: PlaybackTimingKey,
    val offsetMs: Long,
    val schemaVersion: Int = PLAYBACK_TIMING_SCHEMA_VERSION,
) {
    fun normalized(): PlaybackTimingOffset =
        copy(
            offsetMs = offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS),
            schemaVersion = PLAYBACK_TIMING_SCHEMA_VERSION,
        )
}

const val PLAYBACK_TIMING_SMALL_STEP_MS = 50L
const val PLAYBACK_TIMING_MEDIUM_STEP_MS = 250L
const val PLAYBACK_TIMING_LARGE_STEP_MS = 1_000L

// Order is the rendered button order on both the shared and Android TV timing
// panels: most-negative first, ascending. Reordering changes the on-screen layout.
fun playbackTimingSteps(supportsNegative: Boolean): List<Long> =
    if (supportsNegative) {
        listOf(
            -PLAYBACK_TIMING_LARGE_STEP_MS,
            -PLAYBACK_TIMING_MEDIUM_STEP_MS,
            -PLAYBACK_TIMING_SMALL_STEP_MS,
            PLAYBACK_TIMING_SMALL_STEP_MS,
            PLAYBACK_TIMING_MEDIUM_STEP_MS,
            PLAYBACK_TIMING_LARGE_STEP_MS,
        )
    } else {
        listOf(
            PLAYBACK_TIMING_SMALL_STEP_MS,
            PLAYBACK_TIMING_MEDIUM_STEP_MS,
            PLAYBACK_TIMING_LARGE_STEP_MS,
        )
    }
