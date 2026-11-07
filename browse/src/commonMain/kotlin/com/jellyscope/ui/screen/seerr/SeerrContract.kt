// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.runtime.Immutable
import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuickConnect
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestOption
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrSeason

enum class SeerrSection { Discover, Requests, Connection }

enum class SeerrNotice { Created, Reconciled }

@Immutable
data class SeerrScrollPosition(
    val index: Int = 0,
    val offset: Int = 0,
)

@Immutable
data class SeerrRow(
    val media: SeerrMediaCard,
    val request: SeerrRequestSummary? = null,
) {
    val id: String = request?.let { "request:${it.id}" } ?: media.key.stableId
}

val SeerrMediaKey.stableId: String get() = "${if (type == SeerrMediaType.Movie) "movie" else "tv"}:$tmdbId"

@Immutable
data class SeerrFeedUi(
    val rows: List<SeerrRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: SeerrError? = null,
    val page: Int = 0,
    val totalPages: Int = 0,
    val nextSkip: Int = 0,
    val scroll: SeerrScrollPosition = SeerrScrollPosition(),
    val selectedId: String? = null,
    val resetRevision: Long = 0,
) {
    val hasMore: Boolean get() = loaded && page < totalPages
}

@Immutable
data class SeerrConnectionUi(
    val connection: SeerrConnection = SeerrConnection(SeerrConnectionPhase.Disconnected),
    val url: String = "",
    val method: SeerrLoginMethod = SeerrLoginMethod.Jellyfin,
    val busy: Boolean = false,
    val authenticating: Boolean = false,
    val quickConnect: SeerrQuickConnect? = null,
    val error: SeerrError? = null,
    val credentialRevision: Long = 0,
)

@Immutable
data class SeerrSeasonUi(
    val season: SeerrSeason,
    val selected: Boolean,
    val enabled: Boolean,
    val blockedBy: SeerrError?,
    val availability: SeerrAvailability,
)

@Immutable
data class SeerrDetailUi(
    val key: SeerrMediaKey,
    val detail: SeerrDetail? = null,
    val loading: Boolean = true,
    val error: SeerrError? = null,
    val quality: SeerrQuality = SeerrQuality.Standard,
    val selectedSeasons: Set<Int> = emptySet(),
    val seasons: List<SeerrSeasonUi> = emptyList(),
    val showFourK: Boolean = false,
    val canSubmit: Boolean = false,
    val submitting: Boolean = false,
    val resolvingLibrary: Boolean = false,
    val reconciling: Boolean = false,
    val uncertain: Boolean = false,
    val confirmation: SeerrRequestIntent? = null,
    val notice: SeerrNotice? = null,
    val scroll: SeerrScrollPosition = SeerrScrollPosition(),
    val admissionFresh: Boolean = false,
) {
    val option: SeerrRequestOption? get() = if (quality == SeerrQuality.Standard) detail?.standard else detail?.fourK
    val selectionEnabled: Boolean
        get() =
            admissionFresh &&
                detail?.media?.key == key &&
                !loading &&
                !submitting &&
                !reconciling &&
                !uncertain &&
                detail?.reconciliationRequired == false
}

@Immutable
data class SeerrUiState(
    val section: SeerrSection = SeerrSection.Discover,
    val catalog: SeerrCatalog = SeerrCatalog.Trending,
    val query: String = "",
    val submittedQuery: String = "",
    val connection: SeerrConnectionUi = SeerrConnectionUi(),
    val discover: SeerrFeedUi = SeerrFeedUi(),
    val requests: SeerrFeedUi = SeerrFeedUi(),
    val detail: SeerrDetailUi? = null,
    val restricted: Boolean = false,
    val ownerValid: Boolean = true,
    val lastFocusedId: String? = null,
) {
    val connected: Boolean get() = connection.connection.phase == SeerrConnectionPhase.Connected
    val feed: SeerrFeedUi get() = if (section == SeerrSection.Requests) requests else discover
}

sealed interface SeerrIntent {
    data class SelectSection(
        val section: SeerrSection,
    ) : SeerrIntent

    data class SelectCatalog(
        val catalog: SeerrCatalog,
    ) : SeerrIntent

    data class QueryChanged(
        val text: String,
    ) : SeerrIntent

    data object SubmitSearch : SeerrIntent

    data object ClearSearch : SeerrIntent

    data object Refresh : SeerrIntent

    data object LoadMore : SeerrIntent

    data class OpenDetail(
        val row: SeerrRow,
    ) : SeerrIntent

    data object Back : SeerrIntent

    data class SelectQuality(
        val quality: SeerrQuality,
    ) : SeerrIntent

    data class ToggleSeason(
        val number: Int,
    ) : SeerrIntent

    data object ConfirmRequest : SeerrIntent

    data object SubmitRequest : SeerrIntent

    data object Reconcile : SeerrIntent

    data object OpenLibrary : SeerrIntent

    data class UrlChanged(
        val text: String,
    ) : SeerrIntent

    data class SelectLogin(
        val method: SeerrLoginMethod,
    ) : SeerrIntent

    data object ValidateService : SeerrIntent

    // Credentials are event-local, never stored in StateFlow or saved instance state.
    class Login(
        val identifier: String,
        val password: String,
    ) : SeerrIntent

