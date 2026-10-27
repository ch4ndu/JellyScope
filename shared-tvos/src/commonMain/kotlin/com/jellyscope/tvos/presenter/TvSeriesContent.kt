// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.ResumeDecision
import com.jellyscope.core.domain.playback.resumeDecision
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TvSeriesContent(
    private val session: Session,
    private val seriesId: String,
    private val initialSeasonId: String?,
    private val getSeriesSeasons: GetSeriesSeasonsUseCase,
    private val getSeasonEpisodes: GetSeasonEpisodesUseCase,
    private val getNextUp: GetNextUpUseCase,
    private val observePlaybackStopSettlement: ObservePlaybackStopSettlementUseCase,
    private val setItemPlayed: SetItemPlayedAction,
    private val setItemFavorite: SetItemFavoriteAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher,
    private val state: StateFlow<TvItemDetailState>,
    private val publish: (() -> TvSeriesChange) -> Unit,
) {
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var seasonsGeneration = 0L
    private var episodesGeneration = 0L
    private var explicitSeasonId: String? = initialSeasonId
    private var seasonSelectionRevision = 0L
    private val playedMutexes = mutableMapOf<String, Mutex>()
    private val favoriteMutexes = mutableMapOf<String, Mutex>()
    private val playedIntentRevisions = mutableMapOf<String, Long>()
    private val favoriteIntentRevisions = mutableMapOf<String, Long>()
    private val pendingPlayedRevisions = mutableMapOf<String, Long>()
    private val pendingFavoriteRevisions = mutableMapOf<String, Long>()
    private val confirmedPlayedProgress = mutableMapOf<String, TvPlayedProgress>()
    private val confirmedFavorite = mutableMapOf<String, Boolean>()
    private var episodeItems: Map<String, MediaItem> = emptyMap()
    private var highestObservedSettlementSequence = 0L
    private var highestRefreshedSettlementSequence = 0L
    private val relevantPlaybackItemIds =
        state
            .map { current ->
                current.episodes
                    .mapTo(mutableSetOf()) { episode -> episode.id }
                    .apply { current.nextUpEpisode?.id?.let { itemId -> add(itemId) } }
            }.distinctUntilChanged()

    init {
        observePlaybackStopSettlements()
    }

    private fun observePlaybackStopSettlements() {
        scope.launch {
            combine(
                observePlaybackStopSettlement(session.serverId, session.userId),
                relevantPlaybackItemIds,
            ) { settlements, relevantItemIds ->
                settlements.filter { settlement -> settlement.key.itemId in relevantItemIds }
            }.flowOn(workDispatcher).collect { settlements ->
                settlements.forEach { settlement -> recordStopSettlement(settlement.sequence) }
            }
        }
    }

    private fun recordStopSettlement(sequence: Long) {
        if (sequence <= highestObservedSettlementSequence) return
        highestObservedSettlementSequence = sequence
        refreshAfterStopSettlement()
    }

    private fun refreshAfterStopSettlement() {
        if (
            highestRefreshedSettlementSequence >= highestObservedSettlementSequence ||
            seasonsJob?.isActive == true ||
            episodesJob?.isActive == true
        ) {
            return
        }
        val current = state.value
        if (current.episodes.isEmpty() && current.nextUpEpisode == null) return
        val refreshedSequence = highestObservedSettlementSequence
        load {
            highestRefreshedSettlementSequence =
                maxOf(highestRefreshedSettlementSequence, refreshedSequence)
        }
    }

    private fun reduce(change: TvSeriesChange) = publish { change }

    private fun reduce(change: () -> TvSeriesChange) = publish(change)

    fun load() = load(onComplete = null)

    private fun load(onComplete: (() -> Unit)?) {
        val generation = ++seasonsGeneration
        seasonsJob?.cancel()
        val selectionRevision = seasonSelectionRevision
        val playedRevisionsAtLoad = playedIntentRevisions.toMap()
        val pendingPlayedAtLoad = pendingPlayedRevisions.keys.toSet()
        reduce(TvSeriesChange.LoadStarted)
        var completionDelegated = false
        val job =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            coroutineScope {
                                val seasons = async { getSeriesSeasons(seriesId) }
                                val nextUp = async { getNextUp(seriesId = seriesId) }
                                SeriesLoadResult(seasons.await(), nextUp.await())
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            SeriesLoadResult(Result.failure(error), Result.failure(error))
                        }
                    }
                if (generation != seasonsGeneration) return@launch
                val seasons =
                    withContext(workDispatcher) {
                        result.seasons
                            .getOrNull()
                            ?.map { season ->
                                TvSeason(
                                    id = season.id,
                                    name = season.name,
                                    indexNumber = season.indexNumber,
                                    imageUrl =
                                        season.imageRefs.primaryTag?.let { tag ->
                                            imageUrlBuilder.build(
                                                serverUrl = session.serverUrl,
                                                itemId = season.id,
                                                type = JellyfinImageType.Primary,
                                                tag = tag,
                                                maxWidth = CARD_IMAGE_MAX_WIDTH,
                                            )
                                        },
                                )
                            }.orEmpty()
                    }
                val loadedNextUp = result.nextUp.getOrNull()?.firstOrNull()
                var nextUpProjection: Pair<MediaItem?, TvMediaCard?>? = null
                while (nextUpProjection == null) {
                    val currentPlayedRevisions = playedIntentRevisions.toMap()
                    val currentPendingPlayed = pendingPlayedRevisions.toMap()
                    val currentNextUpCard = state.value.nextUpEpisode
                    val nextUp =
                        loadedNextUp?.let { episode ->
                            val preservePlayed =
                                episode.id in pendingPlayedAtLoad ||
                                    episode.id in currentPendingPlayed ||
                                    playedRevisionsAtLoad[episode.id] != currentPlayedRevisions[episode.id]
                            if (preservePlayed) {
                                episode.withPlayedProgress(
                                    episodeItems[episode.id]?.playedProgress()
                                        ?: currentNextUpCard
                                            ?.takeIf { card -> card.id == episode.id }
                                            ?.playedProgress()
                                        ?: episode.playedProgress(),
                                )
                            } else {
                                episode
                            }
                        }
                    val nextUpCard = withContext(workDispatcher) { nextUp?.toTvMediaCard(session, imageUrlBuilder) }
                    if (
                        currentPlayedRevisions == playedIntentRevisions &&
                        currentPendingPlayed == pendingPlayedRevisions
                    ) {
                        nextUpProjection = nextUp to nextUpCard
                    }
                }
                val (nextUp, nextUpCard) = checkNotNull(nextUpProjection)
                val selectionChanged = selectionRevision != seasonSelectionRevision
                val availableSeasons =
                    if (result.seasons.isSuccess && !selectionChanged) seasons else state.value.seasons
                val nextUpSeasonId =
                    nextUp?.seasonId?.takeIf { id -> availableSeasons.any { season -> season.id == id } }
                val requestedSeasonId = explicitSeasonId ?: state.value.selectedSeasonId ?: nextUpSeasonId
                val selectedSeasonId =
                    requestedSeasonId?.takeIf { id -> availableSeasons.any { season -> season.id == id } }
                        ?: availableSeasons.firstOrNull()?.id
                reduce {
                    TvSeriesChange.SeasonsLoaded(
                        generation == seasonsGeneration,
                        result.seasons.isSuccess,
                        result.nextUp.isSuccess,
                        selectionChanged,
                        seasons,
                        result.seasons.exceptionOrNull()?.toTvErrorKind(),
                        result.nextUp.exceptionOrNull()?.toTvErrorKind(),
                        nextUpCard,
                        nextUpSeasonId,
                    )
                }
                if (generation == seasonsGeneration && !selectionChanged && selectedSeasonId != null) {
                    completionDelegated = true
                    loadEpisodes(selectedSeasonId, onComplete)
                }
            }
        seasonsJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (generation == seasonsGeneration) {
                    if (seasonsJob === job) seasonsJob = null
                    if (!completionDelegated) onComplete?.invoke()
                    refreshAfterStopSettlement()
                }
            }
        }
    }

    fun selectSeason(seasonId: String) {
        if (state.value.seasons.none { season -> season.id == seasonId }) return
        explicitSeasonId = seasonId
        seasonSelectionRevision += 1L
        loadEpisodes(seasonId)
    }

    fun retryEpisodes() {
        state.value.selectedSeasonId?.let { seasonId -> loadEpisodes(seasonId) }
    }

    fun togglePlayed(itemId: String) {
        val item = state.value.episodes.firstOrNull { episode -> episode.id == itemId } ?: return
        val desired = !item.played
        val revision = (playedIntentRevisions[itemId] ?: 0L) + 1L
        playedIntentRevisions[itemId] = revision
        pendingPlayedRevisions[itemId] = revision
        if (itemId !in confirmedPlayedProgress) {
            confirmedPlayedProgress[itemId] = episodeItems[itemId]?.playedProgress() ?: item.playedProgress()
        }
        val optimisticProgress = TvPlayedProgress(played = desired, playbackPositionTicks = 0L)
        updateEpisodeItem(itemId) { episode -> episode.withPlayedProgress(optimisticProgress) }
        updateEpisode(itemId) { episode -> episode.withPlayedProgress(optimisticProgress) }
        scope.launch {
            playedMutexes.getOrPut(itemId) { Mutex() }.withLock {
                withContext(workDispatcher) { setItemPlayed(itemId, desired) }
                    .onSuccess {
                        confirmedPlayedProgress[itemId] = optimisticProgress
                        if (pendingPlayedRevisions[itemId] == revision) {
                            pendingPlayedRevisions.remove(itemId)
                        }
                    }.onFailure { error ->
                        if (pendingPlayedRevisions[itemId] == revision) {
                            val confirmed = confirmedPlayedProgress[itemId] ?: item.playedProgress()
                            updateEpisodeItem(itemId) { episode -> episode.withPlayedProgress(confirmed) }
                            updateEpisode(itemId) { episode -> episode.withPlayedProgress(confirmed) }
                            pendingPlayedRevisions.remove(itemId)
                            reduce(TvSeriesChange.ActionFailed(error.toTvErrorKind()))
                        }
                    }
            }
        }
    }

    fun toggleFavorite(itemId: String) {
        val item = state.value.episodes.firstOrNull { episode -> episode.id == itemId } ?: return
        val desired = !item.isFavorite
        val revision = (favoriteIntentRevisions[itemId] ?: 0L) + 1L
        favoriteIntentRevisions[itemId] = revision
        pendingFavoriteRevisions[itemId] = revision
        if (itemId !in confirmedFavorite) confirmedFavorite[itemId] = item.isFavorite
        updateEpisodeItem(itemId) { episode -> episode.copy(isFavorite = desired) }
        updateEpisode(itemId) { episode -> episode.copy(isFavorite = desired) }
        scope.launch {
            favoriteMutexes.getOrPut(itemId) { Mutex() }.withLock {
                withContext(workDispatcher) { setItemFavorite(itemId, desired) }
                    .onSuccess {
                        confirmedFavorite[itemId] = desired
                        if (pendingFavoriteRevisions[itemId] == revision) {
                            pendingFavoriteRevisions.remove(itemId)
                        }
                    }.onFailure { error ->
                        if (pendingFavoriteRevisions[itemId] == revision) {
                            val confirmed = confirmedFavorite[itemId] ?: item.isFavorite
                            updateEpisodeItem(itemId) { episode -> episode.copy(isFavorite = confirmed) }
                            updateEpisode(itemId) { episode -> episode.copy(isFavorite = confirmed) }
                            pendingFavoriteRevisions.remove(itemId)
                            reduce(TvSeriesChange.ActionFailed(error.toTvErrorKind()))
                        }
                    }
            }
        }
    }

    fun playbackSelection(
        itemId: String,
        restart: Boolean,
    ): TvDetailPlaybackSelection? {
        val episode = episodeItems[itemId] ?: return null
        val decision = resumeDecision(episode.playbackPositionTicks, episode.runtime, episode.played)
        val startPositionTicks =
            if (!restart && decision is ResumeDecision.Resume) {
                episode.playbackPositionTicks ?: 0L
            } else {
                0L
            }
        return TvDetailPlaybackSelection(
            itemId = episode.id,
            mediaSourceId = null,
            startPositionTicks = startPositionTicks,
            audioStreamIndex = null,
            subtitleMode = TvPlaybackSubtitleMode.Unspecified,
            subtitleStreamIndex = null,
        )
    }

    fun playMode(itemId: String): TvDetailPlayMode {
        val episode = episodeItems[itemId] ?: return TvDetailPlayMode.Play
        return when (resumeDecision(episode.playbackPositionTicks, episode.runtime, episode.played)) {
            ResumeDecision.Start -> TvDetailPlayMode.Play
            is ResumeDecision.Resume -> TvDetailPlayMode.Resume
            ResumeDecision.StartOver -> TvDetailPlayMode.Restart
        }
    }

    fun canRestart(itemId: String): Boolean {
        val episode = episodeItems[itemId] ?: return false
        return resumeDecision(episode.playbackPositionTicks, episode.runtime, episode.played) is ResumeDecision.Resume
    }

    private fun loadEpisodes(
        seasonId: String,
        onComplete: (() -> Unit)? = null,
    ) {
        val generation = ++episodesGeneration
        episodesJob?.cancel()
        val playedRevisionsAtLoad = playedIntentRevisions.toMap()
        val favoriteRevisionsAtLoad = favoriteIntentRevisions.toMap()
        val pendingPlayedAtLoad = pendingPlayedRevisions.keys.toSet()
        val pendingFavoriteAtLoad = pendingFavoriteRevisions.keys.toSet()
        reduce(TvSeriesChange.EpisodesStarted(seasonId))
        val job =
            scope.launch {
                val loadedResult =
                    withContext(workDispatcher) {
                        try {
                            getSeasonEpisodes(seriesId = seriesId, seasonId = seasonId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != episodesGeneration || state.value.selectedSeasonId != seasonId) return@launch
                var result: Result<EpisodeProjection>? = null
                while (result == null) {
                    val currentCardList = state.value.episodes
                    val currentItems = episodeItems
                    val currentPlayedRevisions = playedIntentRevisions.toMap()
                    val currentFavoriteRevisions = favoriteIntentRevisions.toMap()
                    val currentPendingPlayed = pendingPlayedRevisions.toMap()
                    val currentPendingFavorite = pendingFavoriteRevisions.toMap()
                    val candidate =
                        withContext(workDispatcher) {
                            val currentCards = currentCardList.associateBy(TvMediaCard::id)
                            loadedResult.map { episodes ->
                                val preservedPlayedIds = mutableSetOf<String>()
                                val preservedFavoriteIds = mutableSetOf<String>()
                                val projectedItems =
                                    episodes.map { episode ->
                                        val currentCard = currentCards[episode.id]
                                        val currentItem = currentItems[episode.id]
                                        val preservePlayed =
                                            episode.id in pendingPlayedAtLoad ||
                                                episode.id in currentPendingPlayed ||
                                                playedRevisionsAtLoad[episode.id] != currentPlayedRevisions[episode.id]
                                        val preserveFavorite =
                                            episode.id in pendingFavoriteAtLoad ||
                                                episode.id in currentPendingFavorite ||
                                                favoriteRevisionsAtLoad[episode.id] != currentFavoriteRevisions[episode.id]
                                        if (preservePlayed) preservedPlayedIds += episode.id
                                        if (preserveFavorite) preservedFavoriteIds += episode.id
                                        val playedProgress =
                                            if (preservePlayed) {
                                                currentItem?.playedProgress()
                                                    ?: currentCard?.playedProgress()
                                                    ?: episode.playedProgress()
                                            } else {
                                                episode.playedProgress()
                                            }
                                        episode.withPlayedProgress(playedProgress).copy(
                                            isFavorite =
                                                if (preserveFavorite) {
                                                    currentItem?.isFavorite ?: currentCard?.isFavorite ?: episode.isFavorite
                                                } else {
                                                    episode.isFavorite
                                                },
                                        )
                                    }
                                EpisodeProjection(
                                    items = projectedItems.associateBy(MediaItem::id),
                                    cards =
                                        projectedItems.map { episode ->
                                            episode.toTvMediaCard(session, imageUrlBuilder)
                                        },
                                    preservedPlayedIds = preservedPlayedIds,
                                    preservedFavoriteIds = preservedFavoriteIds,
                                )
                            }
                        }
                    if (generation != episodesGeneration || state.value.selectedSeasonId != seasonId) return@launch
                    if (
                        currentPlayedRevisions == playedIntentRevisions &&
                        currentFavoriteRevisions == favoriteIntentRevisions &&
                        currentPendingPlayed == pendingPlayedRevisions &&
                        currentPendingFavorite == pendingFavoriteRevisions
                    ) {
                        result = candidate
                    }
                }
                val mergedResult = result ?: return@launch
                mergedResult
                    .onSuccess { projection ->
                        episodeItems = projection.items
                        projection.items.values.forEach { episode ->
                            if (episode.id !in projection.preservedPlayedIds) {
                                confirmedPlayedProgress[episode.id] = episode.playedProgress()
                            }
                            if (episode.id !in projection.preservedFavoriteIds) {
                                confirmedFavorite[episode.id] = episode.isFavorite
                            }
                        }
                        reduce { TvSeriesChange.EpisodesLoaded(generation == episodesGeneration, seasonId, projection.cards) }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        reduce { TvSeriesChange.EpisodesFailed(generation == episodesGeneration, seasonId, error.toTvErrorKind()) }
                    }
            }
        episodesJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (generation == episodesGeneration) {
                    if (episodesJob === job) episodesJob = null
                    onComplete?.invoke()
                    refreshAfterStopSettlement()
                }
            }
        }
    }

    private fun updateEpisode(
        itemId: String,
        transform: (TvMediaCard) -> TvMediaCard,
    ) {
        reduce(TvSeriesChange.EpisodeChanged(itemId, transform))
    }

    private fun updateEpisodeItem(
        itemId: String,
        transform: (MediaItem) -> MediaItem,
    ) {
        val item = episodeItems[itemId] ?: return
        episodeItems = episodeItems + (itemId to transform(item))
    }
}

private fun MediaItem.withPlayedProgress(progress: TvPlayedProgress): MediaItem =
    copy(
        played = progress.played,
        playbackPositionTicks = progress.playbackPositionTicks,
        playedPercentage = progress.playedPercentage,
    )

private fun TvMediaCard.playedProgress(): TvPlayedProgress =
    TvPlayedProgress(
        played = played,
        playbackPositionTicks = playbackPositionTicks,
        playedPercentage = progressPercent,
    )

private fun TvMediaCard.withPlayedProgress(progress: TvPlayedProgress): TvMediaCard =
    copy(
        played = progress.played,
        playbackPositionTicks = progress.playbackPositionTicks ?: 0L,
        progressPercent = progress.playedPercentage,
    )

private data class SeriesLoadResult(
    val seasons: Result<List<com.jellyscope.core.domain.model.MediaItem>>,
    val nextUp: Result<List<com.jellyscope.core.domain.model.MediaItem>>,
)

private data class EpisodeProjection(
    val items: Map<String, MediaItem>,
    val cards: List<TvMediaCard>,
    val preservedPlayedIds: Set<String>,
    val preservedFavoriteIds: Set<String>,
)
