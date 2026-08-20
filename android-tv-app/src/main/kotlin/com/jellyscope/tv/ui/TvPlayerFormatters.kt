// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.player.PlayerMediaMetadata
import com.jellyscope.ui.screen.player.PlayerUiState

@Composable
internal fun metadataLine(metadata: PlayerMediaMetadata): String {
    val year = metadata.productionYear?.toString()
    val runtime = metadata.runtimeMs?.let { milliseconds -> formatRuntime(milliseconds) }
    val yearRuntime =
        when {
            year != null && runtime != null -> stringResource(R.string.tv_metadata_separator, year, runtime)
            year != null -> year
            runtime != null -> runtime
            else -> ""
        }
    // Episodes lead with "Series · S1E1" (mirrors the detail header + Home hero),
    // e.g. "Attack on Titan · S3E2 - 2018 - 23 min". Falls back to whichever
    // half exists.
    val seriesName = metadata.seriesName
    val episodeLabel = metadata.episodeLabel
    val episode =
        when {
            seriesName != null && episodeLabel != null -> "$seriesName · $episodeLabel"
            seriesName != null -> seriesName
            else -> episodeLabel
        }
    return when {
        episode != null && yearRuntime.isNotEmpty() ->
            stringResource(R.string.tv_metadata_separator, episode, yearRuntime)
        episode != null -> episode
        else -> yearRuntime
    }
}

@Composable
private fun formatRuntime(milliseconds: Long): String {
    val totalMinutes = (milliseconds / MILLIS_PER_MINUTE).coerceAtLeast(0L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0L) {
        stringResource(R.string.tv_runtime_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.tv_runtime_minutes, minutes)
    }
}

internal fun remainingMs(playbackState: PlaybackState): Long? =
    playbackState.durationMs?.let { duration ->
        (duration - playbackState.positionMs).coerceAtLeast(0L)
    }

internal fun PlaybackState.progressFraction(): Float =
    durationMs
        ?.takeIf { duration -> duration > 0L }
        ?.let { duration -> positionMs.toFloat() / duration.toFloat() }
        ?: 0f

internal fun PlayerUiState.Content.currentItemId(fallback: String): String {
    val playlist = playlist ?: return fallback
    return playlist.items
        .getOrNull(playlist.currentIndex)
        ?.id
        ?: fallback
}

internal fun MediaSegment.skipAction(): SkipSegmentAction? =
    when (type) {
        MediaSegmentType.Intro,
        MediaSegmentType.Recap,
        -> SkipSegmentAction.Intro
        MediaSegmentType.Outro,
        MediaSegmentType.Preview,
        -> SkipSegmentAction.Credits
        // Commercial gets the shared player's generic Skip treatment; policy
        // filtering (skipPromptSegment) decides visibility, this maps labels.
        MediaSegmentType.Commercial -> SkipSegmentAction.Generic
        MediaSegmentType.Unknown -> null
    }

internal fun TrickplayInfo.frameForPositionMs(positionMs: Long): TrickplayFrame? {
    if (intervalMs <= 0L || thumbnailCount <= 0 || tileWidth <= 0 || tileHeight <= 0) {
        return null
    }

    val thumbnailIndex =
        positionMs
            .coerceAtLeast(0L)
            .div(intervalMs)
            .coerceAtMost((thumbnailCount - 1).toLong())
            .toInt()
    val tileIndex = tileIndexForPositionMs(positionMs)
    val localIndex = thumbnailIndex % thumbnailsPerTile
    return TrickplayFrame(
        tileIndex = tileIndex,
        column = localIndex % tileWidth,
        row = localIndex / tileWidth,
    )
}

internal fun Long.countdownSeconds(): Int =
    ((coerceAtLeast(0L) + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND)
        .coerceAtLeast(1L)
        .toInt()

@Composable
internal fun Chapter.displayTitle(index: Int): String =
    name.takeIf { title -> title.isNotBlank() }
        ?: stringResource(R.string.tv_chapter_fallback, index + 1)

@Composable
internal fun formatDuration(milliseconds: Long?): String {
    if (milliseconds == null) {
        return stringResource(R.string.tv_time_unknown)
    }

    val totalSeconds = milliseconds.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0L) {
        "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "$minutes:${seconds.twoDigits()}"
    }
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

internal enum class SkipSegmentAction(
    val labelRes: Int,
) {
    Intro(R.string.tv_skip_intro),
    Credits(R.string.tv_skip_credits),
    Generic(R.string.tv_skip_segment),
}

internal data class TrickplayFrame(
    val tileIndex: Int,
    val column: Int,
    val row: Int,
)

internal const val PLAYER_OVERLAY_HIDE_MS = 5_000L
internal const val AUDIO_UNAVAILABLE_NOTICE_MS = 6_000L
internal const val SUBTITLE_UNAVAILABLE_NOTICE_MS = 6_000L
internal const val UP_NEXT_COUNTDOWN_TICK_MS = 1_000L
internal const val QUEUE_SLIDE_MS = 260
internal const val TV_DEBUG_OVERLAY_ALPHA = 0.75f
internal const val PLAYER_NOTICE_FOCUS_HANDOFF_DELAY_MS = 50L
internal const val MILLIS_PER_SECOND = 1_000L
internal const val SECONDS_PER_MINUTE = 60L
internal const val SECONDS_PER_HOUR = 3_600L
internal const val MINUTES_PER_HOUR = 60L