    data object BeginQuickConnect : SeerrIntent

    data object CancelAuthentication : SeerrIntent

    data object RestoreConnection : SeerrIntent

    data object Disconnect : SeerrIntent

    data object Disposed : SeerrIntent

    data class RememberScroll(
        val detail: Boolean,
        val position: SeerrScrollPosition,
    ) : SeerrIntent

    data class Focused(
        val id: String,
    ) : SeerrIntent
}

data class SeerrLibraryEffect internal constructor(
    val id: Long,
    internal val token: SeerrPublicationToken,
    internal val detailGeneration: Long,
    internal val item: SeerrLibraryItem,
)

internal sealed interface SeerrChange {
    data class Connection(
        val value: SeerrConnectionUi,
    ) : SeerrChange

    data class Feed(
        val section: SeerrSection,
        val value: SeerrFeedUi,
    ) : SeerrChange

    data class Detail(
        val value: SeerrDetailUi?,
    ) : SeerrChange

    data class Section(
        val value: SeerrSection,
    ) : SeerrChange

    data class Query(
        val text: String,
    ) : SeerrChange

    data class Catalog(
        val value: SeerrCatalog,
        val query: String = "",
    ) : SeerrChange

    data class Policy(
        val ownerValid: Boolean,
        val restricted: Boolean,
    ) : SeerrChange

    data object ClearContent : SeerrChange

    data class Focus(
        val id: String?,
    ) : SeerrChange
}

internal fun reduceSeerr(
    state: SeerrUiState,
    change: SeerrChange,
): SeerrUiState =
    when (change) {
        is SeerrChange.Connection -> state.copy(connection = change.value)
        is SeerrChange.Feed ->
            if (change.section ==
                SeerrSection.Requests
            ) {
                state.copy(requests = change.value)
            } else {
                state.copy(discover = change.value)
            }
        is SeerrChange.Detail -> state.copy(detail = change.value)
        is SeerrChange.Section -> state.copy(section = change.value, detail = null, lastFocusedId = null)
        is SeerrChange.Query -> state.copy(query = change.text)
        is SeerrChange.Catalog ->
            state.copy(
                catalog = change.value,
                submittedQuery = change.query,
                discover = SeerrFeedUi(resetRevision = state.discover.resetRevision),
                lastFocusedId = null,
            )
        is SeerrChange.Policy -> state.copy(ownerValid = change.ownerValid, restricted = change.restricted)
        SeerrChange.ClearContent -> state.copy(discover = SeerrFeedUi(), requests = SeerrFeedUi(), detail = null, lastFocusedId = null)
        is SeerrChange.Focus -> state.copy(lastFocusedId = change.id)
    }

internal fun SeerrDetailUi.project(connection: SeerrConnection): SeerrDetailUi {
    val value = detail ?: return copy(canSubmit = false)
    val option = if (quality == SeerrQuality.Standard) value.standard else value.fourK
    val selected = selectedSeasons.intersect(option.eligibleSeasonNumbers.toSet())
    val visibleSeasons = value.seasons.filter { it.number != 0 || connection.service?.specialEpisodesEnabled == true }
    val rows =
        visibleSeasons.map { season ->
            val availability = if (quality == SeerrQuality.Standard) season.standardAvailability else season.fourKAvailability
            val activeRequest =
                value.requests.any {
                    it.quality == quality &&
                        season.number in it.seasons &&
                        it.status != SeerrRequestStatus.Declined &&
                        it.status != SeerrRequestStatus.Completed
                }
            val reason =
                when {
                    season.number in option.eligibleSeasonNumbers -> null
                    activeRequest -> SeerrError.AlreadyRequested
                    availability == SeerrAvailability.Available || availability == SeerrAvailability.PartiallyAvailable ->
                        SeerrError.AlreadyAvailable
                    availability == SeerrAvailability.Blocklisted -> SeerrError.Blocklisted
                    season.episodeCount == 0 -> SeerrError.NoSeasonsAvailable
                    else -> option.blockedBy ?: SeerrError.InvalidSelection
                }
            SeerrSeasonUi(season, season.number in selected, reason == null && selectionEnabled, reason, availability)
        }
    val fourKConfigured = if (key.type == SeerrMediaType.Movie) connection.service?.movie4kEnabled else connection.service?.series4kEnabled
    val fourKAllowed = connection.user?.permissions?.canRequest(key.type, SeerrQuality.FourK) == true
    val fourKInLibrary =
        value.media.fourKAvailability == SeerrAvailability.Available ||
            value.media.fourKAvailability == SeerrAvailability.PartiallyAvailable
    val units = if (key.type == SeerrMediaType.Movie) 1 else selected.size
    return copy(
        selectedSeasons = selected,
        seasons = rows,
        showFourK = fourKInLibrary || (fourKConfigured == true && fourKAllowed),
        canSubmit = selectionEnabled && option.canRequest && value.quota.allows(units) && error == null,
    )
}

/** Only canonical public TMDB poster paths reach Coil; no credential-bearing image builder. */
fun seerrArtworkUrl(value: String?): String? = value?.takeIf { trustedSeerrArtwork.matches(it) }

private val trustedSeerrArtwork = Regex("https://image\\.tmdb\\.org/t/p/(w[0-9]+|original)/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)")
