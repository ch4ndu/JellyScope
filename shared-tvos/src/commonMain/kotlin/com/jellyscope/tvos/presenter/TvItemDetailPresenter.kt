// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.domain.usecase.visibleRelatedGroups
import com.jellyscope.core.playback.SettlementKey
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TvItemDetailPresenter(
    private val session: Session,
    private val itemId: String,
    private val getItemDetail: GetItemDetailUseCase,
    getSeriesSeasons: GetSeriesSeasonsUseCase,
    getSeasonEpisodes: GetSeasonEpisodesUseCase,
    getNextUp: GetNextUpUseCase,
    private val getRelatedItems: GetRelatedItemsUseCase,
    private val observePlaybackStopSettlement: ObservePlaybackStopSettlementUseCase,
    private val setItemPlayed: SetItemPlayedAction,
    private val setItemFavorite: SetItemFavoriteAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    dispatchers: TvosDispatchers,
    initialSeasonId: String? = null,
    private val getPlaybackLaunchContext: GetPlaybackLaunchContextUseCase = GetPlaybackLaunchContextUseCase(),
    private val getLocalSubtitleAsset: GetLocalSubtitleAssetUseCase? = null,
    private val saveSubtitleSelection: SaveSubtitleSelectionAction? = null,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvItemDetailState())
    val state: StateFlow<TvItemDetailState> = _state.asStateFlow()

    private val seriesContent =
        TvSeriesContent(
            session = session,
            seriesId = itemId,
            initialSeasonId = initialSeasonId,
            getSeriesSeasons = getSeriesSeasons,
            getSeasonEpisodes = getSeasonEpisodes,
            getNextUp = getNextUp,
            observePlaybackStopSettlement = observePlaybackStopSettlement,
            setItemPlayed = setItemPlayed,
            setItemFavorite = setItemFavorite,
            imageUrlBuilder = imageUrlBuilder,
            scope = scope,
            workDispatcher = workDispatcher,
            state = state,
            publish = { change -> reduce { TvItemDetailChange.Series(change()) } },
        )
    private var detail: MediaItemDetail? = null
    private var playbackIntent = TvDetailPlaybackIntent()
    private var selectedLocalSubtitleAssetId: String? = null
    private var detailJob: Job? = null
    private var optionJob: Job? = null
    private var relatedJob: Job? = null
    private var localSubtitleSelectionJob: Job? = null
    private var detailGeneration = 0L
    private var optionGeneration = 0L
    private var localSubtitleSelectionGeneration = 0L
    private val playedMutationMutex = Mutex()
    private val favoriteMutationMutex = Mutex()
    private var playedIntentGeneration = 0L
    private var favoriteIntentGeneration = 0L
    private var pendingPlayedIntentGeneration: Long? = null
    private var pendingFavoriteIntentGeneration: Long? = null
    private var lastConfirmedPlayedProgress = TvPlayedProgress()
    private var lastConfirmedFavorite = false
    private var highestObservedSettlementSequence = 0L
    private var highestRefreshedSettlementSequence = 0L

    init {
        observePlaybackStopSettlements()
    }

    private fun reduce(change: TvItemDetailChange) = reduce { change }

    private fun reduce(change: () -> TvItemDetailChange) {
        _state.update { current -> reduceTvItemDetail(current, change()) }
    }

    fun accept(intent: TvItemDetailIntent) {
        when (intent) {
            TvItemDetailIntent.Load -> load()
            is TvItemDetailIntent.SelectVersion -> selectVersion(intent.mediaSourceId)
            is TvItemDetailIntent.SelectAudio -> selectAudio(intent.streamIndex)
            is TvItemDetailIntent.SelectSubtitle -> selectSubtitle(intent.streamIndex)
            is TvItemDetailIntent.SelectLocalSubtitle -> selectLocalSubtitle(intent.assetId)
            TvItemDetailIntent.TogglePlayed -> togglePlayed()
            TvItemDetailIntent.ToggleFavorite -> toggleFavorite()
            is TvItemDetailIntent.SelectSeason -> selectSeason(intent.seasonId)
            TvItemDetailIntent.RetrySeasons -> retrySeasons()
            TvItemDetailIntent.RetryEpisodes -> retryEpisodes()
            is TvItemDetailIntent.ToggleEpisodePlayed -> toggleEpisodePlayed(intent.itemId)
            is TvItemDetailIntent.ToggleEpisodeFavorite -> toggleEpisodeFavorite(intent.itemId)
        }
    }

    fun watchState(onChange: (TvItemDetailState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun observePlaybackStopSettlements() {
        scope.launch {
            observePlaybackStopSettlement(
                SettlementKey(session.serverId, session.userId, itemId),
            ).collect { settlement -> recordStopSettlement(settlement.sequence) }
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
            state.value.content == null ||
            detailJob?.isActive == true
        ) {
            return
        }
        val refreshedSequence = highestObservedSettlementSequence
        load {
            highestRefreshedSettlementSequence =
                maxOf(highestRefreshedSettlementSequence, refreshedSequence)
        }
    }

    private fun load() = load(onComplete = null)

    private fun load(onComplete: (() -> Unit)?) {
        val generation = ++detailGeneration
        detailJob?.cancel()
        optionJob?.cancel()
        relatedJob?.cancel()
        val localSubtitleAssetSnapshot = selectedLocalSubtitleAssetId
        val playedGeneration = playedIntentGeneration
        val favoriteGeneration = favoriteIntentGeneration
        val pendingPlayedGeneration = pendingPlayedIntentGeneration
        val pendingFavoriteGeneration = pendingFavoriteIntentGeneration
        val retained = _state.value.content
        reduce(TvItemDetailChange.LoadStarted(retained != null))
        val job =
            scope.launch {
                val intentSnapshot = playbackIntent
                val result =
                    withContext(workDispatcher) {
                        try {
                            getItemDetail(itemId, includePlaybackFields = true).map { loaded ->
                                val versions = loaded.tvDetailVersions()
                                val sourceId =
                                    intentSnapshot.mediaSourceId
                                        ?.takeIf { requested -> versions.any { version -> version.id == requested } }
                                        ?: versions.firstOrNull()?.id
                                val normalizedIntent = intentSnapshot.retainFor(itemId, sourceId)
                                val launchContext = sourceId?.let { getPlaybackLaunchContext(session, itemId, it) } ?: emptyLaunchContext()
                                val validLocalSubtitleAssetId =
                                    sourceId?.let { selectedSourceId ->
                                        resolveLocalSubtitleAssetId(
                                            sourceId = selectedSourceId,
                                            intent = normalizedIntent,
                                            explicitAssetId = localSubtitleAssetSnapshot,
                                            launchContext = launchContext,
                                        )
                                    }
                                LoadedDetail(
                                    detail = loaded,
                                    playbackIntent = normalizedIntent,
                                    selectedLocalSubtitleAssetId = validLocalSubtitleAssetId,
                                    projection =
                                        loaded.toTvDetailProjection(
                                            session = session,
                                            imageUrlBuilder = imageUrlBuilder,
                                            requestedMediaSourceId = sourceId,
                                            explicitAudioStreamIndex = normalizedIntent.explicitAudioStreamIndex,
                                            explicitSubtitleMode = normalizedIntent.explicitSubtitleMode,
                                            explicitSubtitleStreamIndex = normalizedIntent.explicitSubtitleStreamIndex,
                                            validatedLocalSubtitleAssetId = validLocalSubtitleAssetId,
                                            launchContext = launchContext,
                                        ),
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != detailGeneration) return@launch
                result
                    .onSuccess { loaded ->
                        val currentContent = _state.value.content
                        val preservePlayed =
                            playedGeneration != playedIntentGeneration ||
                                pendingPlayedGeneration != null ||
                                pendingPlayedIntentGeneration != null
                        val preserveFavorite =
                            favoriteGeneration != favoriteIntentGeneration ||
                                pendingFavoriteGeneration != null ||
                                pendingFavoriteIntentGeneration != null
                        val playedProgress =
                            if (preservePlayed) {
                                detail?.item?.playedProgress()
                                    ?: currentContent?.playedProgress()
                                    ?: loaded.detail.item.playedProgress()
                            } else {
                                loaded.detail.item.playedProgress()
                            }
                        val resolvedDetail = loaded.detail.withPlayedProgress(playedProgress)
                        val resolvedProjection =
                            loaded.projection.copy(
                                content =
                                    loaded.projection.content.withPlayedStatus(
                                        played = resolvedDetail.item.played,
                                        item = resolvedDetail.item,
                                    ),
                            )
                        if (!preservePlayed) lastConfirmedPlayedProgress = playedProgress
                        detail = resolvedDetail
                        if (intentSnapshot != playbackIntent || localSubtitleAssetSnapshot != selectedLocalSubtitleAssetId) {
                            projectOptions(resolvedDetail, playbackIntent, selectedLocalSubtitleAssetId)
                            collectRelated(resolvedDetail, generation)
                            if (resolvedDetail.item.kind == MediaKind.Series) {
                                seriesContent.load()
                            } else {
                                clearSeriesState()
                            }
                            return@onSuccess
                        }
                        playbackIntent = loaded.playbackIntent
                        selectedLocalSubtitleAssetId = loaded.selectedLocalSubtitleAssetId
                        val content =
                            resolvedProjection.content.copy(
                                isFavorite =
                                    if (preserveFavorite) {
                                        currentContent?.isFavorite ?: resolvedProjection.content.isFavorite
                                    } else {
                                        resolvedProjection.content.isFavorite
                                    },
                            )
                        if (!preserveFavorite) lastConfirmedFavorite = content.isFavorite
                        publishProjection(resolvedProjection, content)
                        collectRelated(resolvedDetail, generation)
                        if (resolvedDetail.item.kind == MediaKind.Series) {
                            seriesContent.load()
                        } else {
                            clearSeriesState()
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        reduce(TvItemDetailChange.LoadFailed(error.toTvErrorKind()))
                    }
            }
        detailJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (generation == detailGeneration) {
                    if (detailJob === job) detailJob = null
                    onComplete?.invoke()
                    refreshAfterStopSettlement()
                }
            }
        }
    }

    private fun selectVersion(mediaSourceId: String) {
        val loaded = detail ?: return
        if (_state.value.versions.none { version -> version.id == mediaSourceId }) return
        invalidateLocalSubtitleSelection()
        playbackIntent = playbackIntent.selectVersion(itemId, mediaSourceId)
        selectedLocalSubtitleAssetId = null
        reduce(TvItemDetailChange.VersionSelected(mediaSourceId))
        projectOptions(loaded, playbackIntent, selectedLocalSubtitleAssetId)
    }

    private fun selectAudio(streamIndex: Int) {
        if (_state.value.audioTracks.none { track -> track.streamIndex == streamIndex }) return
        playbackIntent = playbackIntent.selectAudio(streamIndex)
        reduce(TvItemDetailChange.AudioSelected(streamIndex))
    }

    private fun selectSubtitle(streamIndex: Int?) {
        if (streamIndex != null && _state.value.subtitleTracks.none { track -> track.streamIndex == streamIndex }) return
        val sourceId = _state.value.selectedMediaSourceId
        invalidateLocalSubtitleSelection()
        playbackIntent = playbackIntent.selectSubtitle(streamIndex)
        selectedLocalSubtitleAssetId = null
        reduce(TvItemDetailChange.SubtitleSelected(streamIndex))
        if (sourceId != null) {
            val selection =
                streamIndex?.let(SubtitleSelectionIntent::Track)
                    ?: SubtitleSelectionIntent.Off
            saveSubtitleSelection?.save(
                LocalSubtitleContext(session.serverId, session.userId, itemId, sourceId).selectionKey(),
                selection,
            )
        }
    }

    private fun selectLocalSubtitle(assetId: String?) {
        val sourceId = state.value.selectedMediaSourceId ?: return
        val getAsset = getLocalSubtitleAsset
        val saveSelection = saveSubtitleSelection
        if (getAsset == null || saveSelection == null) {
            invalidateLocalSubtitleSelection()
            reduce(TvItemDetailChange.LocalSelectionFailed)
            return
        }
        val context = LocalSubtitleContext(session.serverId, session.userId, itemId, sourceId)
        localSubtitleSelectionJob?.cancel()
        val generation = ++localSubtitleSelectionGeneration
        reduce(TvItemDetailChange.LocalSelectionStarted)
        localSubtitleSelectionJob =
            scope.launch {
                val isValid =
                    try {
                        withContext(workDispatcher) {
                            assetId == null || getAsset(assetId, context) != null
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        false
                    }
                if (generation != localSubtitleSelectionGeneration || state.value.selectedMediaSourceId != sourceId) {
                    return@launch
                }
                if (!isValid) {
                    publishLocalSubtitleFailure(generation, sourceId)
                    return@launch
                }
                val selection =
                    assetId?.let(SubtitleSelectionIntent::LocalAsset)
                        ?: SubtitleSelectionIntent.Off
                val write = saveSelection.save(context.selectionKey(), selection)
                val succeeded =
                    try {
                        withContext(workDispatcher) {
                            write.await()
                            true
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        false
                    }
                if (generation != localSubtitleSelectionGeneration || state.value.selectedMediaSourceId != sourceId) {
                    return@launch
                }
                if (!succeeded) {
                    publishLocalSubtitleFailure(generation, sourceId)
                    return@launch
                }
                selectedLocalSubtitleAssetId = assetId
                playbackIntent = playbackIntent.selectSubtitle(null)
                reduce(TvItemDetailChange.LocalSelectionSaved(assetId))
            }
    }

    fun playbackSelection(restart: Boolean = false): TvDetailPlaybackSelection? {
        val current = state.value
        if (current.isSubtitleSelectionPending || current.subtitleSelectionFailed) return null
        val content = current.content?.takeIf(TvItemDetailContent::isPlayable) ?: return null
        return TvDetailPlaybackSelection(
            itemId = content.id,
            mediaSourceId = current.selectedMediaSourceId,
            startPositionTicks = if (restart) 0L else content.playStartPositionTicks,
            audioStreamIndex = playbackIntent.explicitAudioStreamIndex,
            subtitleMode = current.subtitleMode,
            subtitleStreamIndex = current.selectedSubtitleStreamIndex,
            subtitleAssetId = current.selectedSubtitleAssetId,
        )
    }

    fun nextUpPlaybackSelection(): TvDetailPlaybackSelection? {
        val episode = state.value.nextUpEpisode?.takeIf { card -> card.kind == TvCardKind.Episode } ?: return null
        return TvDetailPlaybackSelection(
            itemId = episode.id,
            mediaSourceId = null,
            startPositionTicks = episode.playbackPositionTicks,
            audioStreamIndex = null,
            subtitleMode = TvPlaybackSubtitleMode.Unspecified,
            subtitleStreamIndex = null,
            subtitleAssetId = null,
        )
    }

    fun episodePlaybackSelection(
        itemId: String,
        restart: Boolean = false,
    ): TvDetailPlaybackSelection? = seriesContent.playbackSelection(itemId, restart)

    fun episodePlayMode(itemId: String): TvDetailPlayMode = seriesContent.playMode(itemId)

    fun episodeCanRestart(itemId: String): Boolean = seriesContent.canRestart(itemId)

    private fun togglePlayed() {
        val content = state.value.content ?: return
        if (content.kind != TvCardKind.Movie && content.kind != TvCardKind.Episode) return
        val targetItemId = content.id
        val target = !content.played
        val generation = ++playedIntentGeneration
        pendingPlayedIntentGeneration = generation
        val optimisticProgress = TvPlayedProgress(played = target, playbackPositionTicks = 0L)
        detail = detail?.withPlayedProgress(optimisticProgress)
        reduce { TvItemDetailChange.PlayedChanged(targetItemId, target, detail?.item) }
        scope.launch {
            playedMutationMutex.withLock {
                val result = withContext(workDispatcher) { setItemPlayed(targetItemId, target) }
                result
                    .onSuccess {
                        lastConfirmedPlayedProgress = optimisticProgress
                        if (pendingPlayedIntentGeneration == generation) {
                            pendingPlayedIntentGeneration = null
                        }
                    }.onFailure { error ->
                        if (pendingPlayedIntentGeneration == generation) {
                            pendingPlayedIntentGeneration = null
                            detail = detail?.withPlayedProgress(lastConfirmedPlayedProgress)
                            reduce {
                                TvItemDetailChange.PlayedRolledBack(
                                    targetItemId,
                                    lastConfirmedPlayedProgress.played,
                                    detail?.item,
                                    error.toTvErrorKind(),
                                )
                            }
                        }
                    }
            }
        }
    }

    private fun toggleFavorite() {
        val content = state.value.content ?: return
        val targetItemId = content.id
        val target = !content.isFavorite
        val generation = ++favoriteIntentGeneration
        pendingFavoriteIntentGeneration = generation
        reduce(TvItemDetailChange.FavoriteChanged(targetItemId, target))
        scope.launch {
            favoriteMutationMutex.withLock {
                val result = withContext(workDispatcher) { setItemFavorite(targetItemId, target) }
                result
                    .onSuccess {
                        lastConfirmedFavorite = target
                        if (pendingFavoriteIntentGeneration == generation) {
                            pendingFavoriteIntentGeneration = null
                        }
                    }.onFailure { error ->
                        if (pendingFavoriteIntentGeneration == generation) {
                            pendingFavoriteIntentGeneration = null
                            reduce { TvItemDetailChange.FavoriteRolledBack(targetItemId, lastConfirmedFavorite, error.toTvErrorKind()) }
                        }
                    }
            }
        }
    }

    private fun selectSeason(seasonId: String) = seriesContent.selectSeason(seasonId)

    private fun retrySeasons() = seriesContent.load()

    private fun retryEpisodes() = seriesContent.retryEpisodes()

    private fun toggleEpisodePlayed(itemId: String) = seriesContent.togglePlayed(itemId)

    private fun toggleEpisodeFavorite(itemId: String) = seriesContent.toggleFavorite(itemId)

    private fun invalidateLocalSubtitleSelection() {
        localSubtitleSelectionGeneration += 1
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
    }

    private fun publishLocalSubtitleFailure(
        generation: Long,
        sourceId: String,
    ) {
        if (generation != localSubtitleSelectionGeneration || state.value.selectedMediaSourceId != sourceId) return
        reduce(TvItemDetailChange.LocalSelectionFailed)
    }

    private fun projectOptions(
        loaded: MediaItemDetail,
        intent: TvDetailPlaybackIntent,
        localSubtitleAssetId: String?,
    ) {
        if (intent != playbackIntent || localSubtitleAssetId != selectedLocalSubtitleAssetId) return
        reduce(TvItemDetailChange.OptionsStarted)
        optionJob?.cancel()
        val generation = ++optionGeneration
        optionJob =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            val versions = loaded.tvDetailVersions()
                            val sourceId =
                                intent.mediaSourceId
                                    ?.takeIf { requested -> versions.any { version -> version.id == requested } }
                                    ?: versions.firstOrNull()?.id
                            val normalizedIntent = intent.retainFor(itemId, sourceId)
                            val launchContext = sourceId?.let { getPlaybackLaunchContext(session, itemId, it) } ?: emptyLaunchContext()
                            val validLocalSubtitleAssetId =
                                sourceId?.let { selectedSourceId ->
                                    resolveLocalSubtitleAssetId(
                                        sourceId = selectedSourceId,
                                        intent = normalizedIntent,
                                        explicitAssetId = localSubtitleAssetId,
                                        launchContext = launchContext,
                                    )
                                }
                            Result.success(
                                ProjectedOptions(
                                    playbackIntent = normalizedIntent,
                                    selectedLocalSubtitleAssetId = validLocalSubtitleAssetId,
                                    projection =
                                        loaded.toTvDetailProjection(
                                            session = session,
                                            imageUrlBuilder = imageUrlBuilder,
                                            requestedMediaSourceId = sourceId,
                                            explicitAudioStreamIndex = normalizedIntent.explicitAudioStreamIndex,
                                            explicitSubtitleMode = normalizedIntent.explicitSubtitleMode,
                                            explicitSubtitleStreamIndex = normalizedIntent.explicitSubtitleStreamIndex,
                                            validatedLocalSubtitleAssetId = validLocalSubtitleAssetId,
                                            launchContext = launchContext,
                                        ),
                                ),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != optionGeneration) return@launch
                if (intent != playbackIntent || localSubtitleAssetId != selectedLocalSubtitleAssetId) {
                    projectOptions(loaded, playbackIntent, selectedLocalSubtitleAssetId)
                    return@launch
                }
                result
                    .onSuccess { projected ->
                        playbackIntent = projected.playbackIntent
                        selectedLocalSubtitleAssetId = projected.selectedLocalSubtitleAssetId
                        val currentContent = _state.value.content
                        val currentItem =
                            detail?.item?.takeIf { item -> item.id == loaded.item.id }
                                ?: loaded.item
                        val played = currentContent?.played ?: currentItem.played
                        publishProjection(
                            projected.projection,
                            projected.projection.content
                                .withPlayedStatus(played, currentItem)
                                .copy(
                                    isFavorite =
                                        currentContent?.isFavorite
                                            ?: projected.projection.content.isFavorite,
                                ),
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        reduce(TvItemDetailChange.ActionFailed(error.toTvErrorKind()))
                    }
            }
    }

    private fun publishProjection(
        projection: TvDetailProjection,
        content: TvItemDetailContent,
    ) {
        reduce(TvItemDetailChange.Projected(projection, content))
    }

    private suspend fun resolveLocalSubtitleAssetId(
        sourceId: String,
        intent: TvDetailPlaybackIntent,
        explicitAssetId: String?,
        launchContext: PlaybackLaunchContext,
    ): String? {
        val getAsset = getLocalSubtitleAsset ?: return null
        val candidate =
            explicitAssetId
                ?: if (intent.explicitSubtitleMode == null) {
                    (launchContext.subtitleSelection as? SubtitleSelectionIntent.LocalAsset)?.assetId
                } else {
                    null
                }
        val context = LocalSubtitleContext(session.serverId, session.userId, itemId, sourceId)
        return candidate?.let { assetId -> getAsset(assetId, context)?.id }
    }

    private fun collectRelated(
        loaded: MediaItemDetail,
        generation: Long,
    ) {
        relatedJob?.cancel()
        relatedJob =
            scope.launch {
                val groups = mutableListOf<TvRelatedGroup>()
                try {
                    getRelatedItems(loaded)
                        .visibleRelatedGroups()
                        .flowOn(workDispatcher)
                        .collect { group ->
                            val projected =
                                withContext(workDispatcher) {
                                    TvRelatedGroup(
                                        kind = group.kind,
                                        label = group.label,
                                        items = group.items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                                    )
                                }
                            if (generation != detailGeneration) return@collect
                            groups += projected
                            reduce(TvItemDetailChange.RelatedLoaded(groups.toList()))
                        }
                    if (generation == detailGeneration) {
                        reduce(TvItemDetailChange.RelatedFinished)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    if (generation == detailGeneration) {
                        reduce(TvItemDetailChange.RelatedFinished)
                    }
                }
            }
    }

    private fun clearSeriesState() {
        reduce(TvItemDetailChange.SeriesCleared)
    }
}

internal data class TvPlayedProgress(
    val played: Boolean = false,
    val playbackPositionTicks: Long? = null,
    val playedPercentage: Double? = null,
)

internal fun MediaItem.playedProgress(): TvPlayedProgress =
    TvPlayedProgress(
        played = played,
        playbackPositionTicks = playbackPositionTicks,
        playedPercentage = playedPercentage,
    )

private fun TvItemDetailContent.playedProgress(): TvPlayedProgress =
    TvPlayedProgress(
        played = played,
        playbackPositionTicks = resumePositionTicks,
        playedPercentage = playedPercentage,
    )

private fun MediaItemDetail.withPlayedProgress(progress: TvPlayedProgress): MediaItemDetail =
    copy(
        item =
            item.copy(
                played = progress.played,
                playbackPositionTicks = progress.playbackPositionTicks,
                playedPercentage = progress.playedPercentage,
            ),
    )

private data class LoadedDetail(
    val detail: MediaItemDetail,
    val playbackIntent: TvDetailPlaybackIntent,
    val selectedLocalSubtitleAssetId: String?,
    val projection: TvDetailProjection,
)

private data class ProjectedOptions(
    val playbackIntent: TvDetailPlaybackIntent,
    val selectedLocalSubtitleAssetId: String?,
    val projection: TvDetailProjection,
)

private fun LocalSubtitleContext.selectionKey(): SubtitleSelectionKey =
    SubtitleSelectionKey(
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
    )

private fun emptyLaunchContext(): PlaybackLaunchContext =
    PlaybackLaunchContext(
        playbackPreferences = PlaybackPreferences(),
        playbackSelection = null,
        subtitleSelection = null,
        playbackPreferencesOutcome = PlaybackLaunchReadOutcome.Unavailable,
        playbackSelectionOutcome = PlaybackLaunchReadOutcome.Unavailable,
        subtitleSelectionOutcome = PlaybackLaunchReadOutcome.Unavailable,
    )
