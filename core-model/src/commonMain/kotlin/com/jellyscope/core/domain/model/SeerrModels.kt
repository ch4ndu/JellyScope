// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/** The screen's creating account and epoch, never inferred from a later active account. */
data class SeerrOwner(
    val account: AccountIdentity,
    val boundaryEpoch: Long,
)

/** Validate again immediately before publishing an asynchronous result or navigating. */
data class SeerrPublicationToken(
    val owner: SeerrOwner,
    val connectionGeneration: Long,
)

sealed interface SeerrResult<out T> {
    data class Success<T>(
        val value: T,
        val token: SeerrPublicationToken,
    ) : SeerrResult<T>

    data class Failure(
        val error: SeerrError,
        val token: SeerrPublicationToken? = null,
        val connection: SeerrConnection? = null,
    ) : SeerrResult<Nothing>
}

/** Closed, localizable outcomes. No server text, exceptions, URLs or credentials. */
enum class SeerrError {
    InvalidUrl,
    UnsupportedService,
    SetupRequired,
    HttpsRequired,
    SecurityRejected,
    RedirectRejected,
    InvalidCredentials,
    AccountMismatch,
    AuthenticationRequired,
    SessionExpired,
    VerificationUnavailable,
    QuickConnectUnavailable,
    QuickConnectExpired,
    RestrictedAccount,
    PermissionDenied,
    QuotaUnavailable,
    QuotaExceeded,
    AlreadyRequested,
    AlreadyAvailable,
    Blocklisted,
    MetadataUnavailable,
    InvalidSelection,
    NoSeasonsAvailable,
    RequestInProgress,
    ReconciliationRequired,
    LibraryItemUnavailable,
    RateLimited,
    NetworkUnavailable,
    InvalidResponse,
    PersistenceFailed,
    StaleOwner,
}

enum class SeerrLoginMethod { Jellyfin, Local }

enum class SeerrConnectionPhase { Disconnected, AwaitingLogin, Connected, VerificationRequired }

data class SeerrServiceInfo(
    val endpoint: String,
    val version: String,
    val usesPlainHttp: Boolean,
    val jellyfinLoginEnabled: Boolean,
    val localLoginEnabled: Boolean,
    val quickConnectSupported: Boolean,
    val movie4kEnabled: Boolean,
    val series4kEnabled: Boolean,
    val specialEpisodesEnabled: Boolean,
)

data class SeerrConnection(
    val phase: SeerrConnectionPhase,
    val endpoint: String? = null,
    val user: SeerrUser? = null,
    val service: SeerrServiceInfo? = null,
    val loginMethod: SeerrLoginMethod? = null,
    val unresolvedRequest: SeerrRequestIntent? = null,
)

data class SeerrUser(
    val id: Int,
    val displayName: String,
    val permissions: SeerrPermissions,
)

data class SeerrPermissions(
    val requestMovie: Boolean,
    val requestTv: Boolean,
    val requestMovie4k: Boolean,
    val requestTv4k: Boolean,
    val autoApproveMovie: Boolean,
    val autoApproveTv: Boolean,
    val autoApproveMovie4k: Boolean,
    val autoApproveTv4k: Boolean,
) {
    fun canRequest(
        type: SeerrMediaType,
        quality: SeerrQuality,
    ): Boolean =
        when {
            type == SeerrMediaType.Movie && quality == SeerrQuality.Standard -> requestMovie
            type == SeerrMediaType.Movie -> requestMovie4k
            quality == SeerrQuality.Standard -> requestTv
            else -> requestTv4k
        }

    fun requiresApproval(
        type: SeerrMediaType,
        quality: SeerrQuality,
    ): Boolean =
        !when {
            type == SeerrMediaType.Movie && quality == SeerrQuality.Standard -> autoApproveMovie
            type == SeerrMediaType.Movie -> autoApproveMovie4k
            quality == SeerrQuality.Standard -> autoApproveTv
            else -> autoApproveTv4k
        }
}

