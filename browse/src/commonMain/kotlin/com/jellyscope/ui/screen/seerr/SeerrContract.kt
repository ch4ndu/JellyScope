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
import com.jellyscope.core.domain.model.SeerrPerson
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuickConnect
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestOption
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrSeason
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.person.PersonUiState

enum class SeerrSection { Discover, Requests, Search, Connection }

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
    val card: MediaCardUi =
        MediaCardUi(
            id = id,
            title = media.title.orEmpty(),
            subtitle = media.year?.toString(),
            progressFraction = null,
            watched = false,
            unplayedCount = null,
            imageUrl = seerrArtworkUrl(media.posterUrl),
            kind = if (media.key.type == SeerrMediaType.Movie) MediaCardKind.Movie else MediaCardKind.Series,
            year = media.year?.toString(),
            overview = media.overview,
            backdropUrl = seerrArtworkUrl(media.backdropUrl),
        )
}

val SeerrMediaKey.stableId: String get() = "${if (type == SeerrMediaType.Movie) "movie" else "tv"}:$tmdbId"

@Immutable
data class SeerrFeedUi(
    val rows: List<SeerrRow> = emptyList(),
    val rawRows: List<SeerrRow> = emptyList(),
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
    val artworkUrl: String? = null,
)

sealed interface SeerrMutation {
    data object None : SeerrMutation

    data class Confirming(
        val intent: SeerrRequestIntent,
    ) : SeerrMutation

    data class Submitting(
        val intent: SeerrRequestIntent,
        val detailGeneration: Long,
    ) : SeerrMutation

    data class Reconciling(
        val detailGeneration: Long,
    ) : SeerrMutation
}

