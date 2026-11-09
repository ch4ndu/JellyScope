// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrCastMember
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary

enum class TvSeerrSection { Discover, Requests, Search, Connection }

data class TvSeerrConnectionState(
    // An empty edited draft must not be replaced by an initial connection snapshot.
    val url: String? = null,
    val endpoint: String = "",
    val userName: String = "",
    val phaseKey: String = "seerr.connection.disconnected",
    val connected: Boolean = false,
    val canLogin: Boolean = false,
    val jellyfinLogin: Boolean = false,
    val localLogin: Boolean = false,
    val quickConnect: Boolean = false,
    val plainHttp: Boolean = false,
    val busy: Boolean = false,
    val authenticating: Boolean = false,
    val quickConnectCode: String? = null,
    val quickConnectExpiry: Long = 0,
    val errorKey: String? = null,
)

data class TvSeerrCard(
    val id: String,
    val title: String?,
    val year: String,
    val typeKey: String,
    val posterUrl: String?,
    val standardKey: String,
    val fourKKey: String,
    val requests: List<TvSeerrRequestStatus> = emptyList(),
    val artworkCard: TvMediaCard =
        TvMediaCard(
            id = id,
            title = title.orEmpty(),
            kind = if (typeKey == "seerr.movie") TvCardKind.Movie else TvCardKind.Series,
            imageUrl = posterUrl,
        ),
)

data class TvSeerrRequestStatus(
    val id: Int,
    val qualityKey: String,
    val statusKey: String,
    val seasons: String,
)

data class TvSeerrBrowseState(
    val hideAvailable: Boolean = true,
    val catalog: SeerrCatalog = SeerrCatalog.Trending,
    val query: String = "",
    val submittedQuery: String = "",
    val cards: List<TvSeerrCard> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val errorKey: String? = null,
    val hasMore: Boolean = false,
    val focusedCardId: String? = null,
    val verticalAnchor: String? = null,
)

data class TvSeerrSeason(
    val number: Int,
    val title: String?,
    val specials: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val reasonKey: String?,
    val episodeCount: Int = 0,
    val availabilityKey: String = "seerr.availability.unknown",
)

data class TvSeerrDetailState(
    val card: TvSeerrCard,
    val entryId: Long = 0,
    val backdropUrl: String? = null,
    val cast: List<SeerrCastMember> = emptyList(),
    val similar: List<TvSeerrCard> = emptyList(),
    val relatedPhase: TvSeerrRelatedPhase = TvSeerrRelatedPhase.NotAttempted,
    val relatedEnabled: Boolean = false,
    val focusedId: String? = null,
    val verticalAnchor: String? = null,
    val seasonAnchor: String? = null,
    val castAnchor: String? = null,
    val similarAnchor: String? = null,
    val selectedSeasonCount: Int = 0,
    val showFourK: Boolean = false,
    val overview: String? = null,
    val loading: Boolean = true,
    val errorKey: String? = null,
    val noticeKey: String? = null,
    val fourK: Boolean = false,
    val selectionEnabled: Boolean = false,
    val standardReasonKey: String? = null,
    val fourKReasonKey: String? = null,
    val seasons: List<TvSeerrSeason> = emptyList(),
    val quotaKey: String = "seerr.quota.unavailable",
    val quotaRemaining: Int = 0,
    val quotaDays: Int = 0,
    val canRequest: Boolean = false,
    val requiresApproval: Boolean = true,
    val requestReasonKey: String? = null,
    val reconciliationRequired: Boolean = false,
    val resolvingLibrary: Boolean = false,
    val libraryAvailable: Boolean = false,
)

data class TvSeerrConfirmation(
    val title: String,
    val typeKey: String,
    val qualityKey: String,
    val seasons: List<TvSeerrSeason>,
    val requiresApproval: Boolean,
    val submitting: Boolean = false,
)

data class TvSeerrState(
    val section: TvSeerrSection = TvSeerrSection.Discover,
    val ownerValid: Boolean = true,
    val restricted: Boolean = false,
    val connection: TvSeerrConnectionState = TvSeerrConnectionState(),
    val browse: TvSeerrBrowseState = TvSeerrBrowseState(),
    val detail: TvSeerrDetailState? = null,
    val confirmation: TvSeerrConfirmation? = null,
    val reconciliationRequired: Boolean = false,
)

/** A signal only. The verified item stays private until consumption revalidates ownership. */
data class TvSeerrLibraryEffect(
    val id: Long,
)

