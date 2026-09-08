// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.DeleteLocalSubtitleAction
import com.jellyscope.core.domain.action.EnqueueDownloadAction
import com.jellyscope.core.domain.action.EnqueueFixedDownloadAction
import com.jellyscope.core.domain.action.RetryLocalSubtitleSyncAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaPerson
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.RELATED_GROUP_DISPLAY_LIMIT
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.ResumeDecision
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.resumeDecision
import com.jellyscope.core.domain.playback.toExplicitSubtitleSelectionIntent
import com.jellyscope.core.domain.usecase.FixedDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.FixedDownloadCapability
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.PreviewFixedDownloadUseCase
import com.jellyscope.core.domain.usecase.PreviewOriginalDownloadUseCase
import com.jellyscope.core.domain.usecase.visibleRelatedGroups
import com.jellyscope.core.playback.SettlementKey
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.screen.player.PlaybackSelection
import com.jellyscope.ui.screen.player.PlaybackSelectionMemory
import com.jellyscope.ui.screen.player.resolveRememberedSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

private data class DetailSourceLaunch(
    val mediaSourceId: String?,
    val context: PlaybackLaunchContext?,
    val rememberedSelection: PlaybackSelection?,
)

private data class FixedDownloadPreviewKey(
    val generation: Long,
    val accountIdentity: com.jellyscope.core.domain.model.AccountIdentity,
    val itemId: String,
    val mediaSourceId: String,
    val quality: DownloadQuality.Fixed,
    val selectedAudioStreamIndex: Int?,
    val subtitleSelection: DownloadSubtitleSelection,
)

private fun newDownloadIdentity(): String =
    "download-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toString().removePrefix("-")}"

internal fun DownloadEnqueueResult.toDetailDownloadState(): DetailDownloadState =
    when (this) {
        is DownloadEnqueueResult.Created -> DetailDownloadState.Created(record)
        is DownloadEnqueueResult.Existing -> DetailDownloadState.Existing(record)
        is DownloadEnqueueResult.SchedulingRejected -> DetailDownloadState.SchedulingRejected(record)
        is DownloadEnqueueResult.Rejected -> DetailDownloadState.EnqueueRejected(decision)
        DownloadEnqueueResult.RemovalInProgress -> DetailDownloadState.RemovalInProgress
    }

private fun DownloadRecord?.toDetailDownloadEntryState(): DetailDownloadEntryState {
    val record = this ?: return DetailDownloadEntryState.Add
    return when (record.state) {
        DownloadState.NotDownloaded -> DetailDownloadEntryState.Add
        DownloadState.Completed -> DetailDownloadEntryState.PlayOffline(record)
        DownloadState.Queued,
        DownloadState.Downloading,
        DownloadState.Paused,
        DownloadState.BlockedByQuota,
        DownloadState.Finalizing,
        DownloadState.Failed,
        -> DetailDownloadEntryState.Manage(record)
    }
}

