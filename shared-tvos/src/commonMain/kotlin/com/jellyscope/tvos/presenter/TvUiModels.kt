// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AuthError
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session

/**
 * Swift-facing error taxonomy. Presenters never expose user-facing strings;
 * the SwiftUI layer maps kinds to localized copy.
 */
enum class TvErrorKind {
    InvalidUrl,
    NotReachable,
    InvalidCredentials,
    QuickConnectExpired,
    QuickConnectUnavailable,
    Server,
    Network,
    Unknown,
}

internal fun Throwable.toTvErrorKind(): TvErrorKind =
    when (this) {
        AuthError.InvalidUrl -> TvErrorKind.InvalidUrl
        AuthError.NotReachable -> TvErrorKind.NotReachable
        AuthError.InvalidCredentials -> TvErrorKind.InvalidCredentials
        AuthError.QuickConnectExpired -> TvErrorKind.QuickConnectExpired
        AuthError.QuickConnectUnavailable -> TvErrorKind.QuickConnectUnavailable
        is AuthError.ServerError -> TvErrorKind.Server
        AuthError.AccountNotFound -> TvErrorKind.Unknown
        else -> TvErrorKind.Network
    }

enum class TvCardKind {
    Movie,
    Series,
    Episode,
    Other,
}

internal fun MediaKind.toTvCardKind(): TvCardKind =
    when (this) {
        MediaKind.Movie -> TvCardKind.Movie
        MediaKind.Series -> TvCardKind.Series
        MediaKind.Episode -> TvCardKind.Episode
        MediaKind.Other -> TvCardKind.Other
    }

/**
 * Flattened, immutable card model for shelves and grids. Everything the Swift
 * layer needs is precomputed here (image URL, labels, resume position) so the
 * UI stays a dumb renderer.
 */
data class TvMediaCard(
    val id: String,
    val title: String,
    val kind: TvCardKind,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val productionYear: Int? = null,
    val imageUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val progressPercent: Double? = null,
    val playbackPositionTicks: Long = 0L,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
)

data class TvItemBadges(
    val resolution: String? = null,
    val audioLayout: String? = null,
    val hasSubtitles: Boolean = false,
    val officialRating: String? = null,
    val communityRating: Double? = null,
)

data class TvRelatedGroup(
    val kind: com.jellyscope.core.domain.model.RelatedGroupKind,
    val label: String? = null,
    val items: List<TvMediaCard> = emptyList(),
)

internal const val CARD_IMAGE_MAX_WIDTH = 400
internal const val BACKDROP_IMAGE_MAX_WIDTH = 1280
internal const val LOGO_IMAGE_MAX_WIDTH = 500

data class TvChapter(
    val name: String,
    val startMs: Long,
)

data class TvTrackChoice(
    val streamIndex: Int,
    val languageCode: String? = null,
    val displayLabel: String? = null,
    val ordinal: Int = 0,
    val selected: Boolean = false,
)

enum class TvQualityDefaultSource {
    PlaybackSettings,
    VlcPlaybackSettings,
}

data class TvQualityChoice(
    val maxBitrateBps: Long? = null,
    val resolutionHeight: Int? = null,
    val isCustom: Boolean = false,
    val selected: Boolean = false,
    val inheritsPlaybackDefault: Boolean = false,
    val defaultSource: TvQualityDefaultSource? = null,
    val mode: com.jellyscope.core.domain.playback.PlaybackQualityMode =
        if (maxBitrateBps == null) {
            com.jellyscope.core.domain.playback.PlaybackQualityMode.Auto
        } else {
            com.jellyscope.core.domain.playback.PlaybackQualityMode.Fixed
        },
)

data class TvActiveSegment(
    val type: com.jellyscope.core.domain.playback.MediaSegmentType,
    val endMs: Long,
    // True when the per-type policy is Ask (Swift shows the skip action);
    // AutoSkip segments surface too while the presenter's skip is in flight.
    val askUser: Boolean,
)

internal fun MediaItem.toTvMediaCard(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
): TvMediaCard =
    TvMediaCard(
        id = id,
        title = name,
        kind = kind.toTvCardKind(),
        seriesId = seriesId,
        seriesName = seriesName,
        episodeLabel = episodeLabel,
        productionYear = productionYear,
        imageUrl =
            imageRefs.primaryTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Primary,
                    tag = tag,
                    maxWidth = CARD_IMAGE_MAX_WIDTH,
                )
            },
        backdropUrl =
            imageRefs.backdropTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Backdrop,
                    tag = tag,
                    maxWidth = BACKDROP_IMAGE_MAX_WIDTH,
                )
            },
        logoUrl =
            imageRefs.logoTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Logo,
                    tag = tag,
                    maxWidth = LOGO_IMAGE_MAX_WIDTH,
                )
            },
        progressPercent = playedPercentage,
        playbackPositionTicks = playbackPositionTicks ?: 0L,
        played = played,
        isFavorite = isFavorite,
    )
