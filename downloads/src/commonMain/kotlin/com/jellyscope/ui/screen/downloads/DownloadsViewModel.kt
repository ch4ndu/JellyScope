// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.CancelDownloadAction
import com.jellyscope.core.domain.action.ConfigureDownloadQuotaAction
import com.jellyscope.core.domain.action.DeleteDownloadAction
import com.jellyscope.core.domain.action.PauseDownloadAction
import com.jellyscope.core.domain.action.ResumeDownloadAction
import com.jellyscope.core.domain.action.ResumePausedDownloadsAction
import com.jellyscope.core.domain.action.RetryDownloadAction
import com.jellyscope.core.domain.action.WakeDownloadsQueueAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.OfflinePersonCreditType
import com.jellyscope.core.domain.model.OfflinePersonSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.IsDownloadArtifactLeasedUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.domain.usecase.ReadDownloadArtworkUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

enum class DownloadsSectionKind {
    Completed,
    Active,
    Queued,
    Paused,
    Failed,
}

data class DownloadsSection(
    val kind: DownloadsSectionKind,
    val records: List<DownloadRecord>,
)

fun downloadSections(records: List<DownloadRecord>): List<DownloadsSection> =
    listOf(
        DownloadsSection(
            kind = DownloadsSectionKind.Completed,
            records = records.filter { record -> record.state == DownloadState.Completed },
        ),
        DownloadsSection(
            kind = DownloadsSectionKind.Active,
            records =
                records.filter { record ->
                    record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing
                },
        ),
        DownloadsSection(
            kind = DownloadsSectionKind.Queued,
            records = records.filter { record -> record.state == DownloadState.Queued },
        ),
        DownloadsSection(
            kind = DownloadsSectionKind.Paused,
            records =
                records.filter { record ->
                    record.state == DownloadState.Paused || record.state == DownloadState.BlockedByQuota
                },
        ),
        DownloadsSection(
            kind = DownloadsSectionKind.Failed,
            records = records.filter { record -> record.state == DownloadState.Failed },
        ),
    ).filter { section -> section.records.isNotEmpty() }

/** Presentation-ready local facts; the source row remains the only durable authority. */
data class DownloadDetailUi(
    val record: DownloadRecord,
    val card: MediaCardUi,
    val expectedBytes: Long,
    val progressFraction: Float?,
    val chapters: List<OfflineChapterUi>,
    val audioTracks: List<OfflineTrackSnapshot>,
    val subtitleTracks: List<OfflineTrackSnapshot>,
    val cast: List<OfflinePersonSnapshot>,
    val crew: List<OfflinePersonSnapshot>,
    val otherPeople: List<OfflinePersonSnapshot>,
    val backend: OfflineBackendUi,
)

data class OfflineChapterUi(
    val name: String,
    val startMs: Long,
)

data class OfflineBackendUi(
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val frameRateLabel: String?,
    val isHdrOrDolbyVision: Boolean,
)

fun DownloadsUiState.detailUi(downloadId: DownloadId): DownloadDetailUi? = detailsByDownloadId[downloadId]

fun DownloadsUiState.downloadGridSemanticIndex(downloadId: DownloadId): Int? =
    sections
        .flatMap(DownloadsSection::records)
        .indexOfFirst { record -> record.downloadId == downloadId }
        .takeIf { index -> index >= 0 }

fun DownloadsUiState.downloadGridRestoreSlot(
    downloadId: DownloadId,
    fallbackSemanticIndex: Int?,
): Int? {
    val slots = mutableListOf<Pair<DownloadId, Int>>()
    var slot = 1 // Storage card.
    if (records.any { record -> record.state == DownloadState.Paused }) slot += 1
    sections.forEach { section ->
        slot += 1 // Section heading.
        section.records.forEach { record ->
            slots += record.downloadId to slot
            slot += 1
        }
    }
    return slots.firstOrNull { (id, _) -> id == downloadId }?.second
        ?: fallbackSemanticIndex
            ?.takeIf { slots.isNotEmpty() }
            ?.let { index -> slots[index.coerceIn(0, slots.lastIndex)].second }
}

