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

    fun watchState(onChange: (TvDownloadRequestState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun retryLoad() {
        load()
    }

    fun selectQuality(qualityId: String) {
        if (state.value.qualityChoices.none { choice -> choice.id == qualityId }) return
        invalidateOperation()
        val fixed =
            state.value.qualityChoices
                .first { choice -> choice.id == qualityId }
                .kind == TvDownloadQualityKind.Fixed
        _state.update { current ->
            current.copy(
                stage = TvDownloadRequestStage.Choosing,
                selectedQualityId = qualityId,
                fixedSubtitleOff = if (fixed) current.fixedSubtitleOff else false,
                estimatedBytes = null,
                warningSubtitleLabel = null,
                error = null,
                outcome = null,
            )
        }
    }

    fun setFixedSubtitleOff(off: Boolean) {
        if (selectedQuality()?.kind != TvDownloadQualityKind.Fixed) return
        invalidateOperation()
        _state.update { current ->
            current.copy(
                stage = TvDownloadRequestStage.Choosing,
                fixedSubtitleOff = off,
                estimatedBytes = null,
                warningSubtitleLabel = null,
                error = null,
                outcome = null,
            )
        }
    }

    fun preview() {
        if (loaded == null) {
            load()
        } else {
            startPreview(originalBitmapConfirmed = false, fixedBurnInConfirmed = false)
        }
    }

    fun confirmOriginalWithoutSubtitle() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle) return
        startPreview(originalBitmapConfirmed = true, fixedBurnInConfirmed = false)
    }

    fun confirmFixedBurnIn() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmFixedBurnIn) return
        startPreview(originalBitmapConfirmed = false, fixedBurnInConfirmed = true)
    }

    fun dismissConfirmation() {
        if (state.value.stage != TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle &&
            state.value.stage != TvDownloadRequestStage.ConfirmFixedBurnIn
        ) {
            return
        }
        invalidateOperation()
        _state.update { current ->
            current.copy(
                stage = TvDownloadRequestStage.Choosing,
                warningSubtitleLabel = null,
                error = null,
            )
        }
    }

    fun enqueue() {
        val ready = readyDraft ?: return
        if (state.value.stage != TvDownloadRequestStage.Ready) return
        val operationGeneration = ++generation
        _state.update { current -> current.copy(stage = TvDownloadRequestStage.Submitting, error = null) }
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
                _state.update { current -> result.applyTo(current) }
            }
    }

    private fun load() {
        invalidateOperation()
        loaded = null
        val loadGeneration = generation
        _state.value = TvDownloadRequestState(stage = TvDownloadRequestStage.Loading)
        if (!request.session.enableContentDownloading) {
            _state.value =
                TvDownloadRequestState(
                    stage = TvDownloadRequestStage.Failed,
                    error = TvDownloadRequestError.PermissionDenied,
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
                    _state.value =
                        TvDownloadRequestState(
                            stage = TvDownloadRequestStage.Failed,
                            error = TvDownloadRequestError.SourceChanged,
                        )
                    return@launch
                }
                loaded = result.loaded
                _state.value =
                    TvDownloadRequestState(
                        stage = TvDownloadRequestStage.Choosing,
                        summary = result.summary,
                        qualityChoices = result.qualityChoices,
                        selectedQualityId = TV_DOWNLOAD_ORIGINAL_QUALITY_ID,
                        fixedSubtitleRequirement = result.fixedSubtitleRequirement,
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
            _state.update { current ->
                current.copy(stage = TvDownloadRequestStage.Choosing, error = TvDownloadRequestError.SubtitleUnavailable)
            }
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
                _state.update { current ->
                    current.copy(
                        stage = TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle,
                        warningSubtitleLabel = buildResult.subtitleLabel,
                        error = null,
                    )
                }
                return
            }
            is TvDownloadDraftBuildResult.FixedBurnInConfirmation -> {
                _state.update { current ->
                    current.copy(
                        stage = TvDownloadRequestStage.ConfirmFixedBurnIn,
                        warningSubtitleLabel = buildResult.subtitleLabel,
                        error = null,
                    )
                }
                return
            }
            TvDownloadDraftBuildResult.SubtitleUnavailable -> {
                _state.update { current ->
                    current.copy(stage = TvDownloadRequestStage.Choosing, error = TvDownloadRequestError.SubtitleUnavailable)
                }
                return
            }
            TvDownloadDraftBuildResult.Unsupported -> {
                _state.update { current ->
                    current.copy(stage = TvDownloadRequestStage.Failed, error = TvDownloadRequestError.SourceChanged)
                }
                return
            }
            is TvDownloadDraftBuildResult.OriginalReady,
            is TvDownloadDraftBuildResult.FixedReady,
            -> Unit
        }
        _state.update { current ->
            current.copy(
                stage = TvDownloadRequestStage.Previewing,
                estimatedBytes = null,
                warningSubtitleLabel = null,
                error = null,
                outcome = null,
            )
        }
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
                        _state.update { current ->
                            current.copy(stage = TvDownloadRequestStage.Ready, estimatedBytes = preview.request.admissionEstimateBytes)
                        }
                    }
                    is FixedDownloadAdmissionResult.Ready -> {
                        readyDraft = ReadyDraft.Fixed((buildResult as TvDownloadDraftBuildResult.FixedReady).draft)
                        _state.update { current ->
                            current.copy(stage = TvDownloadRequestStage.Ready, estimatedBytes = preview.request.admissionEstimateBytes)
                        }
                    }
                    is OriginalDownloadAdmissionResult.Rejected -> rejectPreview(preview.decision)
                    is FixedDownloadAdmissionResult.Rejected -> rejectPreview(preview.decision)
                    null -> rejectPreview(DownloadAdmissionDecision.NetworkUnavailable)
                }
            }
    }

    private fun rejectPreview(decision: DownloadAdmissionDecision) {
        readyDraft = null
        _state.update { current ->
            current.copy(
                stage = TvDownloadRequestStage.Choosing,
                estimatedBytes = null,
                error = decision.toTvDownloadRequestError(),
            )
        }
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

private fun DownloadEnqueueResult.applyTo(state: TvDownloadRequestState): TvDownloadRequestState =
    when (this) {
        is DownloadEnqueueResult.Created ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.Queued, error = null)
        is DownloadEnqueueResult.Existing ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.Existing, error = null)
        is DownloadEnqueueResult.SchedulingRejected ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.SchedulingDelayed, error = null)
        is DownloadEnqueueResult.Rejected ->
            state.copy(
                stage = TvDownloadRequestStage.Choosing,
                estimatedBytes = null,
                outcome = null,
                error = decision.toTvDownloadRequestError(),
            )
        DownloadEnqueueResult.RemovalInProgress ->
            state.copy(
                stage = TvDownloadRequestStage.Choosing,
                estimatedBytes = null,
                outcome = null,
                error = TvDownloadRequestError.RemovalInProgress,
            )
    }

private fun DownloadAdmissionDecision.toTvDownloadRequestError(): TvDownloadRequestError =
    when (this) {
        DownloadAdmissionDecision.PermissionDenied -> TvDownloadRequestError.PermissionDenied
        DownloadAdmissionDecision.QuotaUnconfigured -> TvDownloadRequestError.QuotaUnconfigured
        DownloadAdmissionDecision.QuotaExceeded -> TvDownloadRequestError.QuotaExceeded
        DownloadAdmissionDecision.DeviceStorageLow -> TvDownloadRequestError.DeviceStorageLow
        DownloadAdmissionDecision.SizeUnavailable -> TvDownloadRequestError.SizeUnavailable
        DownloadAdmissionDecision.SourceChanged -> TvDownloadRequestError.SourceChanged
        DownloadAdmissionDecision.NetworkUnavailable -> TvDownloadRequestError.NetworkUnavailable
        DownloadAdmissionDecision.UnsupportedArtifact -> TvDownloadRequestError.UnsupportedArtifact
        DownloadAdmissionDecision.PlaybackUnsupported -> TvDownloadRequestError.PlaybackUnsupported
        DownloadAdmissionDecision.Allowed -> TvDownloadRequestError.Unknown
    }

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
