// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaPerson
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.ResumeDecision
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.resumeDecision
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionsUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.screen.player.PlaybackSelection
import com.jellyscope.ui.screen.player.PlaybackSelectionMemory
import com.jellyscope.ui.screen.player.PlaybackSelectionSnapshot
import com.jellyscope.ui.screen.player.resolveRememberedSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SeriesEpisodeProjectionResult(
    val episodes: List<EpisodeUi>,
    val resolvedSourceIds: Map<String, String?>,
)

private data class SeriesEpisodeProjectionRequest(
    val seasonId: String,
    val generation: Long,
    val selectedSeasonId: String?,
    val mediaSourceSelectionGenerations: Map<String, Long>,
)

class SeriesViewModel(
    private val session: Session,
    private val seriesId: String,
    private val getItemDetailUseCase: GetItemDetailUseCase,
    private val getRelatedItemsUseCase: GetRelatedItemsUseCase,
    private val getNextUpUseCase: GetNextUpUseCase,
    private val getSeriesSeasonsUseCase: GetSeriesSeasonsUseCase,
    private val getSeasonEpisodesUseCase: GetSeasonEpisodesUseCase,
    private val observePlaybackStopSettlementUseCase: ObservePlaybackStopSettlementUseCase,
    private val setItemFavoriteAction: SetItemFavoriteAction,
    private val setItemPlayedAction: SetItemPlayedAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val playbackSelectionMemory: PlaybackSelectionMemory,
    private val getPlaybackLaunchContextUseCase: GetPlaybackLaunchContextUseCase,
    private val getSubtitleSelectionsUseCase: GetSubtitleSelectionsUseCase? = null,
    private val getPlaybackSelectionsUseCase: GetPlaybackSelectionsUseCase? = null,
    private val formatterStringsProvider: suspend () -> DetailFormatterStrings = { detailFormatterStrings() },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val episodeCache = mutableMapOf<String, List<EpisodeUi>>()
    private val episodeCacheLocationsByItemId = mutableMapOf<String, MutableSet<String>>()
    private val selectedMediaSourceIdsByEpisode = mutableMapOf<String, String>()
    private val mediaSourceSelectionGenerations = mutableMapOf<String, Long>()
    private val mediaSourceSelectionJobs = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow<SeriesUiState>(SeriesUiState.Loading)
    val state: StateFlow<SeriesUiState> = _state.asStateFlow()
    private val _focusedEpisodeId = MutableStateFlow<String?>(null)
    val focusedEpisodeId: StateFlow<String?> = _focusedEpisodeId.asStateFlow()
    private val displayedEpisodeIds =
        state
            .map { state ->
                ((state as? SeriesUiState.Content)?.content?.episodesState as? SeasonEpisodesUiState.Content)
                    ?.episodes
                    ?.mapTo(mutableSetOf()) { episode -> episode.itemId }
                    .orEmpty()
            }.distinctUntilChanged()
    private var highestObservedSettlementSequence = 0L
    private var highestRefreshedSettlementSequence = 0L
    private val episodesLoadGenerations = mutableMapOf<String, Long>()
    private var activeEpisodesLoadJob: Job? = null
    private var topLevelLoadGeneration = 0L

    init {
        observePlaybackStopSettlements()
        load()
    }

    fun retry() {
        load()
    }

    fun retryEpisodes() {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val seasonId = content.selectedSeasonId ?: return
        _state.updateEpisodesState(seasonId, SeasonEpisodesUiState.Loading)
        loadEpisodesForSeason(seasonId, content.seasonIndexFor(seasonId))
    }

    /**
     * Silent refresh on return from the player: drop the cached episodes for the
     * visible season and re-fetch so watched / resume state changed during
     * playback is reflected. loadEpisodesForSeason keeps the current episodes on
     * screen (no Loading state) until the fresh data arrives, so there is no
     * flash and focus/scroll are preserved (items are keyed by id). No-op until
     * the first load has produced Content.
     */
    fun refresh() {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val seasonId = content.selectedSeasonId ?: return
        removeCachedSeason(seasonId)
        loadEpisodesForSeason(seasonId, content.seasonIndexFor(seasonId))
    }

    private fun observePlaybackStopSettlements() {
        viewModelScope.launch {
            combine(
                observePlaybackStopSettlementUseCase(session.serverId, session.userId),
                displayedEpisodeIds,
            ) { settlements, displayedIds ->
                settlements.filter { settlement -> settlement.key.itemId in displayedIds }
            }.flowOn(workDispatcher).collect { settlements ->
                settlements.forEach { settlement -> recordStopSettlement(settlement.sequence) }
            }
        }
    }

    private fun recordStopSettlement(sequence: Long) {
        if (sequence <= highestObservedSettlementSequence) {
            return
        }
        highestObservedSettlementSequence = sequence
        refreshAfterStopSettlement()
    }

    private fun refreshAfterStopSettlement() {
        if (highestRefreshedSettlementSequence >= highestObservedSettlementSequence) {
            return
        }
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val seasonId = content.selectedSeasonId ?: return
        if (content.episodesState !is SeasonEpisodesUiState.Content) {
            return
        }
        // Any in-flight episodes load defers the settlement refresh: the
        // completion hook re-enters here, so a settlement can delay a refresh
        // but never race a concurrent request whose stale result commits last.
        if (activeEpisodesLoadJob?.isActive == true) {
            return
        }

        val refreshedSequence = highestObservedSettlementSequence
        removeCachedSeason(seasonId)
        loadEpisodesForSeason(
            seasonId = seasonId,
            seasonIndex = content.seasonIndexFor(seasonId),
            onComplete = {
                highestRefreshedSettlementSequence =
                    maxOf(highestRefreshedSettlementSequence, refreshedSequence)
            },
        )
    }

    fun selectSeason(seasonId: String) {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        if (content.selectedSeasonId == seasonId) {
            return
        }

        val cached = episodeCache[seasonId]
        if (cached != null) {
            _state.updateEpisodesState(seasonId, cached.toEpisodesState())
        } else {
            _state.updateEpisodesState(seasonId, SeasonEpisodesUiState.Loading)
            loadEpisodesForSeason(seasonId, content.seasonIndexFor(seasonId))
        }
    }

    fun focusEpisode(itemId: String) {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val episodes = (content.episodesState as? SeasonEpisodesUiState.Content)?.episodes ?: return
        if (episodes.none { candidate -> candidate.itemId == itemId }) {
            return
        }
        _focusedEpisodeId.value = itemId
    }

    fun selectEpisodeMediaVersion(
        itemId: String,
        mediaSourceId: String,
    ) {
        val episode = episodeForSelection(itemId) ?: return
        if (episode.versions.none { version -> version.id == mediaSourceId }) {
            return
        }

        selectedMediaSourceIdsByEpisode[itemId] = mediaSourceId
        val generation = (mediaSourceSelectionGenerations[itemId] ?: 0L) + 1L
        mediaSourceSelectionGenerations[itemId] = generation
        mediaSourceSelectionJobs.remove(itemId)?.cancel()
        if (episode.selectedMediaSourceId == mediaSourceId) {
            return
        }
        mediaSourceSelectionJobs[itemId] =
            viewModelScope.launch {
                val launchContext =
                    withContext(workDispatcher) {
                        getPlaybackLaunchContextUseCase(session, itemId, mediaSourceId)
                    }
                val rememberedSelection = rememberedSelectionFor(itemId, mediaSourceId, launchContext)
                val currentEpisode = episodeForSelection(itemId) ?: return@launch
                val currentVersion =
                    currentEpisode.versions.firstOrNull { version -> version.id == mediaSourceId }
                        ?: return@launch
                if (mediaSourceSelectionGenerations[itemId] != generation ||
                    selectedMediaSourceIdsByEpisode[itemId] != mediaSourceId
                ) {
                    return@launch
                }
                val selectedEpisode =
                    currentEpisode.withSelectedMediaVersion(
                        currentVersion.resolveLaunchDefaults(
                            playbackPreferences = launchContext.playbackPreferences,
                            rememberedSelection = rememberedSelection,
                            storedSubtitleSelection = launchContext.subtitleSelection,
                        ),
                    )
                applyEpisodeSourceProjection(selectedEpisode)
            }
    }

    private fun rememberedSelectionFor(
        itemId: String,
        mediaSourceId: String,
        launchContext: PlaybackLaunchContext,
    ): PlaybackSelection? =
        resolveRememberedSelection(
            durable = launchContext.playbackSelection,
            sourceMemory =
                playbackSelectionMemory.sourceSelectionFor(
                    session.accountIdentity(),
                    itemId,
                    mediaSourceId,
                ),
            legacyItemMemory = playbackSelectionMemory.selectionFor(session.accountIdentity(), itemId),
            durableStoreAvailable = launchContext.playbackSelectionOutcome != PlaybackLaunchReadOutcome.Unavailable,
        )

    private fun episodeForSelection(itemId: String): EpisodeUi? {
        val content = (_state.value as? SeriesUiState.Content)?.content
        return (content?.episodesState as? SeasonEpisodesUiState.Content)
            ?.episodes
            ?.firstOrNull { episode -> episode.itemId == itemId }
            ?: content?.focusedEpisode?.takeIf { episode -> episode.itemId == itemId }
            ?: content?.nextUpEpisode?.takeIf { episode -> episode.itemId == itemId }
            ?: content?.seriesPlayEpisode?.takeIf { episode -> episode.itemId == itemId }
            ?: episodeCacheLocationsByItemId[itemId]
                .orEmpty()
                .asSequence()
                .mapNotNull { seasonId -> episodeCache[seasonId]?.firstOrNull { episode -> episode.itemId == itemId } }
                .firstOrNull()
    }

    private fun applyEpisodeSourceProjection(selectedEpisode: EpisodeUi) {
        episodeCacheLocationsByItemId[selectedEpisode.itemId].orEmpty().toList().forEach { cachedSeasonId ->
            val cachedEpisodes = episodeCache[cachedSeasonId] ?: return@forEach
            cacheSeasonEpisodes(
                cachedSeasonId,
                cachedEpisodes.map { episode ->
                    if (episode.itemId == selectedEpisode.itemId) {
                        episode.withSourceProjectionFrom(selectedEpisode)
                    } else {
                        episode
                    }
                },
            )
        }
        _state.update { current ->
            val currentContent = current as? SeriesUiState.Content ?: return@update current
            val content = currentContent.content
            val episodesState =
                (content.episodesState as? SeasonEpisodesUiState.Content)?.let { episodes ->
                    SeasonEpisodesUiState.Content(
                        episodes.episodes.map { episode ->
                            if (episode.itemId == selectedEpisode.itemId) {
                                episode.withSourceProjectionFrom(selectedEpisode)
                            } else {
                                episode
                            }
                        },
                    )
                } ?: content.episodesState
            currentContent.copy(
                content =
                    content.copy(
                        episodesState = episodesState,
                        focusedEpisode =
                            content.focusedEpisode?.let { episode ->
                                if (episode.itemId == selectedEpisode.itemId) {
                                    episode.withSourceProjectionFrom(selectedEpisode)
                                } else {
                                    episode
                                }
                            },
                        nextUpEpisode =
                            content.nextUpEpisode?.let { episode ->
                                if (episode.itemId == selectedEpisode.itemId) {
                                    episode.withSourceProjectionFrom(selectedEpisode)
                                } else {
                                    episode
                                }
                            },
                        seriesPlayEpisode =
                            content.seriesPlayEpisode?.let { episode ->
                                if (episode.itemId == selectedEpisode.itemId) {
                                    episode.withSourceProjectionFrom(selectedEpisode)
                                } else {
                                    episode
                                }
                            },
                    ),
            )
        }
    }

    fun toggleFocusedEpisodeWatched() {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val previous = currentFocusedEpisode() ?: return
        val played = !previous.isWatched
        val restoreNextUpOnFailure = content.nextUpEpisode?.itemId == previous.itemId

        updateEpisode(previous.itemId) { episode ->
            episode.withWatched(played).copy(watchedToggleInFlight = true)
        }

        viewModelScope.launch {
            val result = setItemPlayedAction(itemId = previous.itemId, played = played)
            if (result.isSuccess) {
                updateEpisode(previous.itemId) { episode -> episode.copy(watchedToggleInFlight = false) }
            } else {
                updateEpisode(previous.itemId) { episode -> episode.withWatchedRollbackFrom(previous) }
                if (restoreNextUpOnFailure) {
                    restoreNextUpEpisode(previous.itemId)
                }
            }
        }
    }

    fun toggleSeriesFavorite() {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return
        val previous = content.series
        val favorite = !previous.isFavorite
        updateSeriesHeader(previous.itemId) { series ->
            series.copy(isFavorite = favorite, favoriteToggleInFlight = true)
        }

        viewModelScope.launch {
            val result = setItemFavoriteAction(itemId = previous.itemId, favorite = favorite)
            if (result.isSuccess) {
                updateSeriesHeader(previous.itemId) { series -> series.copy(favoriteToggleInFlight = false) }
            } else {
                updateSeriesHeader(previous.itemId) { series ->
                    series.copy(
                        isFavorite = previous.isFavorite,
                        favoriteToggleInFlight = false,
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        toggleSeriesFavorite()
    }

    fun toggleFocusedEpisodeFavorite() {
        val previous = currentFocusedEpisode() ?: return
        val favorite = !previous.isFavorite

        updateEpisode(previous.itemId) { episode ->
            episode.copy(isFavorite = favorite, favoriteToggleInFlight = true)
        }

        viewModelScope.launch {
            val result = setItemFavoriteAction(itemId = previous.itemId, favorite = favorite)
            if (result.isSuccess) {
                updateEpisode(previous.itemId) { episode -> episode.copy(favoriteToggleInFlight = false) }
            } else {
                updateEpisode(previous.itemId) { episode ->
                    episode.copy(
                        isFavorite = previous.isFavorite,
                        favoriteToggleInFlight = false,
                    )
                }
            }
        }
    }

    private fun load() {
        val generation = ++topLevelLoadGeneration
        viewModelScope.launch {
            _focusedEpisodeId.value = null
            _state.value = SeriesUiState.Loading
            clearEpisodeCache()

            // These four requests only need seriesId, so fire them concurrently
            // instead of awaiting one-by-one (was 4 serial round-trips before the
            // header could render). Related depends on the detail item, so it is
            // started once detail resolves but still overlaps seasons/next-up.
            val detailDeferred = async { getItemDetailUseCase(seriesId) }
            val seasonsDeferred = async { getSeriesSeasonsUseCase(seriesId) }
            val nextUpDeferred = async { getNextUpUseCase(seriesId) }

            val detailResult = detailDeferred.await()
            val relatedDeferred =
                detailResult.getOrNull()?.let { detail ->
                    async(workDispatcher) {
                        // The series screen keeps a single "Related" shelf; collect the
                        // streamed, deduped groups and flatten them into one list.
                        // On workDispatcher so the terminal collect + flatMap stay off Main.
                        getRelatedItemsUseCase(detail)
                            .catch { throwable ->
                                seriesViewModelLogger.w {
                                    formatSafeFailureDiagnostic(
                                        stage = "related-items",
                                        event = "failed",
                                        throwable = throwable,
                                    )
                                }
                            }.toList()
                            .flatMap { group -> group.items }
                    }
                }
            val seasonsResult = seasonsDeferred.await()
            val nextUpItem = nextUpDeferred.await().getOrElse { emptyList() }.firstOrNull()
            val related = relatedDeferred?.await().orEmpty()
            val formatterStrings = formatterStringsProvider()
            val nextUpRequestedSourceId = nextUpItem?.id?.let { itemId -> selectedMediaSourceIdsByEpisode[itemId] }
            val nextUpSourceId =
                nextUpItem
                    ?.versions
                    ?.selectedPlaybackVersion(nextUpRequestedSourceId)
                    ?.id
                    ?.takeIf(String::isNotBlank)
            if (nextUpItem != null && nextUpSourceId != null) {
                selectedMediaSourceIdsByEpisode[nextUpItem.id] = nextUpSourceId
            }
            val nextUpLaunchContext =
                if (nextUpItem != null && nextUpSourceId != null) {
                    withContext(workDispatcher) {
                        getPlaybackLaunchContextUseCase(session, nextUpItem.id, nextUpSourceId)
                    }
                } else {
                    null
                }
            val playbackPreferences = nextUpLaunchContext?.playbackPreferences ?: PlaybackPreferences().normalized()
            val nextUpRememberedSelection =
                if (nextUpItem != null && nextUpSourceId != null && nextUpLaunchContext != null) {
                    rememberedSelectionFor(nextUpItem.id, nextUpSourceId, nextUpLaunchContext)
                } else {
                    null
                }
            val nextUpStoredSubtitleSelection = nextUpLaunchContext?.subtitleSelection

            // Project DTOs to UI models off Main.
            val nextState =
                withContext(workDispatcher) {
                    detailResult.fold(
                        onSuccess = { detail ->
                            seasonsResult.fold(
                                onSuccess = { seasons ->
                                    val seasonUi = seasons.map { season -> season.toSeasonUi() }.sortedForTabs()
                                    val selectedSeason = seasonUi.initialSeason()
                                    val selectedSeasonId = selectedSeason?.id
                                    val nextUpEpisode =
                                        nextUpItem?.toEpisodeUi(
                                            seriesTitle = detail.item.name,
                                            selectedMediaSourceId = nextUpSourceId,
                                            playbackPreferences = playbackPreferences,
                                            formatterStrings = formatterStrings,
                                            rememberedSelection = nextUpRememberedSelection,
                                            storedSubtitleSelection = nextUpStoredSubtitleSelection,
                                        )
                                    SeriesUiState.Content(
                                        content =
                                            SeriesContentUi(
                                                series = detail.toSeriesHeaderUi(formatterStrings),
                                                seasons = seasonUi,
                                                selectedSeasonId = selectedSeasonId,
                                                episodesState =
                                                    if (selectedSeasonId == null) {
                                                        SeasonEpisodesUiState.Empty
                                                    } else {
                                                        SeasonEpisodesUiState.Loading
                                                    },
                                                focusedEpisode = null,
                                                nextUpEpisode = nextUpEpisode,
                                                seriesPlayEpisode = nextUpEpisode,
                                                related =
                                                    related.map { item ->
                                                        item.toMediaCardUi(session, imageUrlBuilder)
                                                    },
                                            ),
                                    )
                                },
                                onFailure = { SeriesUiState.Error() },
                            )
                        },
                        onFailure = { SeriesUiState.Error() },
                    )
                }

            if (generation != topLevelLoadGeneration) {
                return@launch
            }
            _state.update { nextState }
            val selectedSeasonId = (nextState as? SeriesUiState.Content)?.content?.selectedSeasonId
            val content = (nextState as? SeriesUiState.Content)?.content
            if (selectedSeasonId != null && content != null) {
                loadEpisodesForSeason(selectedSeasonId, content.seasonIndexFor(selectedSeasonId))
            }
        }
    }

    private fun loadEpisodesForSeason(
        seasonId: String,
        seasonIndex: Int?,
        onComplete: (() -> Unit)? = null,
    ) {
        episodeCache[seasonId]?.let { cached ->
            _state.updateEpisodesState(seasonId, cached.toEpisodesState())
            onComplete?.invoke()
            return
        }

        // Episode-strip commits are generation-guarded PER SEASON: when loads
        // for the same season overlap (e.g. an ON_RESUME reveal refresh racing
        // a settlement-triggered one), only the newest request may publish
        // episodes or the cache, so an older response finishing last never
        // reintroduces stale userData. The selected-season identity is part of
        // the request as well, so a response for a season the user left cannot
        // mutate cache aliases or source-selection state in the background.
        val generation = (episodesLoadGenerations[seasonId] ?: 0L) + 1L
        episodesLoadGenerations[seasonId] = generation
        val request =
            SeriesEpisodeProjectionRequest(
                seasonId = seasonId,
                generation = generation,
                selectedSeasonId = currentSelectedSeasonId(),
                mediaSourceSelectionGenerations = mediaSourceSelectionGenerations.toMap(),
            )
        val job =
            viewModelScope.launch {
                val result =
                    withContext(workDispatcher) {
                        getSeasonEpisodesUseCase(
                            seriesId = seriesId,
                            seasonId = seasonId,
                            seasonIndex = seasonIndex,
                        )
                    }
                result.fold(
                    onSuccess = { episodes ->
                        val currentContent = (_state.value as? SeriesUiState.Content)?.content ?: return@fold
                        val seriesTitle =
                            currentContent.series
                                .title
                                .orEmpty()
                        val selectedSourceIds =
                            episodes.associate { episode ->
                                val selectedSourceId =
                                    episode.versions
                                        .selectedPlaybackVersion(selectedMediaSourceIdsByEpisode[episode.id])
                                        ?.id
                                episode.id to selectedSourceId
                            }
                        val memorySnapshot =
                            playbackSelectionMemory.snapshotFor(
                                accountIdentity = session.accountIdentity(),
                                itemIds = episodes.map { episode -> episode.id },
                            )
                        val launchSource =
                            episodes.firstNotNullOfOrNull { episode ->
                                selectedSourceIds[episode.id]
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { sourceId -> episode.id to sourceId }
                            }
                        val playbackPreferences =
                            launchSource
                                ?.let { (episodeId, sourceId) ->
                                    withContext(workDispatcher) {
                                        getPlaybackLaunchContextUseCase(session, episodeId, sourceId)
                                    }.playbackPreferences
                                }
                                ?: PlaybackPreferences().normalized()
                        val formatterStrings = withContext(workDispatcher) { formatterStringsProvider() }
                        val storedSelections =
                            withContext(workDispatcher) {
                                try {
                                    getPlaybackSelectionsUseCase?.invoke(
                                        serverId = session.serverId,
                                        userId = session.userId,
                                        itemIds = episodes.map { episode -> episode.id },
                                    ) ?: emptyMap()
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Throwable) {
                                    emptyMap()
                                }
                            }
                        val storedSubtitleSelections =
                            withContext(workDispatcher) {
                                // One query instead of ~1200 serial Room reads (which left the
                                // strip blank for seconds). Keyed by (itemId, mediaSourceId).
                                try {
                                    getSubtitleSelectionsUseCase?.invoke(
                                        serverId = session.serverId,
                                        userId = session.userId,
                                        itemIds = episodes.map { episode -> episode.id },
                                    ) ?: emptyMap()
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Throwable) {
                                    // Match the per-episode currentSubtitleSelection behavior it
                                    // replaced: a subtitle-store read failure degrades to no
                                    // remembered selections rather than aborting the whole strip
                                    // projection (which would crash the launch and stick on Loading).
                                    seriesViewModelLogger.w {
                                        formatSafeFailureDiagnostic(
                                            stage = "subtitle-selection",
                                            event = "unavailable",
                                            operation = DiagnosticOperation.GetSubtitleSelections,
                                            throwable = exception,
                                        )
                                    }
                                    emptyMap()
                                }
                            }
                        val projection =
                            withContext(workDispatcher) {
                                projectEpisodes(
                                    episodes = episodes,
                                    seriesTitle = seriesTitle,
                                    selectedSourceIds = selectedSourceIds,
                                    playbackPreferences = playbackPreferences,
                                    formatterStrings = formatterStrings,
                                    storedSelections = storedSelections,
                                    memorySnapshot = memorySnapshot,
                                    storedSubtitleSelections = storedSubtitleSelections,
                                )
                            }
                        if (isCurrentEpisodeProjection(request)) {
                            val committedEpisodes =
                                projection.episodes.map { projectedEpisode ->
                                    if (isCurrentMediaSourceSelection(request, projectedEpisode.itemId)) {
                                        projectedEpisode
                                    } else {
                                        // A newer source-selection job owns only the source fields.
                                        // Keep the fresh server projection as the base so watched,
                                        // progress, title, and other fields still refresh.
                                        episodeForSelection(projectedEpisode.itemId)
                                            ?.let(projectedEpisode::withSourceProjectionFrom)
                                            ?: projectedEpisode
                                    }
                                }
                            projection.resolvedSourceIds.forEach { (itemId, resolvedSourceId) ->
                                if (!isCurrentMediaSourceSelection(request, itemId)) return@forEach
                                val requestedSourceId = selectedMediaSourceIdsByEpisode[itemId]
                                if (resolvedSourceId == null) {
                                    selectedMediaSourceIdsByEpisode.remove(itemId)
                                    mediaSourceSelectionJobs.remove(itemId)?.cancel()
                                    mediaSourceSelectionGenerations[itemId] =
                                        (mediaSourceSelectionGenerations[itemId] ?: 0L) + 1L
                                } else if (requestedSourceId != resolvedSourceId) {
                                    selectedMediaSourceIdsByEpisode[itemId] = resolvedSourceId
                                    mediaSourceSelectionJobs.remove(itemId)?.cancel()
                                    mediaSourceSelectionGenerations[itemId] =
                                        (mediaSourceSelectionGenerations[itemId] ?: 0L) + 1L
                                }
                            }
                            cacheSeasonEpisodes(seasonId, committedEpisodes)
                            if (currentSelectedSeasonId() == seasonId) {
                                _state.updateEpisodesState(seasonId, committedEpisodes.toEpisodesState())
                            }
                        }
                    },
                    onFailure = {
                        if (isCurrentEpisodeProjection(request)) {
                            _state.updateEpisodesState(seasonId, SeasonEpisodesUiState.Error)
                        }
                    },
                )
            }
        activeEpisodesLoadJob = job
        job.invokeOnCompletion {
            onComplete?.invoke()
            // Trailing settlement refresh: a settlement observed while this
            // load was in flight runs exactly one follow-up once it finishes.
            refreshAfterStopSettlement()
        }
    }

    private fun currentSelectedSeasonId(): String? =
        (_state.value as? SeriesUiState.Content)
            ?.content
            ?.selectedSeasonId

    private fun isCurrentEpisodeProjection(request: SeriesEpisodeProjectionRequest): Boolean =
        request.generation == episodesLoadGenerations[request.seasonId] &&
            currentSelectedSeasonId() == request.selectedSeasonId

    private fun isCurrentMediaSourceSelection(
        request: SeriesEpisodeProjectionRequest,
        itemId: String,
    ): Boolean = mediaSourceSelectionGenerations[itemId] == request.mediaSourceSelectionGenerations[itemId]

    private fun projectEpisodes(
        episodes: List<MediaItem>,
        seriesTitle: String,
        selectedSourceIds: Map<String, String?>,
        playbackPreferences: PlaybackPreferences,
        formatterStrings: DetailFormatterStrings,
        storedSelections: Map<Pair<String, String>, PlaybackSelection>,
        memorySnapshot: PlaybackSelectionSnapshot,
        storedSubtitleSelections: Map<Pair<String, String>, SubtitleSelectionIntent>,
    ): SeriesEpisodeProjectionResult {
        val resolvedSourceIds = mutableMapOf<String, String?>()
        val projected =
            episodes.sortedForStrip().map { episode ->
                val requestedSourceId = selectedSourceIds[episode.id]
                val selectedVersion = episode.versions.toMediaVersionUis(formatterStrings).selectedMediaVersion(requestedSourceId)
                resolvedSourceIds[episode.id] = selectedVersion?.id
                val rememberedSelection =
                    selectedVersion?.let { version ->
                        resolveRememberedSelection(
                            durable = storedSelections[episode.id to version.id],
                            sourceMemory = memorySnapshot.sourceSelections[episode.id to version.id],
                            legacyItemMemory = memorySnapshot.itemSelections[episode.id],
                            durableStoreAvailable = getPlaybackSelectionsUseCase != null,
                        )
                    }
                episode.toEpisodeUi(
                    seriesTitle = seriesTitle,
                    selectedMediaSourceId = requestedSourceId,
                    playbackPreferences = playbackPreferences,
                    formatterStrings = formatterStrings,
                    rememberedSelection = rememberedSelection,
                    storedSubtitleSelection =
                        selectedVersion?.let { version ->
                            storedSubtitleSelections[episode.id to version.id]
                        },
                )
            }
        return SeriesEpisodeProjectionResult(
            episodes = projected,
            resolvedSourceIds = resolvedSourceIds,
        )
    }

    private fun clearEpisodeCache() {
        episodeCache.clear()
        episodeCacheLocationsByItemId.clear()
    }

    private fun removeCachedSeason(seasonId: String) {
        val removed = episodeCache.remove(seasonId) ?: return
        removed.forEach { episode ->
            episodeCacheLocationsByItemId[episode.itemId]?.let { locations ->
                locations.remove(seasonId)
                if (locations.isEmpty()) {
                    episodeCacheLocationsByItemId.remove(episode.itemId)
                }
            }
        }
    }

    private fun cacheSeasonEpisodes(
        seasonId: String,
        episodes: List<EpisodeUi>,
    ) {
        removeCachedSeason(seasonId)
        episodeCache[seasonId] = episodes
        episodes.forEach { episode ->
            episodeCacheLocationsByItemId
                .getOrPut(episode.itemId) { mutableSetOf() }
                .add(seasonId)
        }
    }

    private fun currentFocusedEpisode(): EpisodeUi? {
        val content = (_state.value as? SeriesUiState.Content)?.content ?: return null
        val episodes = (content.episodesState as? SeasonEpisodesUiState.Content)?.episodes.orEmpty()
        return _focusedEpisodeId.value
            ?.let { itemId -> episodes.firstOrNull { episode -> episode.itemId == itemId } }
            ?: content.focusedEpisode
    }

    private fun MutableStateFlow<SeriesUiState>.updateEpisodesState(
        seasonId: String,
        episodesState: SeasonEpisodesUiState,
    ) {
        val previousSeasonId = (value as? SeriesUiState.Content)?.content?.selectedSeasonId
        if (previousSeasonId != seasonId) {
            _focusedEpisodeId.value = null
        }
        update { current ->
            val currentContent = current as? SeriesUiState.Content ?: return@update current
            currentContent.copy(content = currentContent.content.withEpisodesState(seasonId, episodesState))
        }
    }

    private fun SeriesContentUi.withEpisodesState(
        seasonId: String,
        episodesState: SeasonEpisodesUiState,
    ): SeriesContentUi {
        val episodes = (episodesState as? SeasonEpisodesUiState.Content)?.episodes.orEmpty()
        val focused =
            focusedEpisode
                ?.takeIf { selectedSeasonId == seasonId }
                ?.let { previous -> episodes.firstOrNull { episode -> episode.itemId == previous.itemId } }
                ?: episodes.firstOrNull()
        val refreshedNextUp =
            nextUpEpisode
                ?.let { nextUp -> episodes.firstOrNull { episode -> episode.itemId == nextUp.itemId } ?: nextUp }
                ?.takeUnless { episode -> episode.isWatched }
        return copy(
            selectedSeasonId = seasonId,
            episodesState = episodesState,
            focusedEpisode = focused,
            nextUpEpisode = refreshedNextUp,
            seriesPlayEpisode =
                refreshedNextUp
                    ?: episodes.nextPlayableEpisode()
                    ?: seriesPlayEpisode.takeIf { episodesState is SeasonEpisodesUiState.Loading },
        )
    }

    private fun updateEpisode(
        episodeId: String,
        transform: (EpisodeUi) -> EpisodeUi,
    ) {
        episodeCacheLocationsByItemId[episodeId].orEmpty().toList().forEach { seasonId ->
            val episodes = episodeCache[seasonId] ?: return@forEach
            cacheSeasonEpisodes(
                seasonId,
                episodes.map { episode ->
                    if (episode.itemId == episodeId) transform(episode) else episode
                },
            )
        }
        _state.update { current ->
            val currentContent = current as? SeriesUiState.Content ?: return@update current
            val content = currentContent.content
            val updateEpisode = { episode: EpisodeUi ->
                if (episode.itemId == episodeId) transform(episode) else episode
            }
            currentContent.copy(
                content =
                    content.copy(
                        episodesState =
                            (content.episodesState as? SeasonEpisodesUiState.Content)?.let { episodes ->
                                SeasonEpisodesUiState.Content(episodes.episodes.map(updateEpisode))
                            } ?: content.episodesState,
                        focusedEpisode = content.focusedEpisode?.let(updateEpisode),
                        nextUpEpisode = content.nextUpEpisode?.let(updateEpisode),
                        seriesPlayEpisode = content.seriesPlayEpisode?.let(updateEpisode),
                    ),
            )
        }
    }

    private fun restoreNextUpEpisode(episodeId: String) {
        _state.update { current ->
            val currentContent = current as? SeriesUiState.Content ?: return@update current
            val content = currentContent.content
            val episode =
                (content.episodesState as? SeasonEpisodesUiState.Content)
                    ?.episodes
                    ?.firstOrNull { candidate -> candidate.itemId == episodeId }
                    ?: return@update current
            if (episode.isWatched) {
                current
            } else {
                currentContent.copy(
                    content =
                        content.copy(
                            nextUpEpisode = episode,
                            seriesPlayEpisode = episode,
                        ),
                )
            }
        }
    }

    private fun updateSeriesHeader(
        seriesId: String,
        transform: (SeriesHeaderUi) -> SeriesHeaderUi,
    ) {
        _state.update { current ->
            val currentContent = current as? SeriesUiState.Content ?: return@update current
            val content = currentContent.content
            if (content.series.itemId != seriesId) {
                current
            } else {
                currentContent.copy(content = content.copy(series = transform(content.series)))
            }
        }
    }

    private fun MediaItemDetail.toSeriesHeaderUi(strings: DetailFormatterStrings): SeriesHeaderUi =
        SeriesHeaderUi(
            itemId = item.id,
            title = item.name,
            metadataLine =
                listOfNotNull(
                    productionYear?.toString(),
                    episodeRuntimeText(item.runtime, strings),
                ).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" · "),
            genresLine = genres.takeIf { values -> values.isNotEmpty() }?.joinToString(", "),
            officialRating = officialRating?.takeIf { value -> value.isNotBlank() },
            communityRating = communityRatingText(communityRating),
            criticRatingText = criticRatingText(criticRating),
            overview = overview,
            castAndCrew = castAndCrew(people).map { person -> person.toUi() },
            isFavorite = item.isFavorite,
            favoriteToggleInFlight = false,
            backdropUrl =
                imageUrl(
                    item = item,
                    type = JellyfinImageType.Backdrop,
                    tag = item.imageRefs.backdropTag,
                    maxWidth = 1280,
                ),
            posterUrl =
                imageUrl(
                    item = item,
                    type = JellyfinImageType.Primary,
                    tag = item.imageRefs.primaryTag,
                    maxWidth = 500,
                ),
        )

    private fun MediaItem.toSeasonUi(): SeasonUi =
        SeasonUi(
            id = id,
            title = name,
            indexNumber = indexNumber,
            unplayedCount = unplayedItemCount?.takeIf { count -> count > 0 },
            posterUrl =
                imageUrl(
                    item = this,
                    type = JellyfinImageType.Primary,
                    tag = imageRefs.primaryTag,
                    maxWidth = 300,
                ),
        )

    private fun MediaItem.toEpisodeUi(
        seriesTitle: String,
        selectedMediaSourceId: String?,
        playbackPreferences: PlaybackPreferences,
        formatterStrings: DetailFormatterStrings,
        rememberedSelection: PlaybackSelection?,
        storedSubtitleSelection: SubtitleSelectionIntent?,
    ): EpisodeUi {
        val decision =
            resumeDecision(
                playbackPositionTicks = playbackPositionTicks,
                runtime = runtime,
                played = played,
            )
        val startPositionTicks =
            when (decision) {
                ResumeDecision.Start, ResumeDecision.StartOver -> 0L
                is ResumeDecision.Resume -> playbackPositionTicks ?: 0L
            }
        val versionUis = versions.toMediaVersionUis(formatterStrings)
        val selectedVersion =
            versionUis
                .selectedMediaVersion(selectedMediaSourceId)
                ?.resolveLaunchDefaults(
                    playbackPreferences = playbackPreferences,
                    rememberedSelection = rememberedSelection,
                    storedSubtitleSelection = storedSubtitleSelection,
                )
        val mediaSourceId = selectedVersion?.id
        val episodeLabel =
            seasonEpisodeLabel(
                seasonNumber = parentIndexNumber,
                episodeNumber = indexNumber,
            )
        val airDate = airDateText(premiereDate, formatterStrings)
        val runtimeText = episodeRuntimeText(runtime, formatterStrings)

        return EpisodeUi(
            itemId = id,
            title = name,
            seriesTitle = seriesTitle.takeIf { title -> title.isNotBlank() } ?: seriesName.orEmpty(),
            episodeLabel = episodeLabel,
            episodeBadge = indexNumber?.let { number -> "E$number" },
            metadataLine =
                listOfNotNull(
                    episodeLabel,
                    airDate,
                    runtimeText,
                    officialRating,
                ).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" · "),
            overview = overview,
            imageUrl =
                imageUrl(
                    item = this,
                    type = JellyfinImageType.Primary,
                    tag = imageRefs.primaryTag,
                    maxWidth = 360,
                ),
            streamBadges = selectedVersion?.streamBadges.orEmpty(),
            mediaInfo = selectedVersion?.mediaInfo,
            timeLeftText =
                if (decision is ResumeDecision.Resume) {
                    timeLeftText(
                        runtime = runtime,
                        playbackPositionTicks = playbackPositionTicks,
                        strings = formatterStrings,
                    )
                } else {
                    null
                },
            playAction =
                DetailPlayAction(
                    label = decision.toPlayLabel(),
                    startPositionTicks = startPositionTicks,
                    mediaSourceId = mediaSourceId,
                ),
            restartAction =
                if (decision is ResumeDecision.Resume) {
                    DetailPlayAction(
                        label = DetailPlayLabel.StartOver,
                        startPositionTicks = 0L,
                        mediaSourceId = mediaSourceId,
                    )
                } else {
                    null
                },
            isWatched = played,
            watchedToggleInFlight = false,
            isFavorite = isFavorite,
            favoriteToggleInFlight = false,
            progressFraction =
                playedPercentage
                    ?.toFloat()
                    ?.div(100f)
                    ?.coerceIn(0f, 1f)
                    ?.takeIf { progress -> progress > 0f && progress < 1f },
            trackSelection = selectedVersion?.trackSelection ?: DetailTrackSelectionUi(),
            versions = versionUis.map { version -> if (version.id == mediaSourceId) selectedVersion else version },
            selectedMediaSourceId = mediaSourceId,
        )
    }

    private fun ResumeDecision.toPlayLabel(): DetailPlayLabel =
        when (this) {
            ResumeDecision.Start -> DetailPlayLabel.Start
            is ResumeDecision.Resume -> DetailPlayLabel.Resume(position)
            ResumeDecision.StartOver -> DetailPlayLabel.StartOver
        }

    private fun MediaPerson.toUi(): CastAndCrewUi =
        CastAndCrewUi(
            id = id,
            name = name,
            role = role?.takeIf { value -> value.isNotBlank() },
            fallbackRoleType = type.takeIf { role.isNullOrBlank() },
            imageUrl =
                primaryImageTag?.let { tag ->
                    imageUrlBuilder.personPrimary(
                        serverUrl = session.serverUrl,
                        personId = id,
                        tag = tag,
                    )
                },
        )

    private fun imageUrl(
        item: MediaItem,
        type: JellyfinImageType,
        tag: String?,
        maxWidth: Int,
    ): String? =
        tag?.let {
            imageUrlBuilder.build(
                serverUrl = session.serverUrl,
                itemId = item.id,
                type = type,
                tag = it,
                maxWidth = maxWidth,
            )
        }
}