private fun downloadDetailUi(record: DownloadRecord): DownloadDetailUi {
    val expectedBytes =
        maxOf(
            record.request.expectedSourceBytes ?: 0L,
            record.reservationBytes,
            record.request.admissionEstimateBytes,
            record.physicalBytes,
        )
    val snapshot = record.request.snapshot
    return DownloadDetailUi(
        record = record,
        card =
            MediaCardUi(
                id = record.downloadId.value,
                title = snapshot.title,
                subtitle = snapshot.seriesName ?: snapshot.detail?.productionYear?.toString(),
                progressFraction =
                    if (record.state == DownloadState.Completed) {
                        snapshot.durationMs?.takeIf { record.localResumePositionMs > 0L }?.let { duration ->
                            (record.localResumePositionMs.toFloat() / duration).coerceIn(0f, 1f)
                        }
                    } else {
                        expectedBytes.takeIf { it > 0L }?.let { (record.physicalBytes.toFloat() / it).coerceIn(0f, 1f) }
                    },
                watched = record.localWatched,
                unplayedCount = null,
                imageUrl = null,
                kind =
                    if (snapshot.itemKind ==
                        com.jellyscope.core.domain.model.MediaKind.Episode
                    ) {
                        MediaCardKind.Episode
                    } else {
                        MediaCardKind.Movie
                    },
            ),
        expectedBytes = expectedBytes,
        progressFraction =
            expectedBytes
                .takeIf { bytes -> bytes > 0L }
                ?.let { bytes -> (record.physicalBytes.toFloat() / bytes.toFloat()).coerceIn(0f, 1f) },
        chapters =
            snapshot.chapters.map { chapter ->
                OfflineChapterUi(
                    name = chapter.name,
                    startMs = chapter.startTicks / TICKS_PER_MILLISECOND,
                )
            },
        audioTracks = snapshot.downloadTracks(OfflineTrackKind.Audio, snapshot.selectedAudioTrack),
        subtitleTracks = snapshot.downloadTracks(OfflineTrackKind.Subtitle, snapshot.selectedSubtitleTrack),
        cast =
            snapshot.detail
                ?.people
                .orEmpty()
                .filter { person -> person.creditType == OfflinePersonCreditType.Cast },
        crew =
            snapshot.detail
                ?.people
                .orEmpty()
                .filter { person -> person.creditType == OfflinePersonCreditType.Crew },
        otherPeople =
            snapshot.detail
                ?.people
                .orEmpty()
                .filter { person -> person.creditType == OfflinePersonCreditType.Other },
        backend =
            OfflineBackendUi(
                container = snapshot.backendSource.container,
                videoCodec = snapshot.backendSource.videoCodec,
                audioCodec = snapshot.backendSource.audioCodec,
                videoWidth = snapshot.backendSource.videoWidth,
                videoHeight = snapshot.backendSource.videoHeight,
                frameRateLabel = snapshot.backendSource.videoFrameRate.toDownloadFrameRateLabel(),
                isHdrOrDolbyVision = snapshot.backendSource.isHdrOrDolbyVision,
            ),
    )
}

private fun Double?.toDownloadFrameRateLabel(): String? =
    this
        ?.takeIf { rate -> rate.isFinite() && rate > 0.0 }
        ?.let { rate -> (round(rate * 1_000.0) / 1_000.0).toString() }

private fun com.jellyscope.core.domain.model.OfflineMediaSnapshot.downloadTracks(
    kind: OfflineTrackKind,
    selected: OfflineTrackSnapshot?,
): List<OfflineTrackSnapshot> =
    buildList {
        selected?.takeIf { track -> track.kind == kind }?.let(::add)
        embeddedTracks
            .asSequence()
            .filter { track -> track.kind == kind && track != selected }
            .forEach(::add)
    }

sealed interface DownloadsUiError {
    data object LoadFailed : DownloadsUiError

    data object CommandRejected : DownloadsUiError

    data object QuotaRejected : DownloadsUiError

    data object ArtifactInUse : DownloadsUiError
}

