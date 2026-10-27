// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.DeleteLocalSubtitleAction
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
import com.jellyscope.core.domain.action.RetryLocalSubtitleSyncAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
import com.jellyscope.core.domain.usecase.SearchOpenSubtitlesUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvSubtitlesPresenter(
    private val request: TvSubtitlesRequest,
    private val getItemDetail: GetItemDetailUseCase,
    private val getPlaybackLaunchContext: GetPlaybackLaunchContextUseCase,
    private val searchOpenSubtitles: SearchOpenSubtitlesUseCase,
    private val downloadAndInstallOpenSubtitle: DownloadAndInstallOpenSubtitleAction,
    private val observeLocalSubtitleAssets: ObserveLocalSubtitleAssetsUseCase,
    private val getLocalSubtitleAsset: GetLocalSubtitleAssetUseCase,
    private val deleteLocalSubtitle: DeleteLocalSubtitleAction,
    private val retryLocalSubtitleSync: RetryLocalSubtitleSyncAction,
    private val saveSubtitleSelection: SaveSubtitleSelectionAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSubtitlesState())
    val state: StateFlow<TvSubtitlesState> = _state.asStateFlow()

    private var activeContext: ResolvedTvSubtitleContext? = null
    private var rawSearchResults: List<OpenSubtitleSearchResult> = emptyList()
    private var selectedAssetId: String? = null
    private var contextGeneration = 0L
    private var searchGeneration = 0L
    private var selectionGeneration = 0L
    private var contextJob: Job? = null
    private var searchJob: Job? = null
    private var installJob: Job? = null
    private var localAssetsJob: Job? = null
    private var deleteJob: Job? = null
    private var syncJob: Job? = null

    init {
        load()
    }

    private fun reduce(change: TvSubtitlesChange) = reduce { change }

    private fun reduce(change: () -> TvSubtitlesChange) {
        _state.update { current -> reduceTvSubtitles(current, change()) }
    }

    fun accept(intent: TvSubtitlesIntent) {
        when (intent) {
            TvSubtitlesIntent.Load -> load()
            is TvSubtitlesIntent.SetLanguage -> setLanguage(intent.language)
            TvSubtitlesIntent.Search -> search()
            is TvSubtitlesIntent.Install -> install(intent.fileId)
            is TvSubtitlesIntent.SelectLocalSubtitle -> selectLocalSubtitle(intent.assetId)
            is TvSubtitlesIntent.Delete -> delete(intent.assetId)
            is TvSubtitlesIntent.RetrySync -> retrySync(intent.assetId)
        }
    }

    fun watchState(onChange: (TvSubtitlesState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun load() {
        contextJob?.cancel()
        searchJob?.cancel()
        installJob?.cancel()
        localAssetsJob?.cancel()
        deleteJob?.cancel()
        syncJob?.cancel()
        activeContext = null
        rawSearchResults = emptyList()
        selectedAssetId = null
        val generation = ++contextGeneration
        searchGeneration += 1
        selectionGeneration += 1
        val selectionRevision = _state.value.selectionRevision
        reduce(TvSubtitlesChange.Reset(selectionRevision))
        contextJob =
            scope.launch {
                val resolution =
                    try {
                        withContext(workDispatcher) {
                            val detail = getItemDetail(request.itemId, includePlaybackFields = true).getOrThrow()
                            if (detail.item.kind != MediaKind.Movie && detail.item.kind != MediaKind.Episode) {
                                return@withContext ContextResolution.Unsupported
                            }
                            val versions = detail.tvDetailVersions()
                            val sourceId =
                                if (request.mediaSourceId == null) {
                                    versions.firstOrNull()?.id
                                } else {
                                    versions.firstOrNull { version -> version.id == request.mediaSourceId }?.id
                                }
                                    ?: return@withContext ContextResolution.Unsupported
                            val launchContext = getPlaybackLaunchContext(request.session, request.itemId, sourceId)
                            val resolved =
                                detail.resolveTvSubtitleContext(
                                    session = request.session,
                                    requestedMediaSourceId = sourceId,
                                ) ?: return@withContext ContextResolution.Unsupported
                            val localSelection = launchContext.subtitleSelection as? SubtitleSelectionIntent.LocalAsset
                            val validatedAssetId =
                                localSelection
                                    ?.assetId
                                    ?.let { assetId -> getLocalSubtitleAsset(assetId, resolved.context)?.id }
                            ContextResolution.Available(
                                context = resolved,
                                language = tvSubtitleLanguageCode(launchContext.playbackPreferences.preferredSubtitleLanguage),
                                selectedAssetId = validatedAssetId,
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logSubtitleFailure(
                            stage = "context",
                            event = "load-failed",
                            operation = DiagnosticOperation.GetItemDetail,
                            error = error,
                        )
                        ContextResolution.Failed
                    }
                if (generation != contextGeneration) return@launch
                when (resolution) {
                    is ContextResolution.Available -> {
                        activeContext = resolution.context
                        selectedAssetId = resolution.selectedAssetId
                        reduce(TvSubtitlesChange.Available(resolution.language, resolution.selectedAssetId))
                        observeLocalAssets(resolution.context, generation)
                        search()
                    }
                    ContextResolution.Unsupported -> {
                        reduce(TvSubtitlesChange.Unsupported)
                    }
                    ContextResolution.Failed -> {
                        reduce(TvSubtitlesChange.Failed)
                    }
                }
            }
    }

    private fun setLanguage(language: String) {
        if (state.value.languageChoices.none { choice -> choice.code == language }) return
        if (state.value.selectedLanguage == language) return
        reduce(TvSubtitlesChange.LanguageSelected(language))
        installJob?.cancel()
        installJob = null
        search()
    }

    private fun search() {
        val context = activeContext ?: return
        val contextVersion = contextGeneration
        val generation = ++searchGeneration
        val language = state.value.selectedLanguage
        searchJob?.cancel()
        rawSearchResults = emptyList()
        reduce(TvSubtitlesChange.SearchStarted)
        searchJob =
            scope.launch {
                val result =
                    try {
                        withContext(workDispatcher) {
                            val raw = searchOpenSubtitles(context.searchRequest(language))
                            SearchResult.Success(raw, raw.map(OpenSubtitleSearchResult::toTvOpenSubtitleResult))
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logSubtitleFailure(
                            stage = "search",
                            event = "failed",
                            operation = DiagnosticOperation.OpenSubtitleSearch,
                            error = error,
                        )
                        SearchResult.Failed
                    }
                if (!isCurrent(context, contextVersion) || generation != searchGeneration) return@launch
                when (result) {
                    is SearchResult.Success -> {
                        rawSearchResults = result.raw
                        reduce(TvSubtitlesChange.SearchLoaded(result.projected))
                    }
                    SearchResult.Failed -> {
                        reduce(TvSubtitlesChange.SearchFailed)
                    }
                }
            }
    }

    private fun install(fileId: String) {
        val context = activeContext ?: return
        val result = rawSearchResults.firstOrNull { candidate -> candidate.fileId == fileId && candidate.selectable } ?: return
        if (installJob?.isActive == true) return
        val contextVersion = contextGeneration
        reduce(TvSubtitlesChange.InstallStarted(fileId))
        installJob =
            scope.launch {
                val installed =
                    try {
                        withContext(workDispatcher) {
                            Result.success(downloadAndInstallOpenSubtitle(context.context, result))
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logSubtitleFailure(
                            stage = "download-install",
                            event = "failed",
                            operation = DiagnosticOperation.OpenSubtitleDownload,
                            error = error,
                        )
                        Result.failure(error)
                    }
                if (!isCurrent(context, contextVersion)) return@launch
                installed
                    .onSuccess { value ->
                        if (value.selectionApplied) selectedAssetId = value.asset.id
                        reduce(
                            TvSubtitlesChange.Installed(value.selectionApplied, value.asset.id, value.quotaRemaining, value.quotaResetTime),
                        )
                    }.onFailure {
                        reduce(TvSubtitlesChange.InstallFailed)
                    }
            }
    }

    private fun selectLocalSubtitle(assetId: String?) {
        val context = activeContext ?: return
        val contextVersion = contextGeneration
        val generation = ++selectionGeneration
        reduce(TvSubtitlesChange.SelectionStarted)
        scope.launch {
            val succeeded =
                try {
                    withContext(workDispatcher) {
                        if (assetId != null && getLocalSubtitleAsset(assetId, context.context) == null) {
                            false
                        } else {
                            val selection =
                                assetId?.let(SubtitleSelectionIntent::LocalAsset)
                                    ?: SubtitleSelectionIntent.Off
                            saveSubtitleSelection.save(context.context.selectionKey(), selection).await()
                            true
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logSubtitleFailure(
                        stage = "selection",
                        event = "save-failed",
                        operation = DiagnosticOperation.LocalSubtitleRead,
                        error = error,
                    )
                    false
                }
            if (!isCurrent(context, contextVersion) || generation != selectionGeneration) return@launch
            if (succeeded) selectedAssetId = assetId
            reduce(TvSubtitlesChange.SelectionFinished(succeeded, assetId))
        }
    }

    private fun delete(assetId: String) {
        val context = activeContext ?: return
        if (state.value.localSubtitles.none { asset -> asset.id == assetId }) return
        if (deleteJob?.isActive == true) return
        val contextVersion = contextGeneration
        reduce(TvSubtitlesChange.DeleteStarted(assetId))
        deleteJob =
            scope.launch {
                val succeeded =
                    try {
                        withContext(workDispatcher) {
                            val asset = getLocalSubtitleAsset(assetId, context.context) ?: return@withContext false
                            deleteLocalSubtitle(asset.id)
                            true
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logSubtitleFailure(
                            stage = "local-asset",
                            event = "delete-failed",
                            operation = DiagnosticOperation.ClearLocalSubtitles,
                            error = error,
                        )
                        false
                    }
                if (!isCurrent(context, contextVersion)) return@launch
                val clearedSelection = succeeded && selectedAssetId == assetId
                if (clearedSelection) selectedAssetId = null
                reduce(TvSubtitlesChange.DeleteFinished(succeeded, clearedSelection, assetId))
            }
    }

    private fun retrySync(assetId: String) {
        val context = activeContext ?: return
        val row = state.value.localSubtitles.firstOrNull { asset -> asset.id == assetId && asset.canRetrySync } ?: return
        if (syncJob?.isActive == true) return
        val contextVersion = contextGeneration
        reduce(TvSubtitlesChange.SyncStarted(row.id))
        syncJob =
            scope.launch {
                val succeeded =
                    try {
                        withContext(workDispatcher) {
                            val asset = getLocalSubtitleAsset(assetId, context.context) ?: return@withContext false
                            if (asset.syncState != LocalSubtitleSyncState.UploadedUnconfirmed) return@withContext false
                            retryLocalSubtitleSync(request.session, asset.id)
                            true
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logSubtitleFailure(
                            stage = "local-asset",
                            event = "sync-retry-failed",
                            operation = DiagnosticOperation.LocalSubtitleRefresh,
                            error = error,
                        )
                        false
                    }
                if (!isCurrent(context, contextVersion)) return@launch
                reduce(TvSubtitlesChange.SyncFinished(succeeded, assetId))
            }
    }

    private fun observeLocalAssets(
        context: ResolvedTvSubtitleContext,
        generation: Long,
    ) {
        localAssetsJob?.cancel()
        localAssetsJob =
            scope.launch {
                observeLocalSubtitleAssets(context.context).collect { assets ->
                    val rows =
                        withContext(workDispatcher) {
                            assets.map { asset -> asset.toTvLocalSubtitle(selectedAssetId) }
                        }
                    if (!isCurrent(context, generation)) return@collect
                    reduce(TvSubtitlesChange.AssetsLoaded(rows))
                }
            }
    }

    private fun isCurrent(
        context: ResolvedTvSubtitleContext,
        generation: Long,
    ): Boolean = generation == contextGeneration && activeContext?.context == context.context
}

private sealed interface ContextResolution {
    data class Available(
        val context: ResolvedTvSubtitleContext,
        val language: String,
        val selectedAssetId: String?,
    ) : ContextResolution

    data object Unsupported : ContextResolution

    data object Failed : ContextResolution
}

private sealed interface SearchResult {
    data class Success(
        val raw: List<OpenSubtitleSearchResult>,
        val projected: List<TvOpenSubtitleResult>,
    ) : SearchResult

    data object Failed : SearchResult
}

private fun LocalSubtitleContext.selectionKey(): SubtitleSelectionKey =
    SubtitleSelectionKey(
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
    )

private fun logSubtitleFailure(
    stage: String,
    event: String,
    operation: DiagnosticOperation,
    error: Throwable,
) {
    diagnosticLogger(DiagnosticTag.OpenSubtitles).w {
        formatSafeFailureDiagnostic(
            stage = stage,
            event = event,
            operation = operation,
            throwable = error,
        )
    }
}