enum class TvSeerrRelatedPhase { NotAttempted, Loading, Ready, Error, Interrupted }

sealed interface TvSeerrIntent {
    data object ToggleHideAvailable : TvSeerrIntent

    data class OpenSimilar(
        val id: String,
    ) : TvSeerrIntent

    data object RetrySimilar : TvSeerrIntent

    data class FocusDetail(
        val id: String,
    ) : TvSeerrIntent

    data class RememberPosition(
        val section: String,
        val id: String?,
    ) : TvSeerrIntent

    data class SelectSection(
        val section: TvSeerrSection,
    ) : TvSeerrIntent

    data class SelectCatalog(
        val catalog: SeerrCatalog,
    ) : TvSeerrIntent

    data object ClearSearch : TvSeerrIntent

    data class QueryChanged(
        val query: String,
    ) : TvSeerrIntent

    data class Search(
        val query: String,
    ) : TvSeerrIntent

    data object Refresh : TvSeerrIntent

    data object LoadMore : TvSeerrIntent

    data class RememberBrowsePosition(
        val section: TvSeerrSection,
        val id: String?,
    ) : TvSeerrIntent

    data class FocusCard(
        val id: String,
    ) : TvSeerrIntent

    data class OpenDetail(
        val id: String,
    ) : TvSeerrIntent

    data object Back : TvSeerrIntent

    data class SelectQuality(
        val fourK: Boolean,
    ) : TvSeerrIntent

    data class ToggleSeason(
        val number: Int,
    ) : TvSeerrIntent

    data object ConfirmRequest : TvSeerrIntent

    data object SubmitRequest : TvSeerrIntent

    data object Reconcile : TvSeerrIntent

    data object ViewInLibrary : TvSeerrIntent

    class UrlChanged(
        val url: String,
    ) : TvSeerrIntent {
        override fun toString(): String = "UrlChanged([redacted])"
    }

    data class ValidateService(
        val url: String,
    ) : TvSeerrIntent {
        override fun toString(): String = "ValidateService([redacted])"
    }

    class Login(
        val method: SeerrLoginMethod,
        val url: String,
        val identifier: String,
        val password: String,
    ) : TvSeerrIntent {
        override fun toString(): String = "Login([redacted])"
    }

    class BeginQuickConnect(
        val url: String,
    ) : TvSeerrIntent {
        override fun toString(): String = "BeginQuickConnect([redacted])"
    }

    data object CancelAuthentication : TvSeerrIntent

    data object RestoreConnection : TvSeerrIntent

    data object Disconnect : TvSeerrIntent

    data object Visible : TvSeerrIntent

    data object Hidden : TvSeerrIntent
}

internal sealed interface TvSeerrChange {
    data class Connection(
        val value: TvSeerrConnectionState,
    ) : TvSeerrChange

    data class Browse(
        val value: TvSeerrBrowseState,
    ) : TvSeerrChange

    data class Detail(
        val value: TvSeerrDetailState?,
    ) : TvSeerrChange

    data class Confirmation(
        val value: TvSeerrConfirmation?,
    ) : TvSeerrChange

    data class Section(
        val value: TvSeerrSection,
    ) : TvSeerrChange

    data class Policy(
        val ownerValid: Boolean,
        val restricted: Boolean,
    ) : TvSeerrChange

    data class Reconciliation(
        val required: Boolean,
    ) : TvSeerrChange

    data object ClearContent : TvSeerrChange
}

internal fun reduceTvSeerr(
    state: TvSeerrState,
    change: TvSeerrChange,
): TvSeerrState =
    when (change) {
        is TvSeerrChange.Connection -> state.copy(connection = change.value)
        is TvSeerrChange.Browse -> state.copy(browse = change.value)
        is TvSeerrChange.Detail -> state.copy(detail = change.value)
        is TvSeerrChange.Confirmation -> state.copy(confirmation = change.value)
        is TvSeerrChange.Section -> state.copy(section = change.value, detail = null, confirmation = null)
        is TvSeerrChange.Policy -> state.copy(ownerValid = change.ownerValid, restricted = change.restricted)
        is TvSeerrChange.Reconciliation -> state.copy(reconciliationRequired = change.required)
        TvSeerrChange.ClearContent ->
            state.copy(browse = TvSeerrBrowseState(), detail = null, confirmation = null)
    }

