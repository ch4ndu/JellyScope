// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.visibleRelatedGroups
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TvSeason(
    val id: String,
    val name: String,
    val indexNumber: Int?,
)

data class TvItemDetailContent(
    val id: String,
    val title: String,
    val kind: TvCardKind,
    val overview: String? = null,
    val productionYear: Int? = null,
    val runtimeMinutes: Long? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val playedPercentage: Double? = null,
    val resumePositionTicks: Long = 0L,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val badges: TvItemBadges = TvItemBadges(),
)

data class TvItemDetailState(
    val isLoading: Boolean = true,
    val content: TvItemDetailContent? = null,
    val seasons: List<TvSeason> = emptyList(),
    val seasonsError: TvErrorKind? = null,
    val selectedSeasonId: String? = null,
    val episodes: List<TvMediaCard> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: TvErrorKind? = null,
    // Series-wide Next Up focus target for the episode strip (resolved via
    // GetNextUpUseCase, not a first-season scan).
    val nextUpEpisodeId: String? = null,
    val relatedGroups: List<TvRelatedGroup> = emptyList(),
    val error: TvErrorKind? = null,
)

class TvItemDetailPresenter(
    private val session: Session,
    private val itemId: String,
    private val getItemDetail: GetItemDetailUseCase,
    private val getSeriesSeasons: GetSeriesSeasonsUseCase,
    private val getSeasonEpisodes: GetSeasonEpisodesUseCase,
    private val getNextUp: GetNextUpUseCase,
    private val getRelatedItems: GetRelatedItemsUseCase,
    private val setItemPlayed: SetItemPlayedAction,
    private val setItemFavorite: SetItemFavoriteAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvItemDetailState())
    val state: StateFlow<TvItemDetailState> = _state.asStateFlow()

    private var episodesJob: Job? = null
    private var relatedJob: Job? = null
    private val playedMutationMutex = Mutex()
    private val favoriteMutationMutex = Mutex()
    private var playedIntentGeneration = 0L
    private var favoriteIntentGeneration = 0L
    private var lastConfirmedPlayed = false
    private var lastConfirmedFavorite = false

    fun watchState(onChange: (TvItemDetailState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        relatedJob?.cancel()
        _state.update { TvItemDetailState(isLoading = true) }
        scope.launch {
            // Playback fields carry the media streams the badges derive from.
            getItemDetail(itemId, includePlaybackFields = true)
                .onSuccess { detail ->
                    val content = withContext(dispatchers.work) { detail.toContent() }
                    lastConfirmedPlayed = content.played
                    lastConfirmedFavorite = content.isFavorite
                    _state.update {
                        TvItemDetailState(
                            isLoading = false,
                            content = content,
                        )
                    }
                    if (detail.item.kind == MediaKind.Series) {
                        loadSeasons()
                    }
                    collectRelated(detail)
                }.onFailure { error ->
                    _state.update {
                        TvItemDetailState(
                            isLoading = false,
                            error = error.toTvErrorKind(),
                        )
                    }
                }
        }
    }

    fun togglePlayed() {
        val content = state.value.content ?: return
        val target = !content.played
        _state.update { current -> current.copy(content = content.copy(played = target)) }
        val intentGeneration = ++playedIntentGeneration
        scope.launch {
            playedMutationMutex.withLock {
                setItemPlayed(content.id, target)
                    .onSuccess { lastConfirmedPlayed = target }
                    .onFailure {
                        if (intentGeneration == playedIntentGeneration) {
                            _state.update { current ->
                                current.copy(content = current.content?.copy(played = lastConfirmedPlayed))
                            }
                        }
                    }
            }
        }
    }

    fun toggleFavorite() {
        val content = state.value.content ?: return
        val target = !content.isFavorite
        _state.update { current -> current.copy(content = content.copy(isFavorite = target)) }
        val intentGeneration = ++favoriteIntentGeneration
        scope.launch {
            favoriteMutationMutex.withLock {
                setItemFavorite(content.id, target)
                    .onSuccess { lastConfirmedFavorite = target }
                    .onFailure {
                        if (intentGeneration == favoriteIntentGeneration) {
                            _state.update { current ->
                                current.copy(content = current.content?.copy(isFavorite = lastConfirmedFavorite))
                            }
                        }
                    }
            }
        }
    }

    // Collects through upstream completion so repository cache commits finish;
    // only the first non-empty groups occupy the shared visible shelf limit.
    private fun collectRelated(detail: MediaItemDetail) {
        relatedJob =
            scope.launch {
                getRelatedItems(detail).visibleRelatedGroups().collect { group ->
                    val items =
                        withContext(dispatchers.work) {
                            group.items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                        }
                    val tvGroup =
                        TvRelatedGroup(
                            kind = group.kind,
                            label = group.label,
                            items = items,
                        )
                    _state.update { current -> current.copy(relatedGroups = current.relatedGroups + tvGroup) }
                }
            }
    }

    fun selectSeason(seasonId: String) {
        // Reselecting the current season is a no-op unless its episodes failed,
        // in which case selection doubles as retry.
        if (state.value.selectedSeasonId == seasonId && state.value.episodesError == null) {
            return
        }
        loadEpisodes(seasonId)
    }

    fun retrySeasons() {
        loadSeasons()
    }

    fun retryEpisodes() {
        val seasonId = state.value.selectedSeasonId ?: return
        loadEpisodes(seasonId)
    }

    private fun loadSeasons() {
        _state.update { current -> current.copy(seasonsError = null) }
        scope.launch {
            getSeriesSeasons(itemId)
                .onSuccess { seasons ->
                    val tvSeasons =
                        withContext(dispatchers.work) {
                            seasons.map { season ->
                                TvSeason(
                                    id = season.id,
                                    name = season.name,
                                    indexNumber = season.indexNumber,
                                )
                            }
                        }
                    // Series-wide Next Up decides the initial season and the
                    // strip's focus target; fallback is the first season with
                    // no focus target.
                    val nextUpEpisode = getNextUp(seriesId = itemId).getOrNull()?.firstOrNull()
                    val nextUpSeason =
                        nextUpEpisode?.seasonId?.let { seasonId ->
                            tvSeasons.firstOrNull { season -> season.id == seasonId }
                        }
                    _state.update { current ->
                        current.copy(
                            seasons = tvSeasons,
                            nextUpEpisodeId = nextUpEpisode?.id.takeIf { nextUpSeason != null },
                        )
                    }
                    (nextUpSeason ?: tvSeasons.firstOrNull())?.let { season -> loadEpisodes(season.id) }
                }.onFailure { error ->
                    _state.update { current -> current.copy(seasonsError = error.toTvErrorKind()) }
                }
        }
    }

    private fun loadEpisodes(seasonId: String) {
        episodesJob?.cancel()
        _state.update { current ->
            current.copy(
                selectedSeasonId = seasonId,
                episodes = emptyList(),
                episodesLoading = true,
                episodesError = null,
            )
        }
        episodesJob =
            scope.launch {
                getSeasonEpisodes(seriesId = itemId, seasonId = seasonId)
                    .onSuccess { episodes ->
                        // A slow response for a season the user has already
                        // navigated away from must not clobber the newer one.
                        if (state.value.selectedSeasonId != seasonId) {
                            return@onSuccess
                        }
                        val cards =
                            withContext(dispatchers.work) {
                                episodes.map { episode -> episode.toTvMediaCard(session, imageUrlBuilder) }
                            }
                        // Re-check after the work hop: a newer season may have
                        // landed while projection ran.
                        if (state.value.selectedSeasonId != seasonId) {
                            return@onSuccess
                        }
                        _state.update { current ->
                            current.copy(
                                episodes = cards,
                                episodesLoading = false,
                            )
                        }
                    }.onFailure { error ->
                        if (state.value.selectedSeasonId == seasonId) {
                            _state.update { current ->
                                current.copy(
                                    episodesLoading = false,
                                    episodesError = error.toTvErrorKind(),
                                )
                            }
                        }
                    }
            }
    }

    private fun MediaItemDetail.toContent(): TvItemDetailContent =
        TvItemDetailContent(
            id = item.id,
            title = item.name,
            kind = item.kind.toTvCardKind(),
            overview = overview ?: item.overview,
            productionYear = productionYear ?: item.productionYear,
            runtimeMinutes = item.runtime?.inWholeMinutes,
            officialRating = officialRating ?: item.officialRating,
            communityRating = communityRating ?: item.communityRating,
            genres = genres.ifEmpty { item.genres },
            backdropUrl =
                item.imageRefs.backdropTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = item.id,
                        type = JellyfinImageType.Backdrop,
                        tag = tag,
                        maxWidth = BACKDROP_IMAGE_MAX_WIDTH,
                    )
                },
            logoUrl =
                item.imageRefs.logoTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = item.id,
                        type = JellyfinImageType.Logo,
                        tag = tag,
                        maxWidth = LOGO_IMAGE_MAX_WIDTH,
                    )
                },
            posterUrl =
                item.imageRefs.primaryTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = item.id,
                        type = JellyfinImageType.Primary,
                        tag = tag,
                        maxWidth = CARD_IMAGE_MAX_WIDTH,
                    )
                },
            playedPercentage = item.playedPercentage,
            resumePositionTicks = item.playbackPositionTicks ?: 0L,
            seriesName = item.seriesName,
            episodeLabel = item.episodeLabel,
            played = item.played,
            isFavorite = item.isFavorite,
            badges = badges(),
        )

    private fun MediaItemDetail.badges(): TvItemBadges {
        val streams = versions.firstOrNull()?.mediaStreams.orEmpty()
        val videoHeight =
            streams
                .filter { stream -> stream.type.equals("Video", ignoreCase = true) }
                .mapNotNull(PlaybackMediaStream::height)
                .maxOrNull()
        val audioLayout =
            streams
                .firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
                ?.channelLayout
                ?.takeIf { layout -> layout.contains('.') }
        return TvItemBadges(
            resolution =
                when {
                    videoHeight == null -> null
                    videoHeight >= 2000 -> "4K"
                    videoHeight >= 1000 -> "HD"
                    else -> null
                },
            audioLayout = audioLayout,
            hasSubtitles = streams.any { stream -> stream.type.equals("Subtitle", ignoreCase = true) },
            officialRating = officialRating ?: item.officialRating,
            communityRating = communityRating ?: item.communityRating,
        )
    }
}
