// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.EnqueueDownloadAction
import com.jellyscope.core.domain.action.EnqueueFixedDownloadAction
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.usecase.FixedDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.FixedDownloadCapability
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.PreviewFixedDownloadUseCase
import com.jellyscope.core.domain.usecase.PreviewOriginalDownloadUseCase
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
import kotlin.random.Random
import kotlin.time.Clock

class TvDownloadRequestPresenter(
    private val request: TvDownloadRequest,
    private val getItemDetail: GetItemDetailUseCase,
    private val getLocalSubtitleAsset: GetLocalSubtitleAssetUseCase,
    private val previewOriginalDownload: PreviewOriginalDownloadUseCase,
    private val enqueueDownload: EnqueueDownloadAction,
    private val previewFixedDownload: PreviewFixedDownloadUseCase,
    private val enqueueFixedDownload: EnqueueFixedDownloadAction,
    private val fixedDownloadCapability: FixedDownloadCapability?,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvDownloadRequestState())
    val state: StateFlow<TvDownloadRequestState> = _state.asStateFlow()

    private var loaded: TvLoadedDownloadRequest? = null
    private var readyDraft: ReadyDraft? = null
    private var generation = 0L
    private var operationJob: Job? = null

    init {
        load()
    }

    private fun reduce(change: TvDownloadRequestChange) {
        _state.update { current -> reduceTvDownloadRequest(current, change) }
    }

    fun accept(intent: TvDownloadRequestIntent) {
        when (intent) {
            TvDownloadRequestIntent.RetryLoad -> retryLoad()
            is TvDownloadRequestIntent.SelectQuality -> selectQuality(intent.qualityId)
            is TvDownloadRequestIntent.SetFixedSubtitleOff -> setFixedSubtitleOff(intent.off)
            TvDownloadRequestIntent.Preview -> preview()
            TvDownloadRequestIntent.ConfirmOriginalWithoutSubtitle -> confirmOriginalWithoutSubtitle()
            TvDownloadRequestIntent.ConfirmFixedBurnIn -> confirmFixedBurnIn()
            TvDownloadRequestIntent.DismissConfirmation -> dismissConfirmation()
            TvDownloadRequestIntent.Enqueue -> enqueue()
        }
    }

    fun watchState(onChange: (TvDownloadRequestState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun retryLoad() {
        load()
    }

    private fun selectQuality(qualityId: String) {
        if (state.value.qualityChoices.none { choice -> choice.id == qualityId }) return
        invalidateOperation()
        val fixed =
            state.value.qualityChoices
                .first { choice -> choice.id == qualityId }
                .kind == TvDownloadQualityKind.Fixed
        reduce(TvDownloadRequestChange.QualitySelected(qualityId, fixed))
    }

    private fun setFixedSubtitleOff(off: Boolean) {
        if (selectedQuality()?.kind != TvDownloadQualityKind.Fixed) return
        invalidateOperation()
        reduce(TvDownloadRequestChange.SubtitleOffChanged(off))
    }

    private fun preview() {
        if (loaded == null) {
            load()
        } else {
            startPreview(originalBitmapConfirmed = false, fixedBurnInConfirmed = false)
        }
    }

    private fun confirmOriginalWithoutSubtitle() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle) return
        startPreview(originalBitmapConfirmed = true, fixedBurnInConfirmed = false)
    }

    private fun confirmFixedBurnIn() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmFixedBurnIn) return
        startPreview(originalBitmapConfirmed = false, fixedBurnInConfirmed = true)
    }

    private fun dismissConfirmation() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle &&
            state.value.stage != TvDownloadRequestStage.ConfirmFixedBurnIn
        ) {
            return
        }
        invalidateOperation()
        reduce(TvDownloadRequestChange.ConfirmationDismissed)
    }

    private fun enqueue() {
        val ready = readyDraft ?: return
        if (state.value.stage != TvDownloadRequestStage.Ready) return
        val operationGeneration = ++generation
        reduce(TvDownloadRequestChange.EnqueueStarted)
        operationJob =
            scope.launch {
                val result =
                    try {
                        withContext(workDispatcher) {
                            when (ready) {
                                is ReadyDraft.Original -> enqueueDownload(ready.draft)
                                is ReadyDraft.Fixed -> enqueueFixedDownload(ready.draft)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logRequestFailure("enqueue", DiagnosticOperation.DownloadLoad, error)
                        DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.NetworkUnavailable)
                    }
                if (operationGeneration != generation || readyDraft !== ready) return@launch
                readyDraft = null
                reduce(TvDownloadRequestChange.EnqueueFinished(result))
            }
    }

    private fun load() {
        invalidateOperation()
        loaded = null
        val loadGeneration = generation
        reduce(TvDownloadRequestChange.LoadPublished(TvDownloadRequestState(stage = TvDownloadRequestStage.Loading)))
        if (!request.session.enableContentDownloading) {
            reduce(
                TvDownloadRequestChange.LoadPublished(
                    TvDownloadRequestState(
                        stage = TvDownloadRequestStage.Failed,
                        error = TvDownloadRequestError.PermissionDenied,
                    ),
                ),
            )
            return
        }
        operationJob =
            scope.launch {
                val result =
                    try {
                        withContext(workDispatcher) {
                            loadRequest()?.let { loadedRequest ->
                                TvDownloadRequestLoadResult(
                                    loaded = loadedRequest,
                                    summary = loadedRequest.summary(request.selection),
                                    qualityChoices = loadedRequest.qualityChoices(fixedDownloadCapability != null),
                                    fixedSubtitleRequirement = loadedRequest.fixedSubtitleRequirement(request.selection),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logRequestFailure("load", DiagnosticOperation.GetItemDetail, error)
                        null
                    }
                if (loadGeneration != generation) return@launch
                if (result == null) {
                    reduce(
                        TvDownloadRequestChange.LoadPublished(
                            TvDownloadRequestState(
                                stage = TvDownloadRequestStage.Failed,
                                error = TvDownloadRequestError.SourceChanged,
                            ),
                        ),
                    )
                    return@launch
                }
                loaded = result.loaded
                reduce(
                    TvDownloadRequestChange.LoadPublished(
                        TvDownloadRequestState(
                            stage = TvDownloadRequestStage.Choosing,
                            summary = result.summary,
                            qualityChoices = result.qualityChoices,
                            selectedQualityId = TV_DOWNLOAD_ORIGINAL_QUALITY_ID,
                            fixedSubtitleRequirement = result.fixedSubtitleRequirement,
                        ),
                    ),
                )
            }
    }

    private suspend fun loadRequest(): TvLoadedDownloadRequest? {
        val detail = getItemDetail(request.selection.itemId, includePlaybackFields = true).getOrNull() ?: return null
        if (detail.item.id != request.selection.itemId ||
            (
                detail.item.kind != com.jellyscope.core.domain.model.MediaKind.Movie &&
                    detail.item.kind != com.jellyscope.core.domain.model.MediaKind.Episode
            )
        ) {
            return null
        }
        val versions = detail.versions.ifEmpty { detail.item.versions }.filter { version -> version.id.isNotBlank() }
        val requestedSourceId = request.selection.mediaSourceId
        val version =
            if (requestedSourceId == null) {
                versions.firstOrNull()
            } else {
                versions.firstOrNull { candidate -> candidate.id == requestedSourceId }
            } ?: return null
        val localAsset =
            if (request.selection.subtitleMode == TvPlaybackSubtitleMode.LocalAsset) {
                val assetId = request.selection.subtitleAssetId ?: return null
                getLocalSubtitleAsset(
                    assetId,
                    LocalSubtitleContext(
                        serverId = request.session.serverId,
                        userId = request.session.userId,
                        itemId = request.selection.itemId,
                        mediaSourceId = version.id,
                    ),
                ) ?: return null
            } else {
                null
            }
        return TvLoadedDownloadRequest(
            detail = detail,
            version = version,
            audioStreamIndex = version.resolvedAudioStreamIndex(request.selection.audioStreamIndex),
            localSubtitleAsset = localAsset,
        )
    }

    private fun startPreview(
        originalBitmapConfirmed: Boolean,
        fixedBurnInConfirmed: Boolean,
    ) {
        val loadedRequest = loaded ?: return
        val quality = selectedQuality() ?: return
        if (quality.kind == TvDownloadQualityKind.Fixed &&
            state.value.fixedSubtitleRequirement == TvFixedSubtitleRequirement.OffRequired &&
            !state.value.fixedSubtitleOff
        ) {
            reduce(TvDownloadRequestChange.SubtitleUnavailable)
            return
        }
        invalidateOperation()
        val operationGeneration = generation
        val identity = newTvDownloadIdentity()
        val buildResult =
            loadedRequest.buildDraft(
                session = request.session,
                selection = request.selection,
                quality = quality,
                fixedSubtitleOff = state.value.fixedSubtitleOff,
                originalBitmapConfirmed = originalBitmapConfirmed,
                fixedBurnInConfirmed = fixedBurnInConfirmed,
                identity = identity,
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        when (buildResult) {
            is TvDownloadDraftBuildResult.OriginalBitmapConfirmation -> {
                reduce(TvDownloadRequestChange.OriginalConfirmation(buildResult.subtitleLabel))
                return
            }
            is TvDownloadDraftBuildResult.FixedBurnInConfirmation -> {
                reduce(TvDownloadRequestChange.BurnInConfirmation(buildResult.subtitleLabel))
                return
            }
            TvDownloadDraftBuildResult.SubtitleUnavailable -> {
                reduce(TvDownloadRequestChange.SubtitleUnavailable)
                return
            }
            TvDownloadDraftBuildResult.Unsupported -> {
                reduce(TvDownloadRequestChange.SourceChanged)
                return
            }
            is TvDownloadDraftBuildResult.OriginalReady,
            is TvDownloadDraftBuildResult.FixedReady,
            -> Unit
        }
        reduce(TvDownloadRequestChange.PreviewStarted)
        operationJob =
            scope.launch {
                val preview =
                    try {
                        withContext(workDispatcher) {
                            when (buildResult) {
                                is TvDownloadDraftBuildResult.OriginalReady -> previewOriginalDownload(buildResult.draft)
                                is TvDownloadDraftBuildResult.FixedReady -> previewFixedDownload(buildResult.draft)
                                else -> error("Unexpected download draft state.")
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logRequestFailure("preview", DiagnosticOperation.DownloadLoad, error)
                        null
                    }
                if (operationGeneration != generation) return@launch
                when (preview) {
                    is OriginalDownloadAdmissionResult.Ready -> {
                        readyDraft = ReadyDraft.Original((buildResult as TvDownloadDraftBuildResult.OriginalReady).draft)
                        reduce(TvDownloadRequestChange.Ready(preview.request.admissionEstimateBytes))
                    }
                    is FixedDownloadAdmissionResult.Ready -> {
                        readyDraft = ReadyDraft.Fixed((buildResult as TvDownloadDraftBuildResult.FixedReady).draft)
                        reduce(TvDownloadRequestChange.Ready(preview.request.admissionEstimateBytes))
                    }
                    is OriginalDownloadAdmissionResult.Rejected -> rejectPreview(preview.decision)
                    is FixedDownloadAdmissionResult.Rejected -> rejectPreview(preview.decision)
                    null -> rejectPreview(DownloadAdmissionDecision.NetworkUnavailable)
                }
            }
    }

    private fun rejectPreview(decision: DownloadAdmissionDecision) {
        readyDraft = null
        reduce(TvDownloadRequestChange.PreviewRejected(decision.toTvDownloadRequestError()))
    }

    private fun selectedQuality(): TvDownloadQualityChoice? =
        state.value.qualityChoices.firstOrNull { choice -> choice.id == state.value.selectedQualityId }

    private fun invalidateOperation() {
        generation += 1L
        operationJob?.cancel()
        operationJob = null
        readyDraft = null
    }
}

private sealed interface ReadyDraft {
    data class Original(
        val draft: OriginalDownloadDraft,
    ) : ReadyDraft

    data class Fixed(
        val draft: FixedDownloadDraft,
    ) : ReadyDraft
}

private data class TvDownloadRequestLoadResult(
    val loaded: TvLoadedDownloadRequest,
    val summary: TvDownloadRequestSummary,
    val qualityChoices: List<TvDownloadQualityChoice>,
    val fixedSubtitleRequirement: TvFixedSubtitleRequirement,
)

private fun newTvDownloadIdentity(): String =
    "download-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toString().removePrefix("-")}"

private fun logRequestFailure(
    stage: String,
    operation: DiagnosticOperation,
    error: Throwable,
) {
    tvDownloadRequestLogger.w {
        formatSafeFailureDiagnostic(
            stage = stage,
            event = "failed",
            operation = operation,
            throwable = error,
        )
    }
}

private val tvDownloadRequestLogger = diagnosticLogger(DiagnosticTag.DownloadsViewModel)
