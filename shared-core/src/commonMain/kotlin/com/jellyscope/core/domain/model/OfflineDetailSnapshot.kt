// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/** Credential-free detail facts retained for an explicitly downloaded item. */
data class OfflineDetailSnapshot(
    val overview: String? = null,
    val tagline: String? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val productionYear: Int? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<OfflinePersonSnapshot> = emptyList(),
    val externalProviderIds: OfflineExternalProviderIds = OfflineExternalProviderIds(),
)

data class OfflinePersonSnapshot(
    val name: String,
    val role: String? = null,
    val creditType: OfflinePersonCreditType = OfflinePersonCreditType.Other,
) {
    init {
        require(name.isNotBlank()) { "Offline person name must not be blank." }
    }
}

enum class OfflinePersonCreditType {
    Cast,
    Crew,
    Other,
}

data class OfflineExternalProviderIds(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tmdbItemType: String? = null,
)

/** The fixed local presentation roles. Their source reference is never a URL or a local path. */
enum class OfflineArtworkRole {
    Poster,
    Backdrop,
    Logo,
}

data class OfflineArtworkReference(
    val role: OfflineArtworkRole,
    val owningItemId: String,
    val imageTag: String,
) {
    init {
        require(owningItemId.isNotBlank()) { "Artwork owning item id must not be blank." }
        require(imageTag.isNotBlank()) { "Artwork image tag must not be blank." }
    }
}

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
