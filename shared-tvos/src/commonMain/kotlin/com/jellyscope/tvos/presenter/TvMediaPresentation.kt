// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session

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
            imageUrlBuilder.build(
                serverUrl = session.serverUrl,
                itemId = id,
                type = JellyfinImageType.Logo,
                tag = imageRefs.logoTag,
                maxWidth = LOGO_IMAGE_MAX_WIDTH,
            ),
        overview = overview,
        officialRating = officialRating,
        communityRating = communityRating,
        runtimeMinutes = runtime?.inWholeMinutes,
        progressPercent = playedPercentage,
        playbackPositionTicks = playbackPositionTicks ?: 0L,
        played = played,
        isFavorite = isFavorite,
    )
