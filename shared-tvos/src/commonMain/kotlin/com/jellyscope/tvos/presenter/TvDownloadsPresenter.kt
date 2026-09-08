// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

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
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.wholeGbDownloadQuotaBytes
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.IsDownloadArtifactLeasedUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvDownloadsPresenter(
    private val session: Session,
    private val observeDownloads: ObserveDownloadsUseCase,
    private val getDownloadUsage: GetDownloadUsageUseCase,
    private val getDownloadSettings: GetDownloadSettingsUseCase,
    private val getOfflinePlaybackPlan: GetOfflinePlaybackPlanUseCase,
    private val configureDownloadQuota: ConfigureDownloadQuotaAction,
    private val pauseDownload: PauseDownloadAction,
    private val resumeDownload: ResumeDownloadAction,
    private val resumePausedDownloads: ResumePausedDownloadsAction,
    private val retryDownload: RetryDownloadAction,
    private val wakeDownloadsQueue: WakeDownloadsQueueAction,
    private val cancelDownload: CancelDownloadAction,
    private val deleteDownload: DeleteDownloadAction,
    private val isDownloadArtifactLeased: IsDownloadArtifactLeasedUseCase,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val accountIdentity: AccountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow(TvDownloadsState())
    val state: StateFlow<TvDownloadsState> = _state.asStateFlow()

    private var records: List<DownloadRecord> = emptyList()
    private var projectionGeneration = 0L
    private var storageGeneration = 0L
    private var observeJob: Job? = null
    private var projectionJob: Job? = null
    private var storageJob: Job? = null

    init {
        startObservation()
        refreshStorage()
    }

    fun watchState(onChange: (TvDownloadsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun refresh() {
        if (observeJob?.isActive != true) startObservation()
        projectRows(records)
        refreshStorage()
    }

    fun configureQuota(wholeGb: Long) {
        val quotaBytes = wholeGbDownloadQuotaBytes(wholeGb)
        val maximum = state.value.storage?.maximumConfigurableQuotaBytes
        if (quotaBytes == null || (maximum != null && quotaBytes > maximum)) {
            _state.update { current -> current.copy(error = TvDownloadsError.QuotaRejected) }
            return
        }
        if (state.value.isRefreshingStorage) return
        scope.launch {
            _state.update { current -> current.copy(isRefreshingStorage = true, error = null) }
            val succeeded =
                try {
                    withContext(workDispatcher) { configureDownloadQuota(quotaBytes) }
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logFailure("quota", DiagnosticOperation.DownloadQuota, error)
                    false
                }
            if (!succeeded) {
                _state.update { current -> current.copy(isRefreshingStorage = false, error = TvDownloadsError.QuotaRejected) }
            } else {
                refreshStorage()
            }
        }
    }

    fun pause(downloadId: String) {
        val record = findRecord(downloadId) ?: return staleCommand()
        runCommand(record, DiagnosticOperation.DownloadPause) { pauseDownload(accountIdentity, it.downloadId) }
    }

    fun resume(downloadId: String) {
        val record = findRecord(downloadId) ?: return staleCommand()
        runCommand(record, DiagnosticOperation.DownloadResume) { resumeDownload(accountIdentity, it.downloadId) }
    }

    fun retry(downloadId: String) {
        val record = findRecord(downloadId) ?: return staleCommand()
        runCommand(record, DiagnosticOperation.DownloadRetry) { retryDownload(accountIdentity, it.downloadId) }
    }

    fun resumeAll() {
        val current = state.value
        if (current.inFlightDownloadId != null || current.isBulkResumeInFlight || current.isQueueWakeInFlight) return
        val snapshot = records
        scope.launch {
            val ids =
                withContext(workDispatcher) {
                    snapshot.filter { record -> record.state == DownloadState.Paused }.map(DownloadRecord::downloadId)
                }
            if (
                ids.isEmpty() ||
                state.value.inFlightDownloadId != null ||
                state.value.isBulkResumeInFlight ||
                state.value.isQueueWakeInFlight
            ) {
                return@launch
            }
            _state.update { value -> value.copy(isBulkResumeInFlight = true, error = null) }
            val result =
                try {
                    withContext(workDispatcher) { resumePausedDownloads(accountIdentity, ids) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logFailure("resume-all", DiagnosticOperation.DownloadResumeAll, error)
                    null
                }
            _state.update { value ->
                value.copy(
                    isBulkResumeInFlight = false,
                    error = if (result == DownloadCommandResult.Applied) null else TvDownloadsError.CommandRejected,
                )
            }
            refreshStorage()
        }
    }

    fun resumeQueuedDownloads() {
        val current = state.value
        if (
            !current.hasQueuedDownloads ||
            current.inFlightDownloadId != null ||
            current.isBulkResumeInFlight ||
            current.isQueueWakeInFlight
        ) {
            return
        }
        _state.update { value -> value.copy(isQueueWakeInFlight = true, error = null) }
        scope.launch {
            val failure =
                try {
                    withContext(workDispatcher) { wakeDownloadsQueue().exceptionOrNull() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    error
                }
            if (failure != null) {
                logFailure("scheduling-wake", DiagnosticOperation.DownloadSchedulingWake, failure)
            }
            _state.update { value ->
                value.copy(
                    isQueueWakeInFlight = false,
                    error = if (failure == null) null else TvDownloadsError.QueueWakeRejected,
                )
            }
        }
    }

    fun cancel(
        downloadId: String,
        expectedAttemptGeneration: Long,
    ) {
        val record = findRecord(downloadId, expectedAttemptGeneration) ?: return staleCommand()
        runDeletion(record, DiagnosticOperation.DownloadCancel) { cancelDownload(accountIdentity, it.downloadId) }
    }

    fun delete(
        downloadId: String,
        expectedAttemptGeneration: Long,
    ) {
        val record = findRecord(downloadId, expectedAttemptGeneration) ?: return staleCommand()
        if (isProjectedAsLeased(downloadId)) {
            _state.update { current -> current.copy(error = TvDownloadsError.ArtifactInUse) }
            return
        }
        runDeletion(record, DiagnosticOperation.DownloadDelete) { deleteDownload(accountIdentity, it.downloadId) }
    }

    private fun projectRows(snapshot: List<DownloadRecord>) {
        val generation = ++projectionGeneration
        projectionJob?.cancel()
        projectionJob =
            scope.launch {
                val projection =
                    try {
                        withContext(workDispatcher) {
                            val completed = snapshot.filter { record -> record.state == DownloadState.Completed }
                            val playable =
                                completed
                                    .mapNotNull { record ->
                                        record.downloadId.value.takeIf { hasPlayableArtifact(record) }
                                    }.toSet()
                            val leased =
                                completed
                                    .mapNotNull { record ->
                                        record.downloadId.value.takeIf { hasActiveLease(record) }
                                    }.toSet()
                            TvDownloadsProjection(
                                sections = tvDownloadSections(snapshot, playable, leased),
                                hasPausedDownloads = snapshot.any { record -> record.state == DownloadState.Paused },
                                hasQueuedDownloads = snapshot.any { record -> record.state == DownloadState.Queued },
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logFailure("projection", DiagnosticOperation.DownloadObserve, error)
                        if (generation == projectionGeneration) {
                            _state.update { current -> current.copy(isLoading = false, error = TvDownloadsError.LoadFailed) }
                        }
                        return@launch
                    }
                if (generation != projectionGeneration || records !== snapshot) return@launch
                _state.update { current ->
                    current.copy(
                        sections = projection.sections,
                        isLoading = false,
                        hasPausedDownloads = projection.hasPausedDownloads,
                        hasQueuedDownloads = projection.hasQueuedDownloads,
                        error = current.error.takeUnless { error -> error == TvDownloadsError.LoadFailed },
                    )
                }
            }
    }

    private fun startObservation() {
        if (observeJob?.isActive == true) return
        observeJob =
            scope.launch {
                observeDownloads(accountIdentity)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        logFailure("observe", DiagnosticOperation.DownloadObserve, error)
                        _state.update { current -> current.copy(isLoading = false, error = TvDownloadsError.LoadFailed) }
                    }.collect { observed ->
                        records = observed
                        projectRows(observed)
                        refreshStorage()
                    }
            }
    }

    private fun refreshStorage() {
        val generation = ++storageGeneration
        storageJob?.cancel()
        storageJob =
            scope.launch {
                _state.update { current -> current.copy(isRefreshingStorage = true) }
                val storage =
                    try {
                        withContext(workDispatcher) {
                            val settings = getDownloadSettings()
                            getDownloadUsage(accountIdentity).toTvDownloadStorageState(settings)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logFailure("storage", DiagnosticOperation.GetDownloadUsage, error)
                        if (generation == storageGeneration) {
                            _state.update { current ->
                                current.copy(isRefreshingStorage = false, isLoading = false, error = TvDownloadsError.LoadFailed)
                            }
                        }
                        return@launch
                    }
                if (generation != storageGeneration) return@launch
                _state.update { current -> current.copy(storage = storage, isRefreshingStorage = false) }
            }
    }

    private fun runCommand(
        record: DownloadRecord,
        operation: DiagnosticOperation,
        command: suspend (DownloadRecord) -> DownloadCommandResult,
    ) {
        if (
            state.value.inFlightDownloadId != null ||
            state.value.isBulkResumeInFlight ||
            state.value.isQueueWakeInFlight
        ) {
            return
        }
        scope.launch {
            _state.update { current -> current.copy(inFlightDownloadId = record.downloadId.value, error = null) }
            val result =
                try {
                    withContext(workDispatcher) { command(record) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logFailure("command", operation, error)
                    null
                }
            _state.update { current ->
                current.copy(
                    inFlightDownloadId = null,
                    error = if (result == DownloadCommandResult.Applied) null else TvDownloadsError.CommandRejected,
                )
            }
            refreshStorage()
        }
    }

    private fun runDeletion(
        record: DownloadRecord,
        operation: DiagnosticOperation,
        command: suspend (DownloadRecord) -> DownloadDeletionResult,
    ) {
        if (
            state.value.inFlightDownloadId != null ||
            state.value.isBulkResumeInFlight ||
            state.value.isQueueWakeInFlight
        ) {
            return
        }
        scope.launch {
            _state.update { current -> current.copy(inFlightDownloadId = record.downloadId.value, error = null) }
            val result =
                try {
                    withContext(workDispatcher) { command(record) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logFailure("deletion", operation, error)
                    null
                }
            _state.update { current ->
                current.copy(
                    inFlightDownloadId = null,
                    error =
                        when (result) {
                            DownloadDeletionResult.Deleted -> null
                            DownloadDeletionResult.ArtifactInUse -> TvDownloadsError.ArtifactInUse
                            else -> TvDownloadsError.CommandRejected
                        },
                )
            }
            refreshStorage()
        }
    }

    private fun findRecord(
        downloadId: String,
        attemptGeneration: Long? = null,
    ): DownloadRecord? =
        records.firstOrNull { record ->
            record.downloadId.value == downloadId &&
                (attemptGeneration == null || record.attemptGeneration == attemptGeneration)
        }

    private fun isProjectedAsLeased(downloadId: String): Boolean {
        for (section in state.value.sections) {
            for (row in section.rows) {
                if (row.id == downloadId) return row.isLeased
            }
        }
        return false
    }

    private suspend fun hasPlayableArtifact(record: DownloadRecord): Boolean =
        try {
            getOfflinePlaybackPlan(accountIdentity, record.downloadId)?.attemptGeneration == record.attemptGeneration
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logFailure("offline-artifact", DiagnosticOperation.DownloadLoad, error)
            false
        }

    private suspend fun hasActiveLease(record: DownloadRecord): Boolean =
        try {
            isDownloadArtifactLeased(accountIdentity, record.downloadId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logFailure("lease", DiagnosticOperation.DownloadLease, error)
            false
        }

    private fun staleCommand() {
        _state.update { current -> current.copy(error = TvDownloadsError.StaleConfirmation) }
    }

    private fun logFailure(
        stage: String,
        operation: DiagnosticOperation,
        error: Throwable,
    ) {
        tvDownloadsLogger.w {
            formatSafeFailureDiagnostic(
                stage = stage,
                event = "failed",
                operation = operation,
                throwable = error,
            )
        }
    }
}

private data class TvDownloadsProjection(
    val sections: List<TvDownloadSection>,
    val hasPausedDownloads: Boolean,
    val hasQueuedDownloads: Boolean,
)

private val tvDownloadsLogger = diagnosticLogger(DiagnosticTag.DownloadsViewModel)
