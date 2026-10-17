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
