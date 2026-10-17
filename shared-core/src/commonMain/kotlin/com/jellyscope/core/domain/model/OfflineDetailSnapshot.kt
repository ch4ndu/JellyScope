// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

internal fun MediaItemDetail.toOfflineDetailSnapshot(): OfflineDetailSnapshot =
    OfflineDetailSnapshot(
        overview = overview?.takeIf(String::isNotBlank),
        tagline = taglines.firstOrNull()?.takeIf(String::isNotBlank),
        officialRating = officialRating?.takeIf(String::isNotBlank),
        communityRating = communityRating,
        criticRating = criticRating,
        productionYear = productionYear,
        genres = genres.filter(String::isNotBlank),
        studios = studios.map(Studio::name).filter(String::isNotBlank),
        people =
            people.mapNotNull { person ->
                person.name.takeIf(String::isNotBlank)?.let { name ->
                    OfflinePersonSnapshot(
                        name = name,
                        role = person.role?.takeIf(String::isNotBlank),
                        creditType = person.type.toOfflineCreditType(),
                    )
                }
            },
        externalProviderIds =
            OfflineExternalProviderIds(
                imdbId = imdbId?.takeIf(String::isNotBlank),
                tmdbId = tmdbId?.takeIf(String::isNotBlank),
                tmdbItemType = tmdbItemType?.takeIf(String::isNotBlank),
            ),
    )

internal fun MediaItemDetail.offlineArtworkReferences(seriesDetail: MediaItemDetail?): List<OfflineArtworkReference> {
    val item = item
    val episode = item.kind == MediaKind.Episode
    val seriesItem = seriesDetail?.item

    fun ref(
        role: OfflineArtworkRole,
        owner: MediaItem,
        tag: String?,
    ): OfflineArtworkReference? =
        tag?.takeIf(String::isNotBlank)?.let { imageTag ->
            OfflineArtworkReference(role = role, owningItemId = owner.id, imageTag = imageTag)
        }

    val poster =
        if (episode) {
            seriesItem?.let { parent -> ref(OfflineArtworkRole.Poster, parent, parent.imageRefs.primaryTag) }
                ?: ref(OfflineArtworkRole.Poster, item, item.imageRefs.primaryTag)
        } else {
            ref(OfflineArtworkRole.Poster, item, item.imageRefs.primaryTag)
        }
    val backdrop =
        if (episode) {
            ref(OfflineArtworkRole.Backdrop, item, item.imageRefs.backdropTag)
                ?: seriesItem?.let { parent -> ref(OfflineArtworkRole.Backdrop, parent, parent.imageRefs.backdropTag) }
        } else {
            ref(OfflineArtworkRole.Backdrop, item, item.imageRefs.backdropTag)
        }
    val logo =
        if (episode) {
            seriesItem?.let { parent -> ref(OfflineArtworkRole.Logo, parent, parent.imageRefs.logoTag) }
                ?: ref(OfflineArtworkRole.Logo, item, item.imageRefs.logoTag)
        } else {
            ref(OfflineArtworkRole.Logo, item, item.imageRefs.logoTag)
        }
    return listOfNotNull(poster, backdrop, logo)
}

private fun MediaPersonType.toOfflineCreditType(): OfflinePersonCreditType =
    when (this) {
        MediaPersonType.Actor -> OfflinePersonCreditType.Cast
        MediaPersonType.Director,
        MediaPersonType.Writer,
        MediaPersonType.Producer,
        -> OfflinePersonCreditType.Crew
        MediaPersonType.Other -> OfflinePersonCreditType.Other
    }