internal fun SeerrConnection.toTvSeerr(): TvSeerrConnectionState =
    TvSeerrConnectionState(
        endpoint = endpoint.orEmpty(),
        userName = user?.displayName.orEmpty(),
        phaseKey =
            when (phase) {
                SeerrConnectionPhase.Disconnected -> "seerr.connection.disconnected"
                SeerrConnectionPhase.AwaitingLogin -> "seerr.connection.awaiting_login"
                SeerrConnectionPhase.Connected -> "seerr.connection.connected"
                SeerrConnectionPhase.VerificationRequired -> "seerr.connection.verification"
            },
        connected = phase == SeerrConnectionPhase.Connected,
        canLogin = phase == SeerrConnectionPhase.AwaitingLogin,
        jellyfinLogin = service?.jellyfinLoginEnabled == true,
        localLogin = service?.localLoginEnabled == true,
        quickConnect = service?.quickConnectSupported == true,
        plainHttp = service?.usesPlainHttp == true,
    )

internal fun SeerrMediaKey.stableId(): String = "${if (type == SeerrMediaType.Movie) "movie" else "tv"}:$tmdbId"

internal fun SeerrMediaCard.toTvSeerr(requests: List<SeerrRequestSummary> = emptyList()): TvSeerrCard =
    TvSeerrCard(
        id = key.stableId(),
        title = title,
        year = year?.toString().orEmpty(),
        typeKey = if (key.type == SeerrMediaType.Movie) "seerr.movie" else "seerr.tv",
        posterUrl = posterUrl,
        standardKey = standardAvailability.copyKey(),
        fourKKey = fourKAvailability.copyKey(),
        requests =
            requests.map { request ->
                TvSeerrRequestStatus(
                    id = request.id,
                    qualityKey = request.quality.copyKey(),
                    statusKey = request.status.copyKey(),
                    seasons = request.seasons.joinToString(", "),
                )
            },
    )

internal fun SeerrDetail.toTvSeerr(
    quality: SeerrQuality,
    selected: Set<Int>,
    needsReconciliation: Boolean,
    supportsSpecials: Boolean,
): TvSeerrDetailState {
    val option = if (quality == SeerrQuality.FourK) fourK else standard
    val units = if (media.key.type == SeerrMediaType.Movie) 1 else selected.size
    val blocked =
        when {
            needsReconciliation || reconciliationRequired -> SeerrError.ReconciliationRequired
            option.blockedBy != null -> option.blockedBy
            units == 0 -> SeerrError.InvalidSelection
            !quota.allows(units) -> SeerrError.QuotaExceeded
            else -> null
        }
    return TvSeerrDetailState(
        card = media.toTvSeerr(requests),
        overview = overview,
        libraryAvailable =
            (
                if (quality ==
                    SeerrQuality.Standard
                ) {
                    media.standardAvailability
                } else {
                    media.fourKAvailability
                }
            ).let {
                it == SeerrAvailability.Available ||
                    it == SeerrAvailability.PartiallyAvailable
            },
        backdropUrl = backdropUrl,
        cast = cast,
        loading = false,
        fourK = quality == SeerrQuality.FourK,
        selectionEnabled = true,
        standardReasonKey = standard.blockedBy?.copyKey(),
        fourKReasonKey = fourK.blockedBy?.copyKey(),
        seasons =
            seasons.filter { it.number > 0 || supportsSpecials }.map { season ->
                val eligible = season.number in option.eligibleSeasonNumbers
                val availability = if (quality == SeerrQuality.FourK) season.fourKAvailability else season.standardAvailability
                val activeRequest =
                    requests.any {
                        it.quality == quality &&
                            season.number in it.seasons &&
                            it.status !in setOf(SeerrRequestStatus.Declined, SeerrRequestStatus.Completed)
                    }
                val reason =
                    when {
                        eligible -> null
                        activeRequest -> "seerr.season.requested"
                        availability == SeerrAvailability.PartiallyAvailable -> "seerr.season.partial"
                        availability !in setOf(SeerrAvailability.Unknown, SeerrAvailability.Deleted) -> availability.copyKey()
                        else -> "seerr.season.unavailable"
                    }
                TvSeerrSeason(
                    number = season.number,
                    title = season.title,
                    specials = season.number == 0,
                    selected = season.number in selected,
                    enabled = eligible && option.canRequest && !needsReconciliation && !reconciliationRequired,
                    reasonKey = reason ?: option.blockedBy?.copyKey(),
                    episodeCount = season.episodeCount,
                    availabilityKey = availability.copyKey(),
                )
            },
        quotaKey =
            when (quota.kind) {
                SeerrQuotaKind.Unlimited -> "seerr.quota.unlimited"
                SeerrQuotaKind.Limited -> "seerr.quota.remaining"
                SeerrQuotaKind.Unavailable -> "seerr.quota.unavailable"
            },
        quotaRemaining = quota.remaining ?: 0,
        quotaDays = quota.days ?: 0,
        canRequest = blocked == null,
        requiresApproval = option.requiresApproval,
        requestReasonKey = blocked?.copyKey(),
        reconciliationRequired = needsReconciliation || reconciliationRequired,
    )
}