/** Only the code is public. The secret remains in the connection's transient data owner. */
data class SeerrQuickConnect(
    val code: String,
    val expiresAtEpochMillis: Long,
)

enum class SeerrMediaType { Movie, Tv }

enum class SeerrQuality { Standard, FourK }

data class SeerrMediaKey(
    val type: SeerrMediaType,
    val tmdbId: Int,
) {
    init {
        require(tmdbId > 0)
    }
}

enum class SeerrCatalog { Trending, Movies, Tv, Search }

enum class SeerrAvailability { Unknown, Pending, Processing, PartiallyAvailable, Available, Blocklisted, Deleted, Unrecognized }

enum class SeerrRequestStatus { Pending, Approved, Declined, Failed, Completed, Unrecognized }

data class SeerrMediaCard(
    val key: SeerrMediaKey,
    val title: String?,
    val year: Int?,
    val posterUrl: String?,
    val standardAvailability: SeerrAvailability,
    val fourKAvailability: SeerrAvailability,
)

/** Discover uses scan status from either quality; explicit lookup remains inclusive. */
fun SeerrMediaCard.isFullyAvailable(): Boolean =
    standardAvailability == SeerrAvailability.Available || fourKAvailability == SeerrAvailability.Available

data class SeerrCastMember(
    val id: Int,
    val creditId: String?,
    val name: String,
    val character: String?,
    val profileUrl: String?,
)

data class SeerrCatalogPage(
    val items: List<SeerrMediaCard>,
    val page: Int,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

enum class SeerrQuotaKind { Unlimited, Limited, Unavailable }

data class SeerrQuota(
    val kind: SeerrQuotaKind,
    val used: Int? = null,
    val limit: Int? = null,
    val remaining: Int? = null,
    val days: Int? = null,
    val restricted: Boolean = false,
) {
    fun allows(units: Int): Boolean =
        units > 0 &&
            !restricted &&
            (kind == SeerrQuotaKind.Unlimited || (kind == SeerrQuotaKind.Limited && (remaining ?: 0) >= units))
}

data class SeerrQuotas(
    val movie: SeerrQuota,
    val tv: SeerrQuota,
)

data class SeerrSeason(
    val number: Int,
    val title: String?,
    val episodeCount: Int,
    val standardAvailability: SeerrAvailability,
    val fourKAvailability: SeerrAvailability,
)

data class SeerrRequestSummary(
    val id: Int,
    val key: SeerrMediaKey,
    val quality: SeerrQuality,
    val status: SeerrRequestStatus,
    val seasons: List<Int>,
)

data class SeerrRequestEntry(
    val request: SeerrRequestSummary,
    val media: SeerrMediaCard,
)

data class SeerrRequestsPage(
    val items: List<SeerrRequestEntry>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
    val nextSkip: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

data class SeerrRequestOption(
    val quality: SeerrQuality,
    val eligibleSeasonNumbers: List<Int>,
    val blockedBy: SeerrError?,
    val requiresApproval: Boolean,
) {
    val canRequest: Boolean get() = blockedBy == null
}

data class SeerrDetail(
    val media: SeerrMediaCard,
    val overview: String?,
    val backdropUrl: String?,
    val seasons: List<SeerrSeason>,
    val requests: List<SeerrRequestSummary>,
    val quota: SeerrQuota,
    val standard: SeerrRequestOption,
    val fourK: SeerrRequestOption,
    val reconciliationRequired: Boolean,
    val cast: List<SeerrCastMember> = emptyList(),
)

data class SeerrRequestIntent(
    val key: SeerrMediaKey,
    val quality: SeerrQuality,
    val seasons: List<Int> = emptyList(),
)

data class SeerrRequestCreated(
    val request: SeerrRequestSummary,
)

data class SeerrLibraryItem(
    val itemId: String,
    val kind: MediaKind,
    val key: SeerrMediaKey,
)