@Immutable
data class SeerrDetailUi(
    val key: SeerrMediaKey,
    val entryId: Long = 0,
    val related: SeerrRelatedUi = SeerrRelatedUi(),
    val focusedId: String? = null,
    val seasonScroll: SeerrScrollPosition = SeerrScrollPosition(),
    val castScroll: SeerrScrollPosition = SeerrScrollPosition(),
    val detail: SeerrDetail? = null,
    val loading: Boolean = true,
    val error: SeerrError? = null,
    val quality: SeerrQuality = SeerrQuality.Standard,
    val selectedSeasons: Set<Int> = emptySet(),
    val seasons: List<SeerrSeasonUi> = emptyList(),
    val showFourK: Boolean = false,
    val canSubmit: Boolean = false,
    val resolvingLibrary: Boolean = false,
    val uncertain: Boolean = false,
    val mutation: SeerrMutation = SeerrMutation.None,
    val notice: SeerrNotice? = null,
    val scroll: SeerrScrollPosition = SeerrScrollPosition(),
    val admissionFresh: Boolean = false,
    val reviewVisible: Boolean = false,
    val reviewSeason: Int? = null,
    val libraryPickerVisible: Boolean = false,
    val qualityPickerVisible: Boolean = false,
    val requestQualities: List<SeerrQuality> = emptyList(),
    val focusRevision: Long = 0,
) {
    val selectedSeasonNumbers: List<Int> = selectedSeasons.sorted()
    val submitting: Boolean get() = mutation is SeerrMutation.Submitting
    val reconciling: Boolean get() = mutation is SeerrMutation.Reconciling
    val confirmation: SeerrRequestIntent?
        get() =
            when (val current = mutation) {
                is SeerrMutation.Confirming -> current.intent
                is SeerrMutation.Submitting -> current.intent
                else -> null
            }

    val option: SeerrRequestOption? get() = if (quality == SeerrQuality.Standard) detail?.standard else detail?.fourK
    val modalVisible: Boolean get() = reviewVisible || libraryPickerVisible || qualityPickerVisible
    val reviewEnabled: Boolean get() = detail != null && !submitting && !reconciling && !resolvingLibrary
    val reviewCanSubmit: Boolean get() = reviewVisible && reviewSeason == null && canSubmit && confirmation != null
    val chooseSeasons: Boolean get() = reviewSeason == null && key.type == SeerrMediaType.Tv && selectedSeasons.isEmpty()
    val libraryQualities: List<SeerrQuality>
        get() =
            buildList {
                val media = detail?.media ?: return@buildList
                if (media.standardAvailability.isLibraryCopy()) add(SeerrQuality.Standard)
                if (media.fourKAvailability.isLibraryCopy()) add(SeerrQuality.FourK)
            }
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

data class SeerrPersonUi(
    val id: Int,
    val entryId: Long,
    val person: SeerrPerson? = null,
    val presentation: PersonUiState = PersonUiState.Loading,
    val credits: Map<String, SeerrMediaCard> = emptyMap(),
    val movies: List<SeerrRow> = emptyList(),
    val series: List<SeerrRow> = emptyList(),
    val loading: Boolean = true,
    val error: SeerrError? = null,
    val focusedId: String? = null,
    val scroll: SeerrScrollPosition = SeerrScrollPosition(),
    val movieScroll: SeerrScrollPosition = SeerrScrollPosition(),
    val seriesScroll: SeerrScrollPosition = SeerrScrollPosition(),
)

@Immutable
data class SeerrUiState(
    val hideAvailable: Boolean = true,
    val section: SeerrSection = SeerrSection.Discover,
    val catalog: SeerrCatalog = SeerrCatalog.Trending,
    val query: String = "",
    val submittedQuery: String = "",
    val connection: SeerrConnectionUi = SeerrConnectionUi(),
    val discover: SeerrFeedUi = SeerrFeedUi(),
    val requests: SeerrFeedUi = SeerrFeedUi(),
    val search: SeerrFeedUi = SeerrFeedUi(),
    val detail: SeerrDetailUi? = null,
    val restricted: Boolean = false,
    val ownerValid: Boolean = true,
    val lastFocusedId: String? = null,
    val person: SeerrPersonUi? = null,
) {
    val connected: Boolean get() = connection.connection.phase == SeerrConnectionPhase.Connected
    val feed: SeerrFeedUi
        get() =
            when (section) {
                SeerrSection.Requests -> requests
                SeerrSection.Search -> search
                else -> discover
            }
}

enum class SeerrRelatedPhase { NotAttempted, Loading, Ready, Error, Interrupted }

@Immutable
data class SeerrRelatedUi(
    val phase: SeerrRelatedPhase = SeerrRelatedPhase.NotAttempted,
    val rows: List<SeerrRow> = emptyList(),
    val scroll: SeerrScrollPosition = SeerrScrollPosition(),
)

val SeerrDetailUi.libraryAvailable: Boolean
    get() = libraryQualities.isNotEmpty()

private fun SeerrAvailability.isLibraryCopy(): Boolean = this == SeerrAvailability.Available || this == SeerrAvailability.PartiallyAvailable

val SeerrDetailUi.relatedEnabled: Boolean
    get() = admissionFresh && !loading && mutation == SeerrMutation.None && !modalVisible && !uncertain && !resolvingLibrary

sealed interface SeerrIntent {
    data class OpenPerson(
        val id: Int,
    ) : SeerrIntent

    data class OpenCredit(
        val id: String,
    ) : SeerrIntent

    data object RetryPerson : SeerrIntent

    data class RememberPerson(
        val scroll: SeerrScrollPosition,
        val movies: SeerrScrollPosition,
        val series: SeerrScrollPosition,
    ) : SeerrIntent

    data class PersonFocused(
        val id: String,
    ) : SeerrIntent

    data object ToggleHideAvailable : SeerrIntent

    data class OpenSimilar(
        val id: String,
    ) : SeerrIntent

    data object RetrySimilar : SeerrIntent

    data class RememberRelatedScroll(
        val position: SeerrScrollPosition,
    ) : SeerrIntent

    data class RememberSeasonScroll(
        val position: SeerrScrollPosition,
    ) : SeerrIntent

    data class RememberCastScroll(
        val position: SeerrScrollPosition,
    ) : SeerrIntent

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

    data object ChooseQuality : SeerrIntent

    data object ChooseSeasons : SeerrIntent

    data class OpenLibraryQuality(
        val quality: SeerrQuality,
    ) : SeerrIntent

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

    data object Resumed : SeerrIntent

    data object Disposed : SeerrIntent

    data class RememberScroll(
        val detail: Boolean,
        val position: SeerrScrollPosition,
        val section: SeerrSection? = null,
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
    data class Person(
        val value: SeerrPersonUi?,
    ) : SeerrChange

    data class HideAvailable(
        val value: Boolean,
    ) : SeerrChange

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
        is SeerrChange.Person -> state.copy(person = change.value)
        is SeerrChange.HideAvailable -> state.copy(hideAvailable = change.value)
        is SeerrChange.Connection -> state.copy(connection = change.value)
        is SeerrChange.Feed ->
            when (change.section) {
                SeerrSection.Requests -> state.copy(requests = change.value)
                SeerrSection.Search -> state.copy(search = change.value)
                else -> state.copy(discover = change.value)
            }
        is SeerrChange.Detail -> state.copy(detail = change.value)
        is SeerrChange.Section ->
            state.copy(
                section = change.value,
                detail = null,
                person = null,
                lastFocusedId =
                    when (change.value) {
                        SeerrSection.Discover -> state.discover.selectedId
                        SeerrSection.Search -> state.search.selectedId
                        SeerrSection.Requests -> state.requests.selectedId
                        SeerrSection.Connection -> null
                    },
            )
        is SeerrChange.Query -> state.copy(query = change.text)
        is SeerrChange.Catalog ->
            state.copy(
                catalog = if (change.value == SeerrCatalog.Search) state.catalog else change.value,
                submittedQuery = if (change.value == SeerrCatalog.Search) change.query else state.submittedQuery,
                search =
                    if (change.value ==
                        SeerrCatalog.Search
                    ) {
                        SeerrFeedUi(resetRevision = state.search.resetRevision + 1)
                    } else {
                        state.search
                    },
                discover =
                    if (change.value !=
                        SeerrCatalog.Search
                    ) {
                        SeerrFeedUi(resetRevision = state.discover.resetRevision + 1)
                    } else {
                        state.discover
                    },
                lastFocusedId = null,
            )
        is SeerrChange.Policy -> state.copy(ownerValid = change.ownerValid, restricted = change.restricted)
        SeerrChange.ClearContent ->
            state.copy(
                discover = SeerrFeedUi(),
                requests = SeerrFeedUi(),
                search = SeerrFeedUi(),
                query = "",
                submittedQuery = "",
                detail = null,
                person = null,
                lastFocusedId = null,
            )
        is SeerrChange.Focus -> state.copy(lastFocusedId = change.id)
    }

internal fun SeerrDetailUi.project(connection: SeerrConnection): SeerrDetailUi {
    val value = detail ?: return copy(canSubmit = false)
    val fourKConfigured = if (key.type == SeerrMediaType.Movie) connection.service?.movie4kEnabled else connection.service?.series4kEnabled
    val fourKAllowed = connection.user?.permissions?.canRequest(key.type, SeerrQuality.FourK) == true
    val qualities =
        buildList {
            if (connection.user?.permissions?.canRequest(key.type, SeerrQuality.Standard) == true) add(SeerrQuality.Standard)
            if (fourKConfigured == true && fourKAllowed) add(SeerrQuality.FourK)
        }
    // Supported qualities describe context, independently of quota or active requests.
    // A submission keeps its frozen selection until its existing mutation completes.
    val effectiveQuality = if (submitting || quality in qualities) quality else qualities.firstOrNull() ?: quality
    val qualityChanged = effectiveQuality != quality
    val option = if (effectiveQuality == SeerrQuality.Standard) value.standard else value.fourK
    val selected = if (qualityChanged) emptySet() else selectedSeasons.intersect(option.eligibleSeasonNumbers.toSet())
    val visibleSeasons = value.seasons.filter { it.number != 0 || connection.service?.specialEpisodesEnabled == true }
    val rows =
        visibleSeasons.map { season ->
            val availability = if (effectiveQuality == SeerrQuality.Standard) season.standardAvailability else season.fourKAvailability
            val activeRequest =
                value.requests.any {
                    it.quality == effectiveQuality &&
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
            SeerrSeasonUi(
                season,
                season.number in selected,
                reason == null && selectionEnabled,
                reason,
                availability,
                seerrArtworkUrl(season.posterUrl) ?: seerrArtworkUrl(value.backdropUrl) ?: seerrArtworkUrl(value.media.posterUrl),
            )
        }
    val fourKInLibrary =
        value.media.fourKAvailability == SeerrAvailability.Available ||
            value.media.fourKAvailability == SeerrAvailability.PartiallyAvailable
    val units = if (key.type == SeerrMediaType.Movie) 1 else selected.size
    return copy(
        quality = effectiveQuality,
        mutation = if (qualityChanged && mutation is SeerrMutation.Confirming) SeerrMutation.None else mutation,
        reviewVisible = if (qualityChanged) false else reviewVisible,
        reviewSeason = if (qualityChanged) null else reviewSeason,
        selectedSeasons = if (selectedSeasons == selected) selectedSeasons else selected,
        seasons = if (seasons == rows) seasons else rows,
        showFourK = fourKInLibrary || (fourKConfigured == true && fourKAllowed),
        requestQualities = if (requestQualities == qualities) requestQualities else qualities,
        canSubmit =
            effectiveQuality in qualities &&
                selectionEnabled &&
                option.canRequest &&
                units > 0 &&
                value.quota.allows(units) &&
                error == null,
    )
}

/** Only canonical public TMDB poster paths reach Coil; no credential-bearing image builder. */
fun seerrArtworkUrl(value: String?): String? = value?.takeIf { trustedSeerrArtwork.matches(it) }

private val trustedSeerrArtwork = Regex("https://image\\.tmdb\\.org/t/p/(w[0-9]+|original)/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)")