internal fun SeerrQuality.copyKey(): String = if (this == SeerrQuality.FourK) "seerr.quality.4k" else "seerr.quality.standard"

internal fun SeerrAvailability.copyKey(): String =
    when (this) {
        SeerrAvailability.Unknown -> "seerr.availability.not_available"
        SeerrAvailability.Pending -> "seerr.availability.pending"
        SeerrAvailability.Processing -> "seerr.availability.processing"
        SeerrAvailability.PartiallyAvailable -> "seerr.availability.partial"
        SeerrAvailability.Available -> "seerr.availability.available"
        SeerrAvailability.Blocklisted -> "seerr.availability.blocklisted"
        SeerrAvailability.Deleted -> "seerr.availability.deleted"
        SeerrAvailability.Unrecognized -> "seerr.availability.unknown"
    }

internal fun SeerrRequestStatus.copyKey(): String =
    when (this) {
        SeerrRequestStatus.Pending -> "seerr.status.pending"
        SeerrRequestStatus.Approved -> "seerr.status.approved"
        SeerrRequestStatus.Declined -> "seerr.status.declined"
        SeerrRequestStatus.Failed -> "seerr.status.failed"
        SeerrRequestStatus.Completed -> "seerr.status.completed"
        SeerrRequestStatus.Unrecognized -> "seerr.status.unknown"
    }

internal fun SeerrError.copyKey(): String =
    when (this) {
        SeerrError.InvalidUrl -> "seerr.error.invalid_url"
        SeerrError.UnsupportedService -> "seerr.error.unsupported_service"
        SeerrError.SetupRequired -> "seerr.error.setup_required"
        SeerrError.HttpsRequired -> "seerr.error.https_required"
        SeerrError.SecurityRejected -> "seerr.error.security_rejected"
        SeerrError.RedirectRejected -> "seerr.error.redirect_rejected"
        SeerrError.InvalidCredentials -> "seerr.error.invalid_credentials"
        SeerrError.AccountMismatch -> "seerr.error.account_mismatch"
        SeerrError.AuthenticationRequired -> "seerr.error.authentication_required"
        SeerrError.SessionExpired -> "seerr.error.session_expired"
        SeerrError.VerificationUnavailable -> "seerr.error.verification_unavailable"
        SeerrError.QuickConnectUnavailable -> "seerr.error.quick_connect_unavailable"
        SeerrError.QuickConnectExpired -> "seerr.error.quick_connect_expired"
        SeerrError.RestrictedAccount -> "seerr.error.restricted_account"
        SeerrError.PermissionDenied -> "seerr.error.permission_denied"
        SeerrError.QuotaUnavailable -> "seerr.error.quota_unavailable"
        SeerrError.QuotaExceeded -> "seerr.error.quota_exceeded"
        SeerrError.AlreadyRequested -> "seerr.error.already_requested"
        SeerrError.AlreadyAvailable -> "seerr.error.already_available"
        SeerrError.Blocklisted -> "seerr.error.blocklisted"
        SeerrError.MetadataUnavailable -> "seerr.error.metadata_unavailable"
        SeerrError.InvalidSelection -> "seerr.error.invalid_selection"
        SeerrError.NoSeasonsAvailable -> "seerr.error.no_seasons_available"
        SeerrError.RequestInProgress -> "seerr.error.request_in_progress"
        SeerrError.ReconciliationRequired -> "seerr.error.reconciliation_required"
        SeerrError.LibraryItemUnavailable -> "seerr.error.library_item_unavailable"
        SeerrError.RateLimited -> "seerr.error.rate_limited"
        SeerrError.NetworkUnavailable -> "seerr.error.network_unavailable"
        SeerrError.InvalidResponse -> "seerr.error.invalid_response"
        SeerrError.PersistenceFailed -> "seerr.error.persistence_failed"
        SeerrError.StaleOwner -> "seerr.error.owner_changed"
    }
