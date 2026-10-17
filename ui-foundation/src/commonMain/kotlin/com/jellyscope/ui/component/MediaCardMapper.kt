// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.formattedResumePosition
import kotlin.math.roundToInt

fun MediaItem.toMediaCardUi(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
): MediaCardUi {
    val progress = playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)
    val primaryTag = imageRefs.primaryTag
    val year = productionYear?.toString() ?: dateCreated?.toString()?.take(4)
    val genresLine = genres.takeIf { values -> values.isNotEmpty() }?.joinToString(", ")
    val subtitle = subtitle()
    val runtimeText =
        runtime
            ?.takeIf { duration -> duration.isPositive() }
            ?.let { duration -> formattedResumePosition(duration) }
    val metadataLine =
        listOfNotNull(subtitle, year, runtimeText)
            .takeIf { parts -> parts.isNotEmpty() }
            ?.joinToString(" · ")

    return MediaCardUi(
        id = id,
        title = name,
        subtitle = subtitle,
        year = year,
        seriesName = seriesName,
        episodeLabel = episodeLabel,
        runtimeText = runtimeText,
        metadataLine = metadataLine,
        heroMetadataLine =
            listOfNotNull(
                communityRating?.let(::heroRatingText),
                year,
                officialRating?.takeIf { value -> value.isNotBlank() },
                genresLine,
            ).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" · "),
        overview = overview,
        genres = genres,
        genresLine = genresLine,
        progressFraction = progress?.takeIf { it > 0f && it < 1f },
        resumePositionTicks = playbackPositionTicks?.takeIf { ticks -> ticks > 0L },
        watched = played,
        isFavorite = isFavorite,
        unplayedCount = unplayedItemCount,
        imageUrl =
            primaryTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Primary,
                    tag = tag,
                    maxWidth = MEDIA_CARD_PRIMARY_MAX_WIDTH,
                )
            },
        // The same image as the URL above, unresolved. Cards rebuild it at the
        // width they are actually going to decode at; the fixed-width URL stays as
        // the fallback for anything that has not adopted the descriptor.
        imageRef =
            primaryTag?.let { tag ->
                MediaImageRef(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Primary,
                    tag = tag,
                )
            },
        backdropUrl =
            (imageRefs.backdropTag ?: primaryTag)?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type =
                        if (imageRefs.backdropTag != null) {
                            JellyfinImageType.Backdrop
                        } else {
                            JellyfinImageType.Primary
                        },
                    tag = tag,
                    maxWidth = MEDIA_CARD_BACKDROP_MAX_WIDTH,
                )
            },
        kind =
            when (kind) {
                MediaKind.Movie -> MediaCardKind.Movie
                MediaKind.Series -> MediaCardKind.Series
                MediaKind.Episode -> MediaCardKind.Episode
                MediaKind.Other -> MediaCardKind.Other
            },
    )
}

private fun MediaItem.subtitle(): String? =
    when {
        seriesName != null && episodeLabel != null -> "$seriesName $episodeLabel"
        seriesName != null -> seriesName
        episodeLabel != null -> episodeLabel
        dateCreated != null -> dateCreated.toString().take(4)
        else -> null
    }

private fun heroRatingText(rating: Double): String {
    val rounded = ((rating * 10).roundToInt() / 10.0).trimTrailingZero()
    return "★ $rounded"
}

private fun Double.trimTrailingZero(): String =
    if (this % 1.0 == 0.0) {
        toInt().toString()
    } else {
        toString()
    }

private const val MEDIA_CARD_PRIMARY_MAX_WIDTH = 300
private const val MEDIA_CARD_BACKDROP_MAX_WIDTH = 1280