class DetailViewModel(
    private val session: Session,
    private val itemId: String,
    private val getItemDetailUseCase: GetItemDetailUseCase,
    private val getRelatedItemsUseCase: GetRelatedItemsUseCase,
    private val observePlaybackStopSettlementUseCase: ObservePlaybackStopSettlementUseCase,
    private val setItemFavoriteAction: SetItemFavoriteAction,
    private val setItemPlayedAction: SetItemPlayedAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val playbackSelectionMemory: PlaybackSelectionMemory,
    private val getPlaybackLaunchContextUseCase: GetPlaybackLaunchContextUseCase,
    private val observeLocalSubtitleAssetsUseCase: ObserveLocalSubtitleAssetsUseCase? = null,
    private val deleteLocalSubtitleAction: DeleteLocalSubtitleAction? = null,
    private val retryLocalSubtitleSyncAction: RetryLocalSubtitleSyncAction? = null,
    private val saveSubtitleSelectionAction: SaveSubtitleSelectionAction? = null,
    private val previewOriginalDownloadUseCase: PreviewOriginalDownloadUseCase? = null,
    private val enqueueDownloadAction: EnqueueDownloadAction? = null,
    private val downloadIdentityProvider: () -> String = ::newDownloadIdentity,
    private val formatterStringsProvider: suspend () -> DetailFormatterStrings = { detailFormatterStrings() },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val observeDownloadsUseCase: ObserveDownloadsUseCase? = null,
    private val previewFixedDownloadUseCase: PreviewFixedDownloadUseCase? = null,
    private val enqueueFixedDownloadAction: EnqueueFixedDownloadAction? = null,
    private val fixedDownloadCapability: FixedDownloadCapability? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private val _downloadState = MutableStateFlow<DetailDownloadState>(DetailDownloadState.Idle)
    val downloadState: StateFlow<DetailDownloadState> = _downloadState.asStateFlow()
    private val _downloadEntryState =
        MutableStateFlow<DetailDownloadEntryState>(DetailDownloadEntryState.Add)
    val downloadEntryState: StateFlow<DetailDownloadEntryState> = _downloadEntryState.asStateFlow()
    val isOriginalDownloadAvailable: Boolean
        get() = session.enableContentDownloading && previewOriginalDownloadUseCase != null && enqueueDownloadAction != null
    val isFixedDownloadAvailable: Boolean
        get() =
            session.enableContentDownloading &&
                fixedDownloadCapability != null &&
                previewFixedDownloadUseCase != null &&
                enqueueFixedDownloadAction != null
    val isDownloadAvailable: Boolean
        get() = isOriginalDownloadAvailable || isFixedDownloadAvailable
    private var localSubtitleAssetsJob: Job? = null
    private var highestObservedSettlementSequence = 0L
    private var highestRefreshedSettlementSequence = 0L
    private var loadGeneration = 0L
    private var activeLoadJob: Job? = null
    private var selectedMediaSourceId: String? = null
    private var observedDownloads: List<DownloadRecord> = emptyList()
    private var mediaSourceSelectionGeneration = 0L
    private var mediaSourceSelectionJob: Job? = null
    private var fixedDownloadPreviewGeneration = 0L
    private var fixedDownloadPreviewJob: Job? = null
    private var fixedDownloadPreviewKey: FixedDownloadPreviewKey? = null

    init {
        observeDownloads()
        observePlaybackStopSettlements()
        load()
    }

    fun retry() {
        load()
    }

    fun resetOriginalDownloadState() {
        invalidateFixedDownloadPreview()
        _downloadState.value = DetailDownloadState.Idle
    }

    fun resetDownloadState() {
        resetOriginalDownloadState()
    }

    fun previewOriginalDownload(
        selectedAudioStreamIndex: Int?,
        subtitleSelection: SubtitleSelectionIntent,
    ) {
        if (!session.enableContentDownloading) return
        val preview = previewOriginalDownloadUseCase ?: return
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return
        val identity = downloadIdentityProvider()
        val downloadId = DownloadId(identity)
        val draftResult =
            buildOriginalDownloadDraft(
                detail = detail,
                selectedAudioStreamIndex = selectedAudioStreamIndex,
                subtitleSelection = subtitleSelection,
                accountIdentity = session.accountIdentity(),
                downloadId = downloadId,
                artifactKey = DownloadArtifactKey("artifact-$identity"),
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        when (draftResult) {
            is OriginalDownloadDraftBuildResult.ExternalBitmap -> {
                _downloadState.value = DetailDownloadState.BitmapSubtitleConfirmation(draftResult.streamIndex)
                return
            }
            OriginalDownloadDraftBuildResult.Unsupported -> {
                _downloadState.value =
                    DetailDownloadState.Rejected(
                        DownloadAdmissionDecision.SourceChanged,
                    )
                return
            }
            is OriginalDownloadDraftBuildResult.Ready -> Unit
        }
        val draft = draftResult.draft
        _downloadState.value = DetailDownloadState.Previewing
        viewModelScope.launch {
            val result =
                try {
                    withContext(workDispatcher) { preview(draft) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    OriginalDownloadAdmissionResult.Rejected(
                        DownloadAdmissionDecision.NetworkUnavailable,
                    )
                }
            _downloadState.value =
                when (result) {
                    is OriginalDownloadAdmissionResult.Ready -> DetailDownloadState.Ready(draft, result.request)
                    is OriginalDownloadAdmissionResult.Rejected -> DetailDownloadState.Rejected(result.decision)
                }
        }
    }

    fun enqueueOriginalDownload() {
        if (!session.enableContentDownloading) return
        val enqueue = enqueueDownloadAction ?: return
        val ready = _downloadState.value as? DetailDownloadState.Ready ?: return
        viewModelScope.launch {
            enqueueReadyOriginal(ready.draft, enqueue)
        }
    }

    fun previewFixedDownload(
        quality: DownloadQuality.Fixed,
        selectedAudioStreamIndex: Int?,
        subtitleSelection: SubtitleSelectionIntent,
    ) {
        if (!session.enableContentDownloading) return
        previewFixedDownloadInternal(
            quality = quality,
            selectedAudioStreamIndex = selectedAudioStreamIndex,
            subtitleSelection = subtitleSelection,
            burnInConfirmed = false,
        )
    }

    fun confirmFixedBurnIn() {
        if (!session.enableContentDownloading) return
        val confirmation = _downloadState.value as? DetailDownloadState.FixedBurnInConfirmation ?: return
        previewFixedDownloadInternal(
            quality = confirmation.quality,
            selectedAudioStreamIndex = confirmation.selectedAudioStreamIndex,
            subtitleSelection = SubtitleSelectionIntent.Track(confirmation.subtitleStreamIndex),
            burnInConfirmed = true,
        )
    }

    fun cancelFixedBurnIn() {
        if (_downloadState.value is DetailDownloadState.FixedBurnInConfirmation) {
            invalidateFixedDownloadPreview()
            _downloadState.value = DetailDownloadState.Idle
        }
    }

    fun enqueueFixedDownload() {
        if (!session.enableContentDownloading) return
        val enqueue = enqueueFixedDownloadAction ?: return
        val ready = _downloadState.value as? DetailDownloadState.FixedReady ?: return
        val previewKey = fixedDownloadPreviewKey ?: return
        if (!isCurrentFixedDownloadPreview(previewKey) || previewKey != previewKeyFor(ready.draft)) {
            return
        }
        viewModelScope.launch {
            enqueueReadyFixed(ready.draft, enqueue)
        }
    }

    private fun previewFixedDownloadInternal(
        quality: DownloadQuality.Fixed,
        selectedAudioStreamIndex: Int?,
        subtitleSelection: SubtitleSelectionIntent,
        burnInConfirmed: Boolean,
    ) {
        if (!session.enableContentDownloading) return
        invalidateFixedDownloadPreview()
        val preview = previewFixedDownloadUseCase ?: return
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return
        val identity = downloadIdentityProvider()
        val draftResult =
            buildFixedDownloadDraft(
                detail = detail,
                quality = quality,
                selectedAudioStreamIndex = selectedAudioStreamIndex,
                subtitleSelection = subtitleSelection,
                accountIdentity = session.accountIdentity(),
                downloadId = DownloadId(identity),
                artifactKey = DownloadArtifactKey("artifact-$identity"),
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                burnInConfirmed = burnInConfirmed,
            )
        when (draftResult) {
            is FixedDownloadDraftBuildResult.NeedsBurnIn -> {
                _downloadState.value =
                    DetailDownloadState.FixedBurnInConfirmation(
                        quality = quality,
                        selectedAudioStreamIndex = selectedAudioStreamIndex,
                        subtitleStreamIndex = draftResult.streamIndex,
                        subtitleLabel = draftResult.label,
                    )
                return
            }
            FixedDownloadDraftBuildResult.Unsupported,
            FixedDownloadDraftBuildResult.UnsupportedSubtitle,
            -> {
                _downloadState.value =
                    DetailDownloadState.FixedRejected(DownloadAdmissionDecision.UnsupportedArtifact)
                return
            }
            is FixedDownloadDraftBuildResult.Ready -> Unit
        }
        val draft = draftResult.draft
        val previewKey =
            FixedDownloadPreviewKey(
                generation = fixedDownloadPreviewGeneration,
                accountIdentity = draft.businessKey.accountIdentity,
                itemId = draft.businessKey.itemId,
                mediaSourceId = draft.businessKey.mediaSourceId,
                quality = draft.quality,
                selectedAudioStreamIndex = draft.selectedAudioStreamIndex,
                subtitleSelection = draft.subtitleSelection,
            )
        fixedDownloadPreviewKey = previewKey
        _downloadState.value = DetailDownloadState.Previewing
        fixedDownloadPreviewJob =
            viewModelScope.launch {
                val result =
                    try {
                        withContext(workDispatcher) { preview(draft) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        FixedDownloadAdmissionResult.Rejected(DownloadAdmissionDecision.NetworkUnavailable)
                    }
                if (!isCurrentFixedDownloadPreview(previewKey)) {
                    return@launch
                }
                _downloadState.value =
                    when (result) {
                        is FixedDownloadAdmissionResult.Ready -> DetailDownloadState.FixedReady(draft, result.request)
                        is FixedDownloadAdmissionResult.Rejected -> DetailDownloadState.FixedRejected(result.decision)
                    }
                fixedDownloadPreviewJob = null
            }
    }

    private fun invalidateFixedDownloadPreview() {
        fixedDownloadPreviewJob?.cancel()
        fixedDownloadPreviewJob = null
        fixedDownloadPreviewKey = null
        fixedDownloadPreviewGeneration += 1L
    }

    private fun isCurrentFixedDownloadPreview(key: FixedDownloadPreviewKey): Boolean {
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return false
        return fixedDownloadPreviewGeneration == key.generation &&
            fixedDownloadPreviewKey == key &&
            key.accountIdentity == session.accountIdentity() &&
            key.itemId == itemId &&
            detail.itemId == key.itemId &&
            detail.selectedMediaSourceId == key.mediaSourceId
    }

    private fun previewKeyFor(draft: FixedDownloadDraft): FixedDownloadPreviewKey =
        FixedDownloadPreviewKey(
            generation = fixedDownloadPreviewGeneration,
            accountIdentity = draft.businessKey.accountIdentity,
            itemId = draft.businessKey.itemId,
            mediaSourceId = draft.businessKey.mediaSourceId,
            quality = draft.quality,
            selectedAudioStreamIndex = draft.selectedAudioStreamIndex,
            subtitleSelection = draft.subtitleSelection,
        )

    private suspend fun enqueueReadyOriginal(
        draft: OriginalDownloadDraft,
        enqueue: EnqueueDownloadAction,
    ) {
        val result =
            try {
                withContext(workDispatcher) { enqueue(draft) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.NetworkUnavailable)
            }
        _downloadState.value = result.toDetailDownloadState()
    }

    private suspend fun enqueueReadyFixed(
        draft: FixedDownloadDraft,
        enqueue: EnqueueFixedDownloadAction,
    ) {
        val result =
            try {
                withContext(workDispatcher) { enqueue(draft) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.NetworkUnavailable)
            }
        _downloadState.value = result.toDetailDownloadState()
    }

    private fun observeDownloads() {
        if (!session.enableContentDownloading) {
            _downloadEntryState.value = DetailDownloadEntryState.Add
            return
        }
        val observe = observeDownloadsUseCase ?: return
        viewModelScope.launch {
            observe(session.accountIdentity())
                .catch {
                    observedDownloads = emptyList()
                    reprojectDownloadEntry()
                }.collect { records ->
                    observedDownloads = records
                    reprojectDownloadEntry()
                }
        }
    }

    private fun reprojectDownloadEntry() {
        if (!session.enableContentDownloading) {
            _downloadEntryState.value = DetailDownloadEntryState.Add
            return
        }
        val detail = (_state.value as? DetailUiState.Content)?.detail
        val selectedSourceId = detail?.selectedMediaSourceId
        if (selectedSourceId == null) {
            _downloadEntryState.value = DetailDownloadEntryState.Add
            return
        }
        val matchingRecord =
            observedDownloads.firstOrNull { record ->
                record.businessKey.accountIdentity == session.accountIdentity() &&
                    record.businessKey.itemId == itemId &&
                    record.businessKey.mediaSourceId == selectedSourceId &&
                    when (record.request.quality) {
                        DownloadQuality.Original -> record.request.artifactKind == DownloadArtifactKind.OriginalFile
                        is DownloadQuality.Fixed -> record.request.artifactKind == DownloadArtifactKind.LocalHlsPackage
                    }
            }
        _downloadEntryState.value = matchingRecord.toDetailDownloadEntryState()
    }

    // Silent reload used when the screen becomes active again (e.g. returning
    // from the player) so the resume position / watched state reflect the
    // latest server value without a loading flash. (#172)
    fun refresh() {
        if (_state.value is DetailUiState.Content) {
            load(showLoading = false)
        }
    }

    private fun observePlaybackStopSettlements() {
        viewModelScope.launch {
            observePlaybackStopSettlementUseCase(
                SettlementKey(session.serverId, session.userId, itemId),
            ).collect { settlement -> recordStopSettlement(settlement.sequence) }
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
        if (
            highestRefreshedSettlementSequence >= highestObservedSettlementSequence ||
            _state.value !is DetailUiState.Content
        ) {
            return
        }
        // Any in-flight load defers the settlement refresh: the completion hook
        // re-enters here, so a settlement can delay a refresh but never race a
        // concurrent request whose stale result could commit last.
        if (activeLoadJob?.isActive == true) {
            return
        }

        val refreshedSequence = highestObservedSettlementSequence
        load(showLoading = false) {
            highestRefreshedSettlementSequence =
                maxOf(highestRefreshedSettlementSequence, refreshedSequence)
        }
    }

    fun toggleWatched() {
        val content = _state.value as? DetailUiState.Content ?: return
        val previous = content.detail
        val played = !previous.isWatched
        _state.update { current ->
            if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                current.copy(detail = current.detail.withWatched(played).copy(watchedToggleInFlight = true))
            } else {
                current
            }
        }

        viewModelScope.launch {
            val result = setItemPlayedAction(itemId = previous.itemId, played = played)
            _state.update { current ->
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    if (result.isSuccess) {
                        current.copy(detail = current.detail.copy(watchedToggleInFlight = false))
                    } else {
                        current.copy(detail = current.detail.withWatchedRollbackFrom(previous))
                    }
                } else {
                    current
                }
            }
        }
    }

    private fun observeLocalSubtitleAssets(
        itemId: String,
        mediaSourceId: String,
    ) {
        val observe = observeLocalSubtitleAssetsUseCase ?: return
        localSubtitleAssetsJob?.cancel()
        _state.update { current ->
            if (current is DetailUiState.Content && current.detail.itemId == itemId) {
                current.copy(detail = current.detail.withLocalSubtitleAssets(mediaSourceId, emptyList()))
            } else {
                current
            }
        }
        localSubtitleAssetsJob =
            viewModelScope.launch {
                observe(LocalSubtitleContext(session.serverId, session.userId, itemId, mediaSourceId))
                    .collect { assets ->
                        _state.update { current ->
                            if (current is DetailUiState.Content &&
                                current.detail.itemId == itemId &&
                                current.detail.selectedMediaSourceId == mediaSourceId
                            ) {
                                current.copy(detail = current.detail.withLocalSubtitleAssets(mediaSourceId, assets))
                            } else {
                                current
                            }
                        }
                    }
            }
    }

    fun toggleFavorite() {
        val content = _state.value as? DetailUiState.Content ?: return
        val previous = content.detail
        val favorite = !previous.isFavorite
        _state.update { current ->
            if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                current.copy(detail = current.detail.copy(isFavorite = favorite, favoriteToggleInFlight = true))
            } else {
                current
            }
        }

        viewModelScope.launch {
            val result = setItemFavoriteAction(itemId = previous.itemId, favorite = favorite)
            _state.update { current ->
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    if (result.isSuccess) {
                        current.copy(detail = current.detail.copy(favoriteToggleInFlight = false))
                    } else {
                        current.copy(
                            detail =
                                current.detail.copy(
                                    isFavorite = previous.isFavorite,
                                    favoriteToggleInFlight = false,
                                ),
                        )
                    }
                } else {
                    current
                }
            }
        }
    }

    fun deleteLocalSubtitle(assetId: String) {
        val delete = deleteLocalSubtitleAction ?: return
        viewModelScope.launch {
            delete(assetId)
            // Deletion can drop the durable selection, so reproject from the
            // options already in state before asking the server for anything:
            // refresh() needs a remote item-detail response, and a failed one
            // returns without committing, which would otherwise leave the
            // pre-deletion selection rendered while playback has moved on.
            reprojectSubtitleDefaults()
            refresh()
        }
    }

    private suspend fun reprojectSubtitleDefaults() {
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return
        val mediaSourceId = detail.trackSelection.mediaSourceId ?: return
        val launchContext =
            withContext(workDispatcher) {
                getPlaybackLaunchContextUseCase(session, detail.itemId, mediaSourceId)
            }
        _state.update { current ->
            // Read the defaults off the state being committed, not the snapshot
            // above: a local-asset emission may have landed while the stores
            // were being read, and that list must survive this update.
            if (current is DetailUiState.Content &&
                current.detail.itemId == detail.itemId &&
                current.detail.trackSelection.mediaSourceId == mediaSourceId
            ) {
                val updatedTrackSelection =
                    current.detail.trackSelection.reprojectSubtitleDefaults(
                        playbackPreferences = launchContext.playbackPreferences,
                        storedSubtitleSelection = launchContext.subtitleSelection,
                    )
                val selectedVersion =
                    current.detail.versions.firstOrNull { version -> version.id == mediaSourceId }
                current.copy(
                    detail =
                        if (selectedVersion != null) {
                            current.detail.withSelectedMediaVersion(
                                selectedVersion.copy(trackSelection = updatedTrackSelection),
                            )
                        } else {
                            current.detail.copy(trackSelection = updatedTrackSelection)
                        },
                )
            } else {
                current
            }
        }
    }

    fun retryLocalSubtitleSync(assetId: String) {
        val retry = retryLocalSubtitleSyncAction ?: return
        viewModelScope.launch { retry(session, assetId) }
    }

    fun selectSubtitle(streamIndex: Int?) {
        saveSubtitleSelection(streamIndex.toExplicitSubtitleSelectionIntent())
    }

    fun selectLocalSubtitle(assetId: String) {
        saveSubtitleSelection(SubtitleSelectionIntent.LocalAsset(assetId))
    }

    private fun saveSubtitleSelection(selection: SubtitleSelectionIntent) {
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return
        val mediaSourceId = detail.playAction.mediaSourceId ?: return
        val save = saveSubtitleSelectionAction ?: return
        val key = SubtitleSelectionKey(session.serverId, session.userId, detail.itemId, mediaSourceId)
        save.save(key, selection)
    }

    fun selectMediaVersion(mediaSourceId: String) {
        val detail = (_state.value as? DetailUiState.Content)?.detail ?: return
        if (detail.versions.none { version -> version.id == mediaSourceId }) {
            return
        }

        selectedMediaSourceId = mediaSourceId
        resetOriginalDownloadState()
        val generation = ++mediaSourceSelectionGeneration
        mediaSourceSelectionJob?.cancel()
        mediaSourceSelectionJob = null
        if (detail.selectedMediaSourceId == mediaSourceId) {
            reprojectDownloadEntry()
            return
        }
        mediaSourceSelectionJob =
            viewModelScope.launch {
                val launchContext =
                    withContext(workDispatcher) {
                        getPlaybackLaunchContextUseCase(session, detail.itemId, mediaSourceId)
                    }
                val rememberedSelection = rememberedSelectionFor(detail.itemId, mediaSourceId, launchContext)
                _state.update { current ->
                    val currentDetail = (current as? DetailUiState.Content)?.detail ?: return@update current
                    val currentVersion =
                        currentDetail.versions.firstOrNull { version -> version.id == mediaSourceId }
                            ?: return@update current
                    if (generation != mediaSourceSelectionGeneration ||
                        currentDetail.itemId != detail.itemId ||
                        selectedMediaSourceId != mediaSourceId
                    ) {
                        current
                    } else {
                        val resolvedVersion =
                            currentVersion.resolveLaunchDefaults(
                                playbackPreferences = launchContext.playbackPreferences,
                                rememberedSelection = rememberedSelection,
                                storedSubtitleSelection = launchContext.subtitleSelection,
                            )
                        current.copy(detail = currentDetail.withSelectedMediaVersion(resolvedVersion))
                    }
                }
                reprojectDownloadEntry()
                val selectedDetail = (_state.value as? DetailUiState.Content)?.detail
                if (generation == mediaSourceSelectionGeneration &&
                    selectedDetail?.itemId == detail.itemId &&
                    selectedDetail.selectedMediaSourceId == mediaSourceId
                ) {
                    observeLocalSubtitleAssets(detail.itemId, mediaSourceId)
                }
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

    private suspend fun resolveSourceLaunch(
        detail: MediaItemDetail,
        generation: Long,
    ): DetailSourceLaunch? {
        while (true) {
            if (generation != loadGeneration) {
                return null
            }
            val selectionGeneration = mediaSourceSelectionGeneration
            val requestedSourceId = selectedMediaSourceId
            val sourceId =
                detail.versions
                    .selectedPlaybackVersion(requestedSourceId)
                    ?.id
                    ?.takeIf(String::isNotBlank)
            if (sourceId == null) {
                if (generation != loadGeneration ||
                    selectionGeneration != mediaSourceSelectionGeneration ||
                    selectedMediaSourceId != requestedSourceId
                ) {
                    continue
                }
                acceptRefreshedSourceFallback(requestedSourceId, sourceId)
                return DetailSourceLaunch(
                    mediaSourceId = null,
                    context = null,
                    rememberedSelection = null,
                )
            }
            val launchContext =
                withContext(workDispatcher) {
                    getPlaybackLaunchContextUseCase(session, detail.item.id, sourceId)
                }
            val rememberedSelection = rememberedSelectionFor(detail.item.id, sourceId, launchContext)
            if (generation != loadGeneration) {
                return null
            }
            if (selectionGeneration == mediaSourceSelectionGeneration &&
                selectedMediaSourceId == requestedSourceId
            ) {
                acceptRefreshedSourceFallback(requestedSourceId, sourceId)
                return DetailSourceLaunch(
                    mediaSourceId = sourceId,
                    context = launchContext,
                    rememberedSelection = rememberedSelection,
                )
            }
        }
    }

    private fun acceptRefreshedSourceFallback(
        requestedSourceId: String?,
        resolvedSourceId: String?,
    ) {
        if (requestedSourceId != null && requestedSourceId != resolvedSourceId) {
            mediaSourceSelectionJob?.cancel()
            mediaSourceSelectionGeneration += 1L
        }
        selectedMediaSourceId = resolvedSourceId
    }

    private fun load(
        showLoading: Boolean = true,
        onComplete: (() -> Unit)? = null,
    ) {
        // Header commits are generation-guarded: when loads overlap (e.g. an
        // ON_RESUME reveal refresh racing a settlement-triggered one), only the
        // newest request may publish the header/userData, so an older response
        // finishing last can never reintroduce a stale resume point.
        val generation = ++loadGeneration
        val job =
            viewModelScope.launch {
                if (showLoading) {
                    _state.value = DetailUiState.Loading
                }
                val detail = getItemDetailUseCase(itemId).getOrNull()
                if (detail == null) {
                    if (showLoading && generation == loadGeneration) {
                        _state.update { DetailUiState.Error() }
                    }
                    return@launch
                }
                val formatterStrings = formatterStringsProvider()
                var sourceLaunch: DetailSourceLaunch
                var detailUi: DetailUi
                while (true) {
                    sourceLaunch = resolveSourceLaunch(detail, generation) ?: return@launch
                    val projectionSelectionGeneration = mediaSourceSelectionGeneration
                    // Render the header immediately; the related row is a separate
                    // request and must not block the header from appearing.
                    // Project DTOs off Main; keep the state update below cheap.
                    detailUi =
                        withContext(workDispatcher) {
                            detail.toDetailUi(
                                relatedGroups = emptyList(),
                                relatedLoading = true,
                                selectedMediaSourceId = sourceLaunch.mediaSourceId,
                                playbackPreferences =
                                    sourceLaunch.context?.playbackPreferences
                                        ?: PlaybackPreferences().normalized(),
                                formatterStrings = formatterStrings,
                                rememberedSelection = sourceLaunch.rememberedSelection,
                                storedSubtitleSelection = sourceLaunch.context?.subtitleSelection,
                            )
                        }
                    if (generation != loadGeneration) {
                        return@launch
                    }
                    if (projectionSelectionGeneration == mediaSourceSelectionGeneration &&
                        selectedMediaSourceId == sourceLaunch.mediaSourceId
                    ) {
                        break
                    }
                }
                var committed = false
                _state.update { current ->
                    if (generation != loadGeneration) {
                        current
                    } else if (showLoading) {
                        committed = true
                        DetailUiState.Content(detail = detailUi)
                    } else if (current is DetailUiState.Content && current.detail.itemId == detail.item.id) {
                        // Related can still be streaming while a retained Detail route
                        // silently refreshes. Merge at commit time so groups emitted
                        // during the refresh are never overwritten or marked complete.
                        committed = true
                        DetailUiState.Content(
                            detail =
                                detailUi.copy(
                                    relatedGroups = current.detail.relatedGroups,
                                    relatedLoading = current.detail.relatedLoading,
                                ),
                        )
                    } else {
                        current
                    }
                }
                if (!committed || generation != loadGeneration) {
                    return@launch
                }
                reprojectDownloadEntry()
                sourceLaunch.mediaSourceId?.let { sourceId ->
                    observeLocalSubtitleAssets(detail.item.id, sourceId)
                } ?: run {
                    localSubtitleAssetsJob?.cancel()
                }

                // Related is static across a return-from-player refresh, so only (re)fetch
                // on a fresh load. Keep collecting after the visible shelf cap so the
                // repository can complete its cache population.
                if (showLoading) {
                    getRelatedItemsUseCase(detail)
                        .visibleRelatedGroups()
                        .catch { throwable ->
                            detailViewModelLogger.w {
                                formatSafeFailureDiagnostic(
                                    stage = "related-items",
                                    event = "failed",
                                    throwable = throwable,
                                )
                            }
                        }.collect { group ->
                            val groupUi =
                                withContext(workDispatcher) {
                                    RelatedGroupUi(
                                        kind = group.kind,
                                        label = group.label,
                                        items = group.items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) },
                                    )
                                }
                            _state.update { current ->
                                if (current is DetailUiState.Content) {
                                    val relatedGroups = current.detail.relatedGroups + groupUi
                                    current.copy(
                                        detail =
                                            current.detail.copy(
                                                relatedGroups = relatedGroups,
                                                relatedLoading =
                                                    if (relatedGroups.size >= RELATED_GROUP_DISPLAY_LIMIT) {
                                                        false
                                                    } else {
                                                        current.detail.relatedLoading
                                                    },
                                            ),
                                    )
                                } else {
                                    current
                                }
                            }
                        }
                }
                if (showLoading) {
                    _state.update { current ->
                        if (current is DetailUiState.Content) {
                            current.copy(detail = current.detail.copy(relatedLoading = false))
                        } else {
                            current
                        }
                    }
                }
            }
        activeLoadJob = job
        job.invokeOnCompletion {
            onComplete?.invoke()
            // Trailing settlement refresh: a settlement observed while this
            // load was in flight runs exactly one follow-up once it finishes.
            refreshAfterStopSettlement()
        }
    }

    private fun MediaItemDetail.toDetailUi(
        relatedGroups: List<RelatedGroupUi>,
        relatedLoading: Boolean,
        selectedMediaSourceId: String?,
        playbackPreferences: PlaybackPreferences,
        formatterStrings: DetailFormatterStrings,
        rememberedSelection: PlaybackSelection?,
        storedSubtitleSelection: SubtitleSelectionIntent?,
    ): DetailUi {
        val decision =
            resumeDecision(
                playbackPositionTicks = item.playbackPositionTicks,
                runtime = item.runtime,
                played = item.played,
            )
        val startPositionTicks =
            when (decision) {
                ResumeDecision.Start, ResumeDecision.StartOver -> 0L
                is ResumeDecision.Resume -> item.playbackPositionTicks ?: 0L
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
        val resolvedMediaSourceId = selectedVersion?.id
        val resolvedVersions =
            selectedVersion?.let { selected ->
                versionUis.map { version -> if (version.id == selected.id) selected else version }
            } ?: versionUis

        return DetailUi(
            itemId = item.id,
            itemKind = item.kind,
            seriesName = item.seriesName,
            runtimeMs = item.runtime?.inWholeMilliseconds,
            imdbId = imdbId,
            productionYear = productionYear,
            seasonNumber = item.parentIndexNumber,
            episodeNumber = item.indexNumber,
            subtitleSearchLanguage = playbackPreferences.preferredSubtitleLanguage?.takeIf(String::isNotBlank) ?: "en",
            selectedSourceReleaseBasename = selectedVersion?.releaseBasename,
            selectedMediaSourceId = resolvedMediaSourceId,
            title = item.name,
            headerLine = item.headerLine(),
            metadataLine = metadataLine(),
            genresLine = genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
            officialRating = officialRating?.takeIf { it.isNotBlank() },
            communityRating = communityRatingText(communityRating),
            criticRatingText = criticRatingText(criticRating),
            imdbUrl = imdbId?.let { id -> "https://www.imdb.com/title/$id/" },
            tmdbUrl =
                tmdbId?.let { id ->
                    tmdbItemType?.let { itemType -> "https://www.themoviedb.org/$itemType/$id" }
                },
            tagline = taglines.firstOrNull()?.takeIf { line -> line.isNotBlank() },
            overview = overview,
            directedByLine = directedByLine(people, formatterStrings),
            studioLine = studios.take(2).joinToString(", ") { studio -> studio.name }.takeIf { it.isNotBlank() },
            castAndCrew = castAndCrew(people).map { person -> person.toUi() },
            streamBadges = selectedVersion?.streamBadges.orEmpty(),
            mediaInfo = selectedVersion?.mediaInfo,
            timeLeftText =
                if (decision is ResumeDecision.Resume) {
                    timeLeftText(
                        runtime = item.runtime,
                        playbackPositionTicks = item.playbackPositionTicks,
                        strings = formatterStrings,
                    )
                } else {
                    null
                },
            playAction =
                DetailPlayAction(
                    label = decision.toPlayLabel(),
                    startPositionTicks = startPositionTicks,
                    mediaSourceId = resolvedMediaSourceId,
                ),
            restartAction =
                if (decision is ResumeDecision.Resume) {
                    DetailPlayAction(
                        label = DetailPlayLabel.StartOver,
                        startPositionTicks = 0L,
                        mediaSourceId = resolvedMediaSourceId,
                    )
                } else {
                    null
                },
            isWatched = item.played,
            watchedToggleInFlight = false,
            isFavorite = item.isFavorite,
            favoriteToggleInFlight = false,
            progressFraction =
                item.playedPercentage
                    ?.toFloat()
                    ?.div(100f)
                    ?.coerceIn(0f, 1f),
            trackSelection = selectedVersion?.trackSelection ?: DetailTrackSelectionUi(),
            versions = resolvedVersions,
            relatedGroups = relatedGroups,
            relatedLoading = relatedLoading,
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
            logoUrl =
                item.imageRefs.logoTag?.let {
                    imageUrl(
                        item = item,
                        type = JellyfinImageType.Logo,
                        tag = item.imageRefs.logoTag,
                        maxWidth = 720,
                    )
                },
            trailerUrl = trailerUrl,
            chapters = chapters.map { chapter -> OfflineChapterUi(chapter.name, chapter.startTicks) },
        )
    }

    private fun MediaItemDetail.metadataLine(): String? =
        listOfNotNull(
            productionYear?.toString(),
            item.runtime?.formatted(),
        ).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" · ")

    private fun MediaItem.headerLine(): String? =
        when {
            kind == MediaKind.Episode && seriesName != null && episodeLabel != null ->
                "$seriesName · $episodeLabel"
            kind == MediaKind.Episode && seriesName != null -> seriesName
            kind == MediaKind.Episode && episodeLabel != null -> episodeLabel
            else -> null
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