/** Durable, account-scoped download state behind UseCases and Actions. */
class DownloadsViewModel(
    private val session: Session,
    observeDownloadsUseCase: ObserveDownloadsUseCase,
    private val getDownloadUsageUseCase: GetDownloadUsageUseCase,
    private val getDownloadSettingsUseCase: GetDownloadSettingsUseCase,
    private val configureDownloadQuotaAction: ConfigureDownloadQuotaAction,
    private val pauseDownloadAction: PauseDownloadAction,
    private val resumeDownloadAction: ResumeDownloadAction,
    private val resumePausedDownloadsAction: ResumePausedDownloadsAction,
    private val retryDownloadAction: RetryDownloadAction,
    private val wakeDownloadsQueueAction: WakeDownloadsQueueAction,
    private val cancelDownloadAction: CancelDownloadAction,
    private val deleteDownloadAction: DeleteDownloadAction,
    private val isDownloadArtifactLeasedUseCase: IsDownloadArtifactLeasedUseCase? = null,
    private val readDownloadArtworkUseCase: ReadDownloadArtworkUseCase? = null,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val accountIdentity: AccountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()
    private val usageRefreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var leaseRefreshJob: Job? = null
    private var leaseRefreshInitialized = false

    fun accept(intent: DownloadsIntent) {
        when (intent) {
            DownloadsIntent.Refresh -> refresh()
            DownloadsIntent.WakeQueueOnScreenResume -> wakeQueueOnScreenResume()
            is DownloadsIntent.ConfigureQuota -> configureQuota(intent.quotaBytes)
            is DownloadsIntent.Pause -> pause(intent.downloadId)
            is DownloadsIntent.Resume -> resume(intent.downloadId)
            DownloadsIntent.ResumePausedDownloads -> resumePausedDownloads()
            is DownloadsIntent.Retry -> retry(intent.downloadId)
            is DownloadsIntent.Cancel -> cancel(intent.downloadId)
            is DownloadsIntent.Delete -> delete(intent.downloadId)
        }
    }

    private fun reduce(change: DownloadsChange) {
        _state.update { current -> reduceDownloads(current, change) }
    }

    init {
        viewModelScope.launch {
            for (request in usageRefreshRequests) {
                refreshUsageNow()
                delay(USAGE_REFRESH_COOLDOWN_MS)
            }
        }
        viewModelScope.launch {
            observeDownloadsUseCase(accountIdentity)
                .catch { exception ->
                    if (exception is CancellationException) throw exception
                    downloadsViewModelLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "observe",
                            event = "failed",
                            operation = DiagnosticOperation.DownloadObserve,
                            throwable = exception,
                        )
                    }
                    reduce(DownloadsChange.RecordsFailed)
                }.collect { records ->
                    val projection =
                        withContext(workDispatcher) {
                            DownloadRecordsProjection(
                                sections = downloadSections(records),
                                detailsByDownloadId =
                                    records.associate { record ->
                                        record.downloadId to downloadDetailUi(record)
                                    },
                            )
                        }
                    reduce(DownloadsChange.RecordsUpdated(records, projection.sections, projection.detailsByDownloadId))
                    requestUsageRefresh()
                    if (!leaseRefreshInitialized) {
                        requestLeaseRefresh()
                    }
                }
        }
        requestUsageRefresh()
    }

    private fun refresh() {
        requestUsageRefresh()
        requestLeaseRefresh()
    }

    private fun wakeQueueOnScreenResume() {
        viewModelScope.launch {
            val failure =
                try {
                    withContext(workDispatcher) { wakeDownloadsQueueAction().exceptionOrNull() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    exception
                }
            failure?.let { exception ->
                downloadsViewModelLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "scheduling-wake",
                        event = "failed",
                        operation = DiagnosticOperation.DownloadSchedulingWake,
                        throwable = exception,
                    )
                }
            }
        }
    }

    private fun configureQuota(quotaBytes: Long?) {
        viewModelScope.launch {
            reduce(DownloadsChange.QuotaStarted)
            try {
                withContext(workDispatcher) { configureDownloadQuotaAction(quotaBytes) }
                requestUsageRefresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                downloadsViewModelLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "quota",
                        event = "failed",
                        operation = DiagnosticOperation.DownloadQuota,
                        throwable = exception,
                    )
                }
                reduce(DownloadsChange.QuotaFailed)
            }
        }
    }

    private fun pause(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadPause) {
                pauseDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    private fun resume(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadResume) {
                resumeDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    private fun resumePausedDownloads() {
        if (_state.value.inFlightDownloadId != null || _state.value.isBulkResumeInFlight) return
        val pausedDownloadIds =
            _state.value.records
                .filter { record -> record.state == DownloadState.Paused }
                .map(DownloadRecord::downloadId)
        if (pausedDownloadIds.isEmpty()) return

        viewModelScope.launch {
            reduce(DownloadsChange.BulkResumeStarted)
            val result =
                try {
                    withContext(workDispatcher) { resumePausedDownloadsAction(accountIdentity, pausedDownloadIds) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    downloadsViewModelLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "bulk-resume",
                            event = "failed",
                            operation = DiagnosticOperation.DownloadResumeAll,
                            throwable = exception,
                        )
                    }
                    null
                }
            if (result != null && result != DownloadCommandResult.Applied) {
                downloadsViewModelLogger.w {
                    "stage=bulk-resume event=rejected operation=${DiagnosticOperation.DownloadResumeAll.wireValue} " +
                        "result=${result.diagnosticValue()}"
                }
            }
            reduce(DownloadsChange.BulkResumeFinished(result))
            requestUsageRefresh()
        }
    }

    private fun retry(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadRetry) {
                retryDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    private fun cancel(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runDeletion(downloadId, DiagnosticOperation.DownloadCancel) {
                cancelDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    private fun delete(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runDeletion(downloadId, DiagnosticOperation.DownloadDelete) {
                deleteDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    /**
     * Loads one local presentation image for a currently rendered card or detail view. The
     * composable owns the call lifecycle, so changing account, item, generation, or role cancels
     * the old read before it can update the newly rendered image.
     */
    suspend fun readArtwork(
        downloadId: DownloadId,
        role: OfflineArtworkRole,
    ): ByteArray? {
        val reader = readDownloadArtworkUseCase ?: return null
        return try {
            withContext(workDispatcher) { reader(accountIdentity, downloadId, role) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Throwable) {
            downloadsViewModelLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "presentation-read",
                    event = "failed",
                    operation = DiagnosticOperation.DownloadLoad,
                    throwable = exception,
                )
            }
            null
        }
    }

    private fun findRecord(downloadId: String): DownloadRecord? =
        _state.value.records.firstOrNull { record -> record.downloadId.value == downloadId }

    private fun runCommand(
        downloadId: String,
        operation: DiagnosticOperation,
        command: suspend () -> DownloadCommandResult,
    ) {
        if (_state.value.inFlightDownloadId != null || _state.value.isBulkResumeInFlight) {
            return
        }
        viewModelScope.launch {
            reduce(DownloadsChange.CommandStarted(downloadId))
            val result =
                try {
                    withContext(workDispatcher) { command() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    downloadsViewModelLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "command",
                            event = "failed",
                            operation = operation,
                            throwable = exception,
                        )
                    }
                    null
                }
            reduce(DownloadsChange.CommandFinished(result))
            requestUsageRefresh()
        }
    }

    private fun runDeletion(
        downloadId: String,
        operation: DiagnosticOperation,
        command: suspend () -> DownloadDeletionResult,
    ) {
        if (_state.value.inFlightDownloadId != null || _state.value.isBulkResumeInFlight) {
            return
        }
        viewModelScope.launch {
            reduce(DownloadsChange.CommandStarted(downloadId))
            val result =
                try {
                    withContext(workDispatcher) { command() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    downloadsViewModelLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "deletion",
                            event = "failed",
                            operation = operation,
                            throwable = exception,
                        )
                    }
                    null
                }
            reduce(DownloadsChange.DeletionFinished(result))
            requestUsageRefresh()
        }
    }

    private fun requestUsageRefresh() {
        usageRefreshRequests.trySend(Unit)
    }

    private fun requestLeaseRefresh() {
        val leaseUseCase = isDownloadArtifactLeasedUseCase ?: return
        leaseRefreshInitialized = true
        leaseRefreshJob?.cancel()
        leaseRefreshJob =
            viewModelScope.launch {
                val completed = _state.value.records.filter { record -> record.state == DownloadState.Completed }
                val leased =
                    completed
                        .mapNotNull { record ->
                            val inUse =
                                try {
                                    withContext(workDispatcher) { leaseUseCase(accountIdentity, record.downloadId) }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (exception: Throwable) {
                                    downloadsViewModelLogger.w {
                                        formatSafeFailureDiagnostic(
                                            stage = "lease",
                                            event = "failed",
                                            operation = DiagnosticOperation.DownloadLease,
                                            throwable = exception,
                                        )
                                    }
                                    false
                                }
                            record.downloadId.value.takeIf { inUse }
                        }.toSet()
                reduce(DownloadsChange.LeasesUpdated(leased))
            }
    }

    private suspend fun refreshUsageNow() {
        reduce(DownloadsChange.UsageStarted)
        val usage =
            try {
                withContext(workDispatcher) { getDownloadUsageUseCase(accountIdentity) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                downloadsViewModelLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "usage",
                        event = "failed",
                        operation = DiagnosticOperation.GetDownloadUsage,
                        throwable = exception,
                    )
                }
                reduce(DownloadsChange.UsageFailed)
                return
            }
        val settings =
            try {
                withContext(workDispatcher) { getDownloadSettingsUseCase() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                downloadsViewModelLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "settings",
                        event = "failed",
                        operation = DiagnosticOperation.GetDownloadSettings,
                        throwable = exception,
                    )
                }
                reduce(DownloadsChange.UsageFailed)
                return
            }
        reduce(DownloadsChange.UsageUpdated(usage, settings))
    }
}

private const val USAGE_REFRESH_COOLDOWN_MS = 500L
private const val TICKS_PER_MILLISECOND = 10_000L

private data class DownloadRecordsProjection(
    val sections: List<DownloadsSection>,
    val detailsByDownloadId: Map<DownloadId, DownloadDetailUi>,
)

private fun DownloadCommandResult.diagnosticValue(): String =
    when (this) {
        DownloadCommandResult.Applied -> "Applied"
        DownloadCommandResult.NotFound -> "NotFound"
        DownloadCommandResult.AccountNotOwned -> "AccountNotOwned"
        DownloadCommandResult.RemovalInProgress -> "RemovalInProgress"
        DownloadCommandResult.ActiveAttemptUnavailable -> "ActiveAttemptUnavailable"
        DownloadCommandResult.SchedulingRejected -> "SchedulingRejected"
        DownloadCommandResult.InvalidState -> "InvalidState"
    }

private val downloadsViewModelLogger = diagnosticLogger(DiagnosticTag.DownloadsViewModel)
