// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.repository.DownloadCommandCoordinator
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.domain.action.CancelDownloadAction
import com.jellyscope.core.domain.action.ConfigureDownloadQuotaAction
import com.jellyscope.core.domain.action.DeleteDownloadAction
import com.jellyscope.core.domain.action.PauseDownloadAction
import com.jellyscope.core.domain.action.ResumeDownloadAction
import com.jellyscope.core.domain.action.ResumePausedDownloadsAction
import com.jellyscope.core.domain.action.RetryDownloadAction
import com.jellyscope.core.domain.action.WakeDownloadsQueueAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsViewModelTest {
    @Test
    fun loadsOnlyTheSessionAccountAndRefreshesLocalUsage() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = TestDownloadRepository()
                val account =
                    Session(
                        serverUrl = "https://jellyfin.example",
                        serverId = "server-1",
                        serverName = "Jellyfin",
                        userId = "user-1",
                        userName = "Murali",
                        accessToken = "token",
                        deviceId = "device",
                    )
                val lifecycleHost = TestDownloadLifecycleHost()
                val commandCoordinator = TestDownloadCommandCoordinator()
                val viewModel =
                    DownloadsViewModel(
                        session = account,
                        observeDownloadsUseCase = ObserveDownloadsUseCase(repository),
                        getDownloadUsageUseCase = GetDownloadUsageUseCase(repository),
                        getDownloadSettingsUseCase = GetDownloadSettingsUseCase(repository),
                        configureDownloadQuotaAction = ConfigureDownloadQuotaAction(repository),
                        pauseDownloadAction = PauseDownloadAction(commandCoordinator),
                        resumeDownloadAction = ResumeDownloadAction(repository, lifecycleHost),
                        resumePausedDownloadsAction = ResumePausedDownloadsAction(repository, lifecycleHost),
                        retryDownloadAction = RetryDownloadAction(repository, lifecycleHost),
                        wakeDownloadsQueueAction = WakeDownloadsQueueAction(lifecycleHost),
                        cancelDownloadAction = CancelDownloadAction(commandCoordinator),
                        deleteDownloadAction = DeleteDownloadAction(repository),
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()

                assertEquals(AccountIdentity("server-1", "user-1"), repository.observedAccount)
                assertEquals(AccountIdentity("server-1", "user-1"), repository.usageAccount)
                assertEquals(repository.usage, viewModel.state.value.usage)
                assertEquals(repository.settings, viewModel.state.value.settings)
                assertEquals(false, viewModel.state.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun pauseFailureEmitsOnlyTypedSafeDiagnostic() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.DownloadsViewModel.wireValue) {
                            messages += message
                            assertEquals(null, throwable)
                        }
                    }
                }
            try {
                val repository = TestDownloadRepository()
                repository.records.value = listOf(testDownloadRecord(DownloadState.Queued))
                val lifecycleHost = TestDownloadLifecycleHost()
                val viewModel =
                    DownloadsViewModel(
                        session = testSession(),
                        observeDownloadsUseCase = ObserveDownloadsUseCase(repository),
                        getDownloadUsageUseCase = GetDownloadUsageUseCase(repository),
                        getDownloadSettingsUseCase = GetDownloadSettingsUseCase(repository),
                        configureDownloadQuotaAction = ConfigureDownloadQuotaAction(repository),
                        pauseDownloadAction =
                            PauseDownloadAction(
                                TestDownloadCommandCoordinator(
                                    failure = IllegalStateException("download identity message"),
                                ),
                            ),
                        resumeDownloadAction = ResumeDownloadAction(repository, lifecycleHost),
                        resumePausedDownloadsAction = ResumePausedDownloadsAction(repository, lifecycleHost),
                        retryDownloadAction = RetryDownloadAction(repository, lifecycleHost),
                        wakeDownloadsQueueAction = WakeDownloadsQueueAction(lifecycleHost),
                        cancelDownloadAction = CancelDownloadAction(TestDownloadCommandCoordinator()),
                        deleteDownloadAction = DeleteDownloadAction(repository),
                        workDispatcher = dispatcher,
                    )
                Logger.setLogWriters(writer)
                advanceUntilIdle()
                viewModel.pause("download-queued")
                advanceUntilIdle()
            } finally {
                Logger.setLogWriters(emptyList())
                Dispatchers.resetMain()
            }

            val message = messages.single { value -> value.contains("operation=downloadPause") }
            assertEquals("stage=command event=failed operation=downloadPause exceptionType=IllegalStateException", message)
            assertEquals(message, LogScrubber.capture(DiagnosticTag.DownloadsViewModel.wireValue, message))
            assertFalse(message.contains("download identity message"))
            assertTrue(message.contains("exceptionType=IllegalStateException"))
        }

    @Test
    fun resumePausedDownloadsAppliesSiblingRowsAndWakesOnceAfterOneRejection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = TestDownloadRepository()
                val firstPaused = testDownloadRecord(DownloadState.Paused, "download-paused-a")
                val quotaBlocked = testDownloadRecord(DownloadState.BlockedByQuota, "download-blocked")
                val secondPaused = testDownloadRecord(DownloadState.Paused, "download-paused-b")
                repository.records.value = listOf(firstPaused, quotaBlocked, secondPaused)
                repository.rejectedResumeIds += firstPaused.downloadId
                val lifecycleHost = TestDownloadLifecycleHost()
                val viewModel =
                    DownloadsViewModel(
                        session = testSession(),
                        observeDownloadsUseCase = ObserveDownloadsUseCase(repository),
                        getDownloadUsageUseCase = GetDownloadUsageUseCase(repository),
                        getDownloadSettingsUseCase = GetDownloadSettingsUseCase(repository),
                        configureDownloadQuotaAction = ConfigureDownloadQuotaAction(repository),
                        pauseDownloadAction = PauseDownloadAction(TestDownloadCommandCoordinator()),
                        resumeDownloadAction = ResumeDownloadAction(repository, lifecycleHost),
                        resumePausedDownloadsAction = ResumePausedDownloadsAction(repository, lifecycleHost),
                        retryDownloadAction = RetryDownloadAction(repository, lifecycleHost),
                        wakeDownloadsQueueAction = WakeDownloadsQueueAction(lifecycleHost),
                        cancelDownloadAction = CancelDownloadAction(TestDownloadCommandCoordinator()),
                        deleteDownloadAction = DeleteDownloadAction(repository),
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()
                viewModel.resumePausedDownloads()
                advanceUntilIdle()

                assertEquals(listOf(firstPaused.downloadId, secondPaused.downloadId), repository.resumeCalls)
                assertEquals(1, lifecycleHost.wakeCount)
                assertEquals(
                    listOf(DownloadState.Paused, DownloadState.BlockedByQuota, DownloadState.Queued),
                    viewModel.state.value.records
                        .map(DownloadRecord::state),
                )
                assertEquals(null, viewModel.state.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun testSession() =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Jellyfin",
        userId = "user-1",
        userName = "Murali",
        accessToken = "token",
        deviceId = "device",
    )

private fun testDownloadRecord(
    state: DownloadState,
    downloadId: String = "download-queued",
): DownloadRecord =
    DownloadRecord(
        request =
            DownloadRequest(
                downloadId = DownloadId(downloadId),
                businessKey = DownloadBusinessKey(AccountIdentity("server-1", "user-1"), "item-1", "source-1"),
                quality = DownloadQuality.Original,
                artifactKind = DownloadArtifactKind.OriginalFile,
                selectedAudioStreamIndex = null,
                subtitleSelection = DownloadSubtitleSelection.Off,
                admissionEstimateBytes = 100L,
                initialReservationBytes = 100L,
                artifactKey = DownloadArtifactKey("artifact-$downloadId"),
                snapshot =
                    OfflineMediaSnapshot(
                        title = "Queued item",
                        itemKind = MediaKind.Movie,
                        backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                    ),
                createdAtEpochMs = 1L,
            ),
        fifoSequence = 1L,
        state = state,
        reservationBytes = if (state == DownloadState.Completed) 0L else 100L,
        physicalBytes = 0L,
        checkpointBytes = 0L,
        attemptGeneration = 0L,
        failure = DownloadFailure.Network.takeIf { state == DownloadState.Failed },
        updatedAtEpochMs = 1L,
    )

private class TestDownloadLifecycleHost(
    wakeResults: List<Result<Unit>> = listOf(Result.success(Unit)),
) : DownloadLifecycleHost {
    private val pendingWakeResults = wakeResults.toMutableList()
    var wakeCount = 0
        private set

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun wakeFromUserAction(): Result<Unit> {
        wakeCount += 1
        return if (pendingWakeResults.isEmpty()) {
            Result.success(Unit)
        } else {
            pendingWakeResults.removeAt(0)
        }
    }
}

private class TestDownloadCommandCoordinator(
    private val failure: Throwable? = null,
) : DownloadCommandCoordinator {
    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult {
        failure?.let { error -> throw error }
        return DownloadCommandResult.Applied
    }

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.Deleted
}

private class TestDownloadRepository : DownloadRepository {
    val usage =
        DownloadUsage(
            physicalBytes = 10L,
            currentAccountPhysicalBytes = 6L,
            otherAccountsPhysicalBytes = 4L,
            outstandingReservationBytes = 20L,
            projectedCommittedBytes = 30L,
            quotaBytes = null,
            remainingQuotaBytes = null,
            deviceAvailableBytes = 100L,
            safetyReserveBytes = 1L,
            maximumConfigurableQuotaBytes = 100L,
            overAllocation = false,
        )
    val settings = DownloadSettings(quotaBytes = null, nextFifoSequence = 1L, membershipRevision = 0L)
    val records = MutableStateFlow(emptyList<DownloadRecord>())
    var observedAccount: AccountIdentity? = null
    var usageAccount: AccountIdentity? = null
    var retryCalls: Int = 0
    val resumeCalls = mutableListOf<DownloadId>()
    val rejectedResumeIds = mutableSetOf<DownloadId>()

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> {
        observedAccount = accountIdentity
        return records
    }

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ) = null

    override suspend fun getDownloadSettings() = settings

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage {
        usageAccount = accountIdentity
        return usage
    }

    override suspend fun setQuotaBytes(quotaBytes: Long?) = settings

    override suspend fun enqueue(request: com.jellyscope.core.domain.model.DownloadRequest): DownloadEnqueueResult =
        DownloadEnqueueResult.RemovalInProgress

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ) = DownloadCommandResult.Applied

    override suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult {
        val index = records.value.indexOfFirst { record -> record.downloadId == downloadId }
        if (index < 0) return DownloadCommandResult.NotFound
        resumeCalls += downloadId
        val record = records.value[index]
        if (record.businessKey.accountIdentity != accountIdentity) return DownloadCommandResult.AccountNotOwned
        if (record.state != DownloadState.Paused && record.state != DownloadState.BlockedByQuota) {
            return DownloadCommandResult.InvalidState
        }
        if (downloadId in rejectedResumeIds) return DownloadCommandResult.InvalidState
        records.value = records.value.toMutableList().also { items -> items[index] = record.copy(state = DownloadState.Queued) }
        return DownloadCommandResult.Applied
    }

    override suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult {
        retryCalls += 1
        return DownloadCommandResult.Applied
    }

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ) = DownloadDeletionResult.Deleted

    override suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ) = DownloadDeletionResult.Deleted

    override suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
    ) = false
}
