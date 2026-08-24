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
import com.jellyscope.core.domain.action.RetryDownloadAction
import com.jellyscope.core.domain.action.RetryDownloadSchedulingAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.IsDownloadArtifactLeasedUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

data class DownloadsUiState(
    val records: List<DownloadRecord> = emptyList(),
    val sections: List<DownloadsSection> = downloadSections(records),
    val usage: DownloadUsage? = null,
    val settings: DownloadSettings? = null,
    val isLoading: Boolean = true,
    val isRefreshingUsage: Boolean = false,
    val inFlightDownloadId: String? = null,
    val leasedDownloadIds: Set<String> = emptySet(),
    val error: DownloadsUiError? = null,
)

sealed interface DownloadsUiError {
    data object LoadFailed : DownloadsUiError

    data object CommandRejected : DownloadsUiError

    data object SchedulingRetryRejected : DownloadsUiError

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
    private val retryDownloadAction: RetryDownloadAction,
    private val retryDownloadSchedulingAction: RetryDownloadSchedulingAction,
    private val cancelDownloadAction: CancelDownloadAction,
    private val deleteDownloadAction: DeleteDownloadAction,
    private val isDownloadArtifactLeasedUseCase: IsDownloadArtifactLeasedUseCase? = null,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val accountIdentity: AccountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()
    private val usageRefreshRequests =
        MutableSharedFlow<Unit>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private var leaseRefreshJob: Job? = null
    private var leaseRefreshInitialized = false

    init {
        viewModelScope.launch {
            usageRefreshRequests
                .debounce(500L)
                .collectLatest { refreshUsageNow() }
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
                    _state.update { current -> current.copy(isLoading = false, error = DownloadsUiError.LoadFailed) }
                }.collect { records ->
                    _state.update { current -> current.copy(records = records, sections = downloadSections(records), isLoading = false) }
                    requestUsageRefresh()
                    if (!leaseRefreshInitialized) {
                        requestLeaseRefresh()
                    }
                }
        }
        requestUsageRefresh()
    }

    fun refresh() {
        requestUsageRefresh()
        requestLeaseRefresh()
    }

    fun configureQuota(quotaBytes: Long?) {
        viewModelScope.launch {
            _state.update { current -> current.copy(isRefreshingUsage = true, error = null) }
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
                _state.update { current -> current.copy(isRefreshingUsage = false, error = DownloadsUiError.QuotaRejected) }
            }
        }
    }

    fun pause(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadPause) {
                pauseDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    fun resume(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadResume) {
                resumeDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    fun retry(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runCommand(downloadId, DiagnosticOperation.DownloadRetry) {
                retryDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    /** Wakes native scheduling for an already queued row; [retry] requeues failures. */
    fun retryScheduling(downloadId: String) {
        findRecord(downloadId)
            ?.takeIf { record -> record.state == DownloadState.Queued }
            ?.let { record -> runSchedulingRetry(record.downloadId.value, DiagnosticOperation.DownloadSchedulingWake) }
    }

    fun cancel(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runDeletion(downloadId, DiagnosticOperation.DownloadCancel) {
                cancelDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    fun delete(downloadId: String) {
        findRecord(downloadId)?.let { record ->
            runDeletion(downloadId, DiagnosticOperation.DownloadDelete) {
                deleteDownloadAction(accountIdentity, record.downloadId)
            }
        }
    }

    private fun findRecord(downloadId: String): DownloadRecord? =
        _state.value.records.firstOrNull { record -> record.downloadId.value == downloadId }

    private fun runCommand(
        downloadId: String,
        operation: DiagnosticOperation,
        command: suspend () -> DownloadCommandResult,
    ) {
        if (_state.value.inFlightDownloadId != null) {
            return
        }
        viewModelScope.launch {
            _state.update { current -> current.copy(inFlightDownloadId = downloadId, error = null) }
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
            _state.update { current ->
                current.copy(
                    inFlightDownloadId = null,
                    error = if (result == DownloadCommandResult.Applied) null else DownloadsUiError.CommandRejected,
                )
            }
            requestUsageRefresh()
        }
    }

    private fun runSchedulingRetry(
        downloadId: String,
        operation: DiagnosticOperation,
    ) {
        if (_state.value.inFlightDownloadId != null) {
            return
        }
        viewModelScope.launch {
            _state.update { current -> current.copy(inFlightDownloadId = downloadId, error = null) }
            val result =
                try {
                    withContext(workDispatcher) { retryDownloadSchedulingAction() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    downloadsViewModelLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "scheduling-wake",
                            event = "failed",
                            operation = operation,
                            throwable = exception,
                        )
                    }
                    Result.failure<Unit>(exception)
                }
            _state.update { current ->
                current.copy(
                    inFlightDownloadId = null,
                    error = if (result.isSuccess) null else DownloadsUiError.SchedulingRetryRejected,
                )
            }
        }
    }

    private fun runDeletion(
        downloadId: String,
        operation: DiagnosticOperation,
        command: suspend () -> DownloadDeletionResult,
    ) {
        if (_state.value.inFlightDownloadId != null) {
            return
        }
        viewModelScope.launch {
            _state.update { current -> current.copy(inFlightDownloadId = downloadId, error = null) }
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
            _state.update { current ->
                current.copy(
                    inFlightDownloadId = null,
                    error =
                        when (result) {
                            DownloadDeletionResult.Deleted -> null
                            DownloadDeletionResult.ArtifactInUse -> DownloadsUiError.ArtifactInUse
                            else -> DownloadsUiError.CommandRejected
                        },
                )
            }
            requestUsageRefresh()
        }
    }

    private fun requestUsageRefresh() {
        usageRefreshRequests.tryEmit(Unit)
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
                _state.update { current -> current.copy(leasedDownloadIds = leased) }
            }
    }

    /** Debounces checkpoint bursts before refreshing usage. */
    private suspend fun refreshUsageNow() {
        _state.update { current -> current.copy(isRefreshingUsage = true) }
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
                _state.update { current -> current.copy(isRefreshingUsage = false, error = DownloadsUiError.LoadFailed) }
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
                _state.update { current -> current.copy(isRefreshingUsage = false, error = DownloadsUiError.LoadFailed) }
                return
            }
        _state.update { current ->
            current.copy(usage = usage, settings = settings, isRefreshingUsage = false)
        }
    }
}

private val downloadsViewModelLogger = diagnosticLogger(DiagnosticTag.DownloadsViewModel)
