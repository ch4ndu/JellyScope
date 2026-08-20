// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.ticksToMilliseconds
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlin.time.Duration.Companion.seconds

internal data class DesiredWatchNextItem(
    val item: MediaItem,
    val watchNextType: Int,
)

@SuppressLint("RestrictedApi")
internal suspend fun DesiredWatchNextItem.toProgram(
    context: Context,
    session: Session,
    posterCache: WatchNextPosterCache,
    stagingDirectory: java.io.File,
): DesiredWatchNextProgram {
    val stagedPoster = posterCache.stagePoster(session, item, stagingDirectory)
    val engagementTime =
        when (watchNextType) {
            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW ->
                item.dateCreated?.toEpochMilliseconds() ?: System.currentTimeMillis()
            else -> System.currentTimeMillis()
        }
    val builder =
        WatchNextProgram
            .Builder()
            .setWatchNextType(watchNextType)
            .setInternalProviderId(WatchNextContract.accountPayload(session.accountIdentity(), item.id))
            .setType(item.previewProgramType())
            .setTitle(item.programTitle())
            .setIntentUri(
                Uri.parse(
                    WatchNextContract
                        .launchIntent(context, session.accountIdentity(), item.id)
                        .toUri(Intent.URI_INTENT_SCHEME),
                ),
            ).setLastEngagementTimeUtcMillis(engagementTime)
            .setPosterArtAspectRatio(item.posterAspectRatio())

    item.overview
        ?.stripHtml()
        ?.takeIf { overview -> overview.isNotBlank() }
        ?.let { overview -> builder.setDescription(overview) }
    item.runtime
        ?.inWholeMilliseconds
        ?.takeIf { durationMillis -> durationMillis > 0L }
        ?.let { durationMillis -> builder.setDurationMillis(durationMillis.toInt()) }
    item.playbackPositionTicks
        ?.takeIf { ticks -> watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && ticks > 0L }
        ?.let { ticks -> builder.setLastPlaybackPositionMillis(ticksToMilliseconds(ticks).toInt()) }
    stagedPoster?.uri?.let { uri -> builder.setPosterArtUri(uri) }

    if (item.kind == MediaKind.Episode) {
        builder.setEpisodeTitle(item.name)
        item.parentIndexNumber?.let { seasonNumber -> builder.setSeasonNumber(seasonNumber) }
        item.indexNumber?.let { episodeNumber -> builder.setEpisodeNumber(episodeNumber) }
    }

    return DesiredWatchNextProgram(
        itemId = item.id,
        contentValues = builder.build().toContentValues(),
        stagedPoster = stagedPoster,
    )
}

internal data class DesiredWatchNextProgram(
    val itemId: String,
    val contentValues: ContentValues,
    val stagedPoster: StagedPoster? = null,
)

@SuppressLint("RestrictedApi")
internal fun DesiredWatchNextProgram.contentValuesForReconciliationUpdate(existingProgram: WatchNextProgram): ContentValues =
    ContentValues(contentValues).apply {
        val existingEngagementTime = existingProgram.lastEngagementTimeUtcMillis
        if (existingEngagementTime >= 0L) {
            put(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, existingEngagementTime)
        } else {
            remove(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
        }
    }

internal data class PosterImageSource(
    val type: JellyfinImageType,
    val tag: String,
    val maxWidth: Int,
)

internal object WatchNextSupport {
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        val packageManager = context.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return false
        }

        return packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
    }
}

@SuppressLint("RestrictedApi")
internal fun MediaItem.previewProgramType(): Int =
    when (kind) {
        MediaKind.Movie -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        MediaKind.Episode -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        MediaKind.Series, MediaKind.Other -> TvContractCompat.PreviewPrograms.TYPE_CHANNEL
    }

@SuppressLint("RestrictedApi")
internal fun MediaItem.posterAspectRatio(): Int =
    if (kind == MediaKind.Episode) {
        TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
    } else {
        TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER
    }

@SuppressLint("RestrictedApi")
internal fun MediaItem.nextEpisodeWatchNextType(): Int =
    if (kind == MediaKind.Episode && indexNumber == FIRST_EPISODE_INDEX) {
        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
    } else {
        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
    }

internal fun MediaItem.programTitle(): String =
    if (kind == MediaKind.Episode) {
        seriesName ?: name
    } else {
        name
    }

internal fun String.stripHtml(): String =
    replace(HTML_TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

internal val SESSION_RESTORE_TIMEOUT = 10.seconds
internal val HTTP_SUCCESS_STATUS_RANGE = 200..299
private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val WHITESPACE_REGEX = Regex("\\s+")
internal const val MAX_WATCH_NEXT_ITEMS = 20
internal const val FIRST_EPISODE_INDEX = 1
internal const val POSTER_WIDTH_PX = 300
internal const val POSTER_HEIGHT_PX = 450
internal const val THUMB_WIDTH_PX = 480
internal const val THUMB_HEIGHT_PX = 270
internal const val POSTER_JPEG_QUALITY = 90
internal val watchNextLogger = diagnosticLogger(DiagnosticTag.WatchNextSyncWorker)
