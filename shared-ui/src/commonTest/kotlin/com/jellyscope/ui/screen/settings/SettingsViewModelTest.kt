// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.ClearLocalSubtitlesAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SavePlayerDeviceSettingsAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.action.SetOpenSubtitlesApiKeyAction
import com.jellyscope.core.domain.action.SetPictureInPictureEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.action.SetVerboseLogcatEnabledAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPolicy
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.GetAvailablePlayerBackendsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitleResultPreferenceUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitlesApiKeyUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlayerDevicePolicyUseCase
import com.jellyscope.core.domain.usecase.GetVerboseLogcatStateUseCase
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObservePictureInPictureEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.core.domain.usecase.RefreshPlayerDevicePolicyUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.LogScrubber
import com.jellyscope.ui.AppInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @Test
    fun refreshDownloadUsageReloadsLatestUsageForResume() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = SettingsDownloadRepository()
                val viewModel =
                    settingsViewModel(
                        getDownloadUsageUseCase = GetDownloadUsageUseCase(repository),
                        getDownloadSettingsUseCase = GetDownloadSettingsUseCase(repository),
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()
                assertEquals(
                    10L,
                    viewModel.state.value.downloadUsage
                        ?.physicalBytes,
                )

                repository.usage =
                    repository.usage.copy(
                        physicalBytes = 42L,
                        currentAccountPhysicalBytes = 38L,
                    )
                viewModel.refreshDownloadUsage()
                advanceUntilIdle()

                assertEquals(
                    42L,
                    viewModel.state.value.downloadUsage
                        ?.physicalBytes,
                )
                assertEquals(repository.settings, viewModel.state.value.downloadSettings)
                assertEquals(2, repository.usageReads)
                assertEquals(AccountIdentity("server-1", "user-1"), repository.usageAccount)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun overlappingDownloadUsageRefreshesPublishOnlyTheLatestRead() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = SettingsDownloadRepository()
                val first = CompletableDeferred<DownloadUsage>()
                val second = CompletableDeferred<DownloadUsage>()
                repository.usageGates.addLast(first)
                repository.usageGates.addLast(second)
                val viewModel =
                    settingsViewModel(
                        getDownloadUsageUseCase = GetDownloadUsageUseCase(repository),
                        getDownloadSettingsUseCase = GetDownloadSettingsUseCase(repository),
                        workDispatcher = dispatcher,
                    )

                runCurrent()
                assertEquals(1, repository.usageReads)
                viewModel.refreshDownloadUsage()
                runCurrent()
                assertEquals(2, repository.usageReads)

                val newerUsage =
                    repository.usage.copy(
                        physicalBytes = 42L,
                        currentAccountPhysicalBytes = 38L,
                    )
                second.complete(newerUsage)
                advanceUntilIdle()
                assertEquals(
                    42L,
                    viewModel.state.value.downloadUsage
                        ?.physicalBytes,
                )

                first.complete(repository.usage.copy(physicalBytes = 10L))
                advanceUntilIdle()
                assertEquals(
                    42L,
                    viewModel.state.value.downloadUsage
                        ?.physicalBytes,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun togglesVerboseLogcat() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore()
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val viewModel =
                    settingsViewModel(
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                sendLogs = settingsSendLogsAction(buffer, SettingsClientLogRepository()),
                            ),
                        workDispatcher = dispatcher,
                    )

                assertFalse(viewModel.state.value.verboseLogcatEnabled)

                viewModel.setVerboseLogcatEnabled(true)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.verboseLogcatEnabled)
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun togglesPlaybackInfoAtStart() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore()
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val viewModel =
                    settingsViewModel(
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                getPlaybackInfoAtStartState = GetPlaybackInfoAtStartStateUseCase(preferenceStore),
                                setPlaybackInfoAtStartEnabled = SetPlaybackInfoAtStartEnabledAction(preferenceStore),
                                sendLogs = settingsSendLogsAction(buffer, SettingsClientLogRepository()),
                            ),
                        workDispatcher = dispatcher,
                    )

                assertFalse(viewModel.state.value.playbackInfoAtStartEnabled)

                viewModel.setPlaybackInfoAtStartEnabled(true)
                runCurrent()

                assertTrue(preferenceStore.playbackInfoAtStartEnabled.value)
                assertTrue(viewModel.state.value.playbackInfoAtStartEnabled)
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun togglesCollectionAndSendsCapturedClientLogs() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore()
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val repository = SettingsClientLogRepository()
                val viewModel =
                    settingsViewModel(
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                sendLogs = settingsSendLogsAction(buffer, repository),
                            ),
                        workDispatcher = dispatcher,
                    )

                viewModel.setLogCollectionEnabled(true)
                advanceUntilIdle()
                buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")
                runCurrent()
                val event = async { viewModel.events.first() }
                viewModel.sendClientLogs()
                advanceUntilIdle()

                assertTrue(viewModel.state.value.collectLogs)
                assertEquals(1, repository.uploads.size)
                assertEquals(0, viewModel.state.value.logBufferSize.entryCount)
                assertEquals(
                    SettingsEvent.ClientLogsSent(SendClientLogsResult.Success("client-logs.txt")),
                    event.await(),
                )
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun collectionPreferenceFailureIsVisibleAndClearsAfterASuccessfulRetry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore(failEnabledWrites = true)
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val viewModel =
                    settingsViewModel(
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                sendLogs = settingsSendLogsAction(buffer, SettingsClientLogRepository()),
                            ),
                        workDispatcher = dispatcher,
                    )

                viewModel.setLogCollectionEnabled(true)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.logCollectionPreferenceError)
                assertFalse(viewModel.state.value.collectLogs)

                preferenceStore.failEnabledWrites = false
                viewModel.setLogCollectionEnabled(true)
                advanceUntilIdle()

                assertFalse(viewModel.state.value.logCollectionPreferenceError)
                assertTrue(viewModel.state.value.collectLogs)
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sendsSnapshotWithCollectionDisabledAndAnEmptyBuffer() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore(enabled = false)
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val repository = SettingsClientLogRepository()
                val viewModel =
                    settingsViewModel(
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                sendLogs = settingsSendLogsAction(buffer, repository),
                            ),
                        workDispatcher = dispatcher,
                    )
                runCurrent()

                val event = async { viewModel.events.first() }
                viewModel.sendClientLogs()
                advanceUntilIdle()

                assertFalse(viewModel.state.value.collectLogs)
                assertEquals(0, viewModel.state.value.logBufferSize.entryCount)
                assertEquals(1, repository.uploads.size)
                assertEquals(
                    SettingsEvent.ClientLogsSent(SendClientLogsResult.Success("client-logs.txt")),
                    event.await(),
                )
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sendsClientLogsWithTheLoadedBackendPreference() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bufferScope = CoroutineScope(SupervisorJob() + dispatcher)
            Dispatchers.setMain(dispatcher)
            try {
                val preferenceStore = FakeLogCollectionPreferenceStore(enabled = false)
                val playbackStore =
                    FakePlaybackPreferencesStore(
                        PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc),
                    )
                val buffer =
                    LogBufferStore(
                        preferenceStore = preferenceStore,
                        ownerScope = bufferScope,
                        actorDispatcher = dispatcher,
                    )
                val repository = SettingsClientLogRepository()
                val viewModel =
                    settingsViewModel(
                        playbackPreferencesStore = playbackStore,
                        logCollectionDependencies =
                            LogCollectionTestDependencies(
                                getState = GetLogCollectionStateUseCase(preferenceStore, buffer),
                                setEnabled = SetLogCollectionEnabledAction(preferenceStore, buffer),
                                getVerboseState = GetVerboseLogcatStateUseCase(preferenceStore),
                                setVerboseEnabled = SetVerboseLogcatEnabledAction(preferenceStore),
                                sendLogs =
                                    settingsSendLogsAction(
                                        buffer = buffer,
                                        repository = repository,
                                        provider = AndroidSettingsDeviceProfileProvider,
                                    ),
                            ),
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()

                viewModel.sendClientLogs()
                viewModel.setDefaultPlayerBackend(PlayerBackend.ExoPlayer)
                advanceUntilIdle()

                assertEquals(PlayerBackend.ExoPlayer, viewModel.state.value.playbackPreferences.defaultPlayerBackend)
                assertTrue(repository.uploads.single().contains("capabilities.backend=libvlc"))
            } finally {
                bufferScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun unavailableStoredBackendUsesPolicyDisplayFallbackWithoutRewritingPreference() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = GateDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val store =
                    FakePlaybackPreferencesStore(
                        PlaybackPreferences(defaultPlayerBackend = PlayerBackend.VlcKit),
                    )
                val policy = androidPlayerBackendPolicy()
                val provider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = policy
                        override val availableBackends = setOf(PlayerBackend.ExoPlayer, PlayerBackend.LibVlc)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = deviceCapabilities
                    }
                val viewModel =
                    settingsViewModel(
                        playbackPreferencesStore = store,
                        playerBackendPolicy = policy,
                        getAvailablePlayerBackendsUseCase = GetAvailablePlayerBackendsUseCase(provider),
                        workDispatcher = workDispatcher,
                    )

                runCurrent()
                assertTrue(workDispatcher.pendingCount > 0)
                while (workDispatcher.pendingCount > 0) {
                    workDispatcher.runNext()
                    runCurrent()
                }

                assertEquals(
                    listOf(
                        PlayerBackendChoice(PlayerBackend.ExoPlayer, available = true),
                        PlayerBackendChoice(PlayerBackend.Mpv, available = false),
                        PlayerBackendChoice(PlayerBackend.LibVlc, available = true),
                    ),
                    viewModel.state.value.playerBackendChoices,
                )
                assertEquals(PlayerBackend.ExoPlayer, viewModel.state.value.selectedPlayerBackend)
                assertEquals(PlayerBackend.VlcKit, viewModel.state.value.playbackPreferences.defaultPlayerBackend)
                assertTrue(store.savedPreferences.isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun logoutInvokesLogoutActionAndCompletesOnlyOnSuccess() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = FakeAuthRepository()
                var completed = false
                val viewModel =
                    settingsViewModel(
                        logoutAction = LogoutAction(repository),
                        workDispatcher = dispatcher,
                    )

                viewModel.logout { completed = true }
                advanceUntilIdle()

                assertEquals(1, repository.logoutCalls)
                assertTrue(completed)
                assertTrue(viewModel.state.value.isLoggingOut)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun logoutFailureResetsStateDoesNotCompleteAndEmitsSafeFailureEvent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    FakeAuthRepository(
                        logoutResult = Result.failure(SessionRemovalError.DownloadRemovalConfirmationRequired),
                    )
                val messages = mutableListOf<String>()
                val writer =
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag == DiagnosticTag.SettingsViewModel.wireValue) {
                                messages += message
                                assertEquals(null, throwable)
                            }
                        }
                    }
                val viewModel =
                    settingsViewModel(
                        logoutAction = LogoutAction(repository),
                        workDispatcher = dispatcher,
                    )
                val event = async { viewModel.events.first() }
                var completed = false

                Logger.setLogWriters(writer)
                viewModel.logout { completed = true }
                advanceUntilIdle()

                assertEquals(1, repository.logoutCalls)
                assertFalse(completed)
                assertFalse(viewModel.state.value.isLoggingOut)
                assertEquals(SettingsEvent.LogoutFailed, event.await())
                assertEquals(1, messages.size)
                val message = messages.single()
                assertEquals("stage=logout event=failed operation=logout exceptionType=DownloadRemovalConfirmationRequired", message)
                assertEquals(message, LogScrubber.capture(DiagnosticTag.SettingsViewModel.wireValue, message))
            } finally {
                Logger.setLogWriters(emptyList())
                Dispatchers.resetMain()
            }
        }

    @Test
    fun logoutConfirmationUsesDisplayedPreviewAndDismissalReleasesIt() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val preview =
                    DownloadRemovalPreview(
                        membershipRevision = 8L,
                        confirmations =
                            listOf(
                                DownloadRemovalConfirmation(
                                    accountIdentity = AccountIdentity(session.serverId, session.userId),
                                    membershipRevision = 8L,
                                    recordCount = 1L,
                                    displayedBytes = 128L,
                                ),
                            ),
                    )
                val reader = FakeRemovalPreviewReader(preview)
                val issuer = FakeRemovalAuthorizationIssuer()
                val releaser = FakeRemovalPreviewReleaser()
                val repository = FakeAuthRepository()
                val viewModel =
                    settingsViewModel(
                        logoutAction = LogoutAction(repository),
                        removalPreviewReader = reader,
                        authorizationIssuer = issuer,
                        previewReleaser = releaser,
                        workDispatcher = dispatcher,
                    )

                viewModel.logout()
                advanceUntilIdle()
                assertEquals(preview, viewModel.state.value.logoutRemovalPreview)

                viewModel.dismissLogoutRemoval()
                advanceUntilIdle()
                assertEquals(1, releaser.calls)
                assertEquals(preview, releaser.preview)

                val confirmingViewModel =
                    settingsViewModel(
                        logoutAction = LogoutAction(repository),
                        removalPreviewReader = reader,
                        authorizationIssuer = issuer,
                        workDispatcher = dispatcher,
                    )
                confirmingViewModel.logout()
                advanceUntilIdle()
                confirmingViewModel.confirmLogoutRemoval()
                advanceUntilIdle()
                assertEquals(preview, issuer.preview)
                assertEquals(1, repository.logoutCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun dismissalReleaseFailureEmitsTypedSafeDiagnosticAndDismisses() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val preview =
                DownloadRemovalPreview(
                    membershipRevision = 8L,
                    confirmations =
                        listOf(
                            DownloadRemovalConfirmation(
                                accountIdentity = AccountIdentity(session.serverId, session.userId),
                                membershipRevision = 8L,
                                recordCount = 1L,
                                displayedBytes = 128L,
                            ),
                        ),
                )
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.SettingsViewModel.wireValue) {
                            messages += message
                            assertEquals(null, throwable)
                        }
                    }
                }
            try {
                val releaser =
                    FakeRemovalPreviewReleaser(
                        failure = IllegalStateException("release identity message"),
                    )
                val viewModel =
                    settingsViewModel(
                        removalPreviewReader = FakeRemovalPreviewReader(preview),
                        authorizationIssuer = FakeRemovalAuthorizationIssuer(),
                        previewReleaser = releaser,
                        workDispatcher = dispatcher,
                    )
                Logger.setLogWriters(writer)
                viewModel.logout()
                advanceUntilIdle()
                viewModel.dismissLogoutRemoval()
                advanceUntilIdle()

                assertEquals(1, releaser.calls)
                assertEquals(null, viewModel.state.value.logoutRemovalPreview)
                val message = messages.single { value -> value.contains("operation=releaseDownloadRemovalPreview") }
                assertEquals(
                    "stage=removal-preview-release event=failed operation=releaseDownloadRemovalPreview exceptionType=IllegalStateException",
                    message,
                )
                assertEquals(message, LogScrubber.capture(DiagnosticTag.SettingsViewModel.wireValue, message))
                assertFalse(message.contains("release identity message"))
            } finally {
                Logger.setLogWriters(emptyList())
                Dispatchers.resetMain()
            }
        }

    @Test
    fun logoutDismissalReleaseSurvivesImmediateViewModelCancellation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val preview =
                    DownloadRemovalPreview(
                        membershipRevision = 8L,
                        confirmations =
                            listOf(
                                DownloadRemovalConfirmation(
                                    accountIdentity = AccountIdentity(session.serverId, session.userId),
                                    membershipRevision = 8L,
                                    recordCount = 1L,
                                    displayedBytes = 128L,
                                ),
                            ),
                    )
                val releaser = BlockingRemovalPreviewReleaser()
                val viewModel =
                    settingsViewModel(
                        removalPreviewReader = FakeRemovalPreviewReader(preview),
                        authorizationIssuer = FakeRemovalAuthorizationIssuer(),
                        previewReleaser = releaser,
                        workDispatcher = dispatcher,
                    )

                viewModel.logout()
                advanceUntilIdle()
                viewModel.dismissLogoutRemoval()
                viewModel.viewModelScope.cancel()
                runCurrent()
                assertTrue(releaser.started.isCompleted)

                releaser.finish.complete(Unit)
                advanceUntilIdle()

                assertEquals(1, releaser.calls)
                assertEquals(null, viewModel.state.value.logoutRemovalPreview)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun loadsAndSavesPlaybackPreferences() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store =
                    FakePlaybackPreferencesStore(
                        PlaybackPreferences(
                            defaultMaxBitrateBps = 12_000_000L,
                            vlcTranscodeMaxBitrateBps = 8_000_000L,
                            preferredAudioLanguage = "eng",
                            preferredSubtitleLanguage = "spa",
                            stillWatchingPrompt = false,
                        ),
                    )
                val viewModel =
                    settingsViewModel(
                        playbackPreferencesStore = store,
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()

                assertEquals(
                    PlaybackPreferences(
                        defaultQualityPolicy = PlaybackQualityPolicy.fixed(12_000_000L),
                        defaultMaxBitrateBps = 12_000_000L,
                        vlcTranscodeMaxBitrateBps = 8_000_000L,
                        preferredAudioLanguage = "eng",
                        preferredSubtitleLanguage = "spa",
                        stillWatchingPrompt = false,
                    ),
                    viewModel.state.value.playbackPreferences,
                )

                viewModel.setDefaultMaxBitrateBps(8_000_000L)
                viewModel.setVlcTranscodeMaxBitrateBps(20_000_000L)
                viewModel.setPreferredAudioLanguage(" jpn ")
                viewModel.setPreferredSubtitleLanguage("")
                viewModel.setStillWatchingPrompt(true)
                viewModel.setDefaultPlayerBackend(PlayerBackend.Mpv)
                advanceUntilIdle()

                assertEquals(
                    PlaybackPreferences(
                        defaultQualityPolicy = PlaybackQualityPolicy.fixed(8_000_000L),
                        defaultMaxBitrateBps = 8_000_000L,
                        vlcTranscodeMaxBitrateBps = 20_000_000L,
                        defaultPlayerBackend = PlayerBackend.Mpv,
                        preferredAudioLanguage = "jpn",
                        preferredSubtitleLanguage = null,
                        stillWatchingPrompt = true,
                    ),
                    store.savedPreferences.last(),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancelledOpenSubtitlesKeyLoadDoesNotPublishAPartialValue() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = GateDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitlesApiKeyUseCase =
                            GetOpenSubtitlesApiKeyUseCase(
                                OpenSubtitlesSettingsStore(BlockingSecureStore()),
                            ),
                        workDispatcher = workDispatcher,
                    )
                runCurrent()
                assertTrue(workDispatcher.pendingCount > 0)
                viewModel.setOpenSubtitlesApiKey("newer-key")

                viewModel.viewModelScope.cancel()
                runCurrent()

                assertEquals("", viewModel.state.value.openSubtitlesApiKey)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun openSubtitlesKeyUpdateWaitsForLoadAndDurableSave() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore =
                    PreferenceSecureStore(
                        initialPreference = null,
                        initialApiKey = "old-key",
                        blockReads = true,
                        blockWrites = true,
                    )
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitlesApiKeyUseCase = GetOpenSubtitlesApiKeyUseCase(settings),
                        setOpenSubtitlesApiKeyAction = SetOpenSubtitlesApiKeyAction(settings),
                        workDispatcher = dispatcher,
                    )
                runCurrent()
                secureStore.readStarted.await()

                viewModel.setOpenSubtitlesApiKey("new-key") {}
                runCurrent()
                assertFalse(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertEquals("", viewModel.state.value.openSubtitlesApiKey)
                assertFalse(viewModel.state.value.isSavingOpenSubtitlesApiKey)

                secureStore.allowRead.complete(Unit)
                advanceUntilIdle()
                assertTrue(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertEquals("old-key", viewModel.state.value.openSubtitlesApiKey)

                viewModel.setOpenSubtitlesApiKey("new-key") {}
                runCurrent()
                secureStore.writeStarted.await()
                assertTrue(viewModel.state.value.isSavingOpenSubtitlesApiKey)
                assertEquals("old-key", viewModel.state.value.openSubtitlesApiKey)

                secureStore.allowWrite.complete(Unit)
                advanceUntilIdle()
                assertEquals("new-key", viewModel.state.value.openSubtitlesApiKey)
                assertFalse(viewModel.state.value.isSavingOpenSubtitlesApiKey)
                assertFalse(viewModel.state.value.openSubtitlesApiKeyError)
                assertEquals("new-key", secureStore.storedApiKey())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOpenSubtitlesKeySaveKeepsConfirmedValue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore(initialPreference = null, initialApiKey = "old-key", failWrites = true)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitlesApiKeyUseCase = GetOpenSubtitlesApiKeyUseCase(settings),
                        setOpenSubtitlesApiKeyAction = SetOpenSubtitlesApiKeyAction(settings),
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()

                viewModel.setOpenSubtitlesApiKey("new-key") {}
                advanceUntilIdle()

                assertEquals("old-key", viewModel.state.value.openSubtitlesApiKey)
                assertTrue(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertFalse(viewModel.state.value.isSavingOpenSubtitlesApiKey)
                assertTrue(viewModel.state.value.openSubtitlesApiKeyError)
                assertEquals("old-key", secureStore.storedApiKey())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOpenSubtitlesKeyLoadStaysUnconfirmedUntilRetrySucceeds() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore(initialPreference = null, initialApiKey = "saved-key", apiKeyReadFailures = 1)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitlesApiKeyUseCase = GetOpenSubtitlesApiKeyUseCase(settings),
                        workDispatcher = dispatcher,
                    )

                advanceUntilIdle()

                assertEquals(1, secureStore.apiKeyReads)
                assertFalse(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertFalse(viewModel.state.value.isLoadingOpenSubtitlesApiKey)
                assertTrue(viewModel.state.value.openSubtitlesApiKeyLoadError)
                assertEquals("", viewModel.state.value.openSubtitlesApiKey)

                viewModel.retryOpenSubtitlesApiKeyLoad()
                assertFalse(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertTrue(viewModel.state.value.isLoadingOpenSubtitlesApiKey)
                assertFalse(viewModel.state.value.openSubtitlesApiKeyLoadError)
                advanceUntilIdle()

                assertEquals(2, secureStore.apiKeyReads)
                assertTrue(viewModel.state.value.isOpenSubtitlesApiKeyLoaded)
                assertFalse(viewModel.state.value.isLoadingOpenSubtitlesApiKey)
                assertFalse(viewModel.state.value.openSubtitlesApiKeyLoadError)
                assertEquals("saved-key", viewModel.state.value.openSubtitlesApiKey)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun savesDisplayRefreshRateMatchingSetting() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakePlayerDeviceSettingsStore()
                val viewModel = settingsViewModel(playerDeviceSettingsStore = store, workDispatcher = dispatcher)

                assertEquals(false, viewModel.state.value.playerDeviceSettings.matchDisplayRefreshRate)

                viewModel.setMatchDisplayRefreshRate(true)
                advanceUntilIdle()

                assertEquals(true, store.settings.value.matchDisplayRefreshRate)
                assertEquals(true, viewModel.state.value.playerDeviceSettings.matchDisplayRefreshRate)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun savesMaximumVideoResolutionSetting() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakePlayerDeviceSettingsStore()
                val viewModel = settingsViewModel(playerDeviceSettingsStore = store, workDispatcher = dispatcher)

                viewModel.setMaxVideoResolution(PlayerVideoResolutionLimit.Height1080)
                advanceUntilIdle()

                assertEquals(PlayerVideoResolutionLimit.Height1080, store.settings.value.maxVideoResolution)
                assertEquals(
                    PlayerVideoResolutionLimit.Height1080,
                    viewModel.state.value.playerDeviceSettings.maxVideoResolution,
                )

                viewModel.setIosPlaybackCompatibilityMode(IosPlaybackCompatibilityMode.Unrestricted)
                advanceUntilIdle()

                assertEquals(
                    IosPlaybackCompatibilityMode.Unrestricted,
                    store.settings.value.iosPlaybackCompatibilityMode,
                )
                assertEquals(
                    IosPlaybackCompatibilityMode.Unrestricted,
                    viewModel.state.value.playerDeviceSettings.iosPlaybackCompatibilityMode,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun refreshCapabilitiesGuardsDuplicateRequestsAndPublishesProgress() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val provider = FakeDeviceProfileProvider()
                val store = FakePlayerDeviceSettingsStore()
                val viewModel =
                    settingsViewModel(
                        playerDeviceSettingsStore = store,
                        refreshPlayerDevicePolicyUseCase = RefreshPlayerDevicePolicyUseCase(provider, store),
                        workDispatcher = dispatcher,
                    )

                viewModel.refreshPlayerDevicePolicy()
                viewModel.refreshPlayerDevicePolicy()

                assertTrue(viewModel.state.value.isRefreshingPlayerDevicePolicy)
                assertFalse(viewModel.state.value.playerDevicePolicyError)
                advanceUntilIdle()
                assertEquals(1, provider.refreshCalls)
                assertFalse(viewModel.state.value.isRefreshingPlayerDevicePolicy)
                assertFalse(viewModel.state.value.playerDevicePolicyError)
                assertEquals(1, viewModel.state.value.playerDevicePolicyRefreshCompletion)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun refreshCapabilitiesRetainsAnActionableFailureState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val provider = FakeDeviceProfileProvider(refreshFailure = IllegalStateException("refresh failed"))
                val store = FakePlayerDeviceSettingsStore()
                val viewModel =
                    settingsViewModel(
                        playerDeviceSettingsStore = store,
                        refreshPlayerDevicePolicyUseCase = RefreshPlayerDevicePolicyUseCase(provider, store),
                        workDispatcher = dispatcher,
                    )

                viewModel.refreshPlayerDevicePolicy()
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isRefreshingPlayerDevicePolicy)
                assertTrue(viewModel.state.value.playerDevicePolicyError)
                assertEquals(1, viewModel.state.value.playerDevicePolicyRefreshCompletion)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun devicePolicyObservationUsesLatestSettingsBeforeFirstProbe() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = GateDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val provider = FakeDeviceProfileProvider()
                val store = FakePlayerDeviceSettingsStore()
                val viewModel =
                    settingsViewModel(
                        playerDeviceSettingsStore = store,
                        getPlayerDevicePolicyUseCase = GetPlayerDevicePolicyUseCase(provider, store),
                        workDispatcher = workDispatcher,
                    )
                val initialSettings = PlayerDeviceSettings(audioMode = PlayerAudioMode.PassthroughWhenSupported)
                val latestSettings = PlayerDeviceSettings(audioMode = PlayerAudioMode.StereoPcm)
                val policyPublications = mutableListOf<PlayerDeviceSettings>()
                backgroundScope.launch(mainDispatcher) {
                    viewModel.state.collect { state ->
                        state.playerDevicePolicy?.let { policy -> policyPublications += policy.settings }
                    }
                }

                assertEquals(0, provider.capabilitiesCalls)
                runCurrent()
                assertTrue(workDispatcher.pendingCount > 0)
                workDispatcher.holdNext()
                assertEquals(0, provider.capabilitiesCalls)

                store.setSettings(initialSettings)
                store.setSettings(latestSettings)
                runCurrent()
                workDispatcher.releaseHeld()
                runCurrent()
                while (workDispatcher.pendingCount > 0) {
                    workDispatcher.runNext()
                    runCurrent()
                }

                assertEquals(1, provider.capabilitiesCalls)
                assertEquals(listOf(latestSettings), policyPublications)
                assertEquals(latestSettings, viewModel.state.value.playerDeviceSettings)
                assertEquals(
                    latestSettings,
                    viewModel.state.value.playerDevicePolicy
                        ?.settings,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun playbackPreferencesReadOnWorkDispatcherAndPublishOnMain() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = GateDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val store = FakePlaybackPreferencesStore(PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc))
                val viewModel =
                    settingsViewModel(
                        playbackPreferencesStore = store,
                        workDispatcher = workDispatcher,
                    )

                runCurrent()
                assertTrue(viewModel.state.value.isLoadingPlaybackPreferences)
                assertTrue(workDispatcher.pendingCount > 0)

                while (workDispatcher.pendingCount > 0) {
                    workDispatcher.runNext()
                    runCurrent()
                }

                assertFalse(viewModel.state.value.isLoadingPlaybackPreferences)
                assertFalse(viewModel.state.value.playbackPreferencesError)
                assertEquals(PlayerBackend.LibVlc, viewModel.state.value.playbackPreferences.defaultPlayerBackend)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun clearLocalSubtitlesGuardsDuplicateRequestsAndClearsProgressOnSuccess() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val assetStore = FakeLocalSubtitleAssetStore()
                val coordinator =
                    LocalSubtitleMutationCoordinator(
                        assetStore = assetStore,
                        fileStore = FakeLocalSubtitleFileStore(),
                        selectionStore = FakeSubtitleSelectionStore(),
                        scope = backgroundScope,
                    )
                val viewModel =
                    settingsViewModel(
                        clearLocalSubtitlesAction = ClearLocalSubtitlesAction(coordinator),
                        workDispatcher = dispatcher,
                    )

                viewModel.clearLocalSubtitles()
                viewModel.clearLocalSubtitles()

                assertTrue(viewModel.state.value.isClearingLocalSubtitles)
                assertFalse(viewModel.state.value.localSubtitlesClearError)
                advanceUntilIdle()
                assertEquals(1, assetStore.allCalls)
                assertFalse(viewModel.state.value.isClearingLocalSubtitles)
                assertFalse(viewModel.state.value.localSubtitlesClearError)
                assertEquals(1, viewModel.state.value.localSubtitlesClearCompletion)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun clearLocalSubtitlesRetainsAnActionableFailureState() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = GateDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val coordinator =
                    LocalSubtitleMutationCoordinator(
                        assetStore = FakeLocalSubtitleAssetStore(allFailure = IllegalStateException("clear failed")),
                        fileStore = FakeLocalSubtitleFileStore(),
                        selectionStore = FakeSubtitleSelectionStore(),
                        scope = backgroundScope,
                    )
                val viewModel =
                    settingsViewModel(
                        clearLocalSubtitlesAction = ClearLocalSubtitlesAction(coordinator),
                        workDispatcher = workDispatcher,
                    )

                viewModel.clearLocalSubtitles()
                runCurrent()
                assertTrue(viewModel.state.value.isClearingLocalSubtitles)
                assertTrue(workDispatcher.pendingCount > 0)
                while (workDispatcher.pendingCount > 0) {
                    workDispatcher.runNext()
                    runCurrent()
                }

                assertFalse(viewModel.state.value.isClearingLocalSubtitles)
                assertTrue(viewModel.state.value.localSubtitlesClearError)
                assertEquals(1, viewModel.state.value.localSubtitlesClearCompletion)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun updatesAppTheme() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeAppThemeStore()
                val viewModel =
                    settingsViewModel(
                        appThemeStore = store,
                        workDispatcher = dispatcher,
                    )

                viewModel.setAppTheme(AppColorThemeId.Midnight)
                advanceUntilIdle()

                assertEquals(AppColorThemeId.Midnight, store.theme.value)
                assertEquals(AppColorThemeId.Midnight, viewModel.state.value.appTheme)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun updatesPictureInPicturePreference() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakePictureInPictureStore()
                val viewModel =
                    settingsViewModel(
                        pictureInPictureStore = store,
                        workDispatcher = dispatcher,
                    )

                assertEquals(true, viewModel.state.value.pictureInPictureEnabled)

                viewModel.setPictureInPictureEnabled(false)
                advanceUntilIdle()

                assertEquals(false, store.enabled.value)
                assertEquals(false, viewModel.state.value.pictureInPictureEnabled)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun updatesTileSizePreference() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeTileSizeStore()
                val viewModel =
                    settingsViewModel(
                        tileSizeStore = store,
                        workDispatcher = dispatcher,
                    )

                assertEquals(TileSizeId.Medium, viewModel.state.value.tileSize)

                viewModel.setTileSize(TileSizeId.Large)
                advanceUntilIdle()

                assertEquals(TileSizeId.Large, store.tileSize.value)
                assertEquals(TileSizeId.Large, viewModel.state.value.tileSize)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun setSegmentSkipPolicyPersistsPerTypeThroughTheSerializedWriter() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakePlaybackPreferencesStore(PlaybackPreferences())
                val viewModel =
                    settingsViewModel(
                        playbackPreferencesStore = store,
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()

                viewModel.setSegmentSkipPolicy(MediaSegmentType.Intro, SegmentSkipPolicy.AutoSkip)
                viewModel.setSegmentSkipPolicy(MediaSegmentType.Commercial, SegmentSkipPolicy.Ignore)
                advanceUntilIdle()

                val saved = store.savedPreferences.last()
                assertEquals(SegmentSkipPolicy.AutoSkip, saved.introSkip)
                assertEquals(SegmentSkipPolicy.Ignore, saved.commercialSkip)
                assertEquals(SegmentSkipPolicy.Ask, saved.outroSkip)
                assertEquals(
                    SegmentSkipPolicy.AutoSkip,
                    viewModel.state.value.playbackPreferences.introSkip,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun latestValueWriterSerializesWritesAndKeepsTheNewestValue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val persisted = mutableListOf<Int>()
            val writer =
                LatestValueWriter<Int>(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    write = { value ->
                        if (value == 1) {
                            firstWriteStarted.complete(Unit)
                            releaseFirstWrite.await()
                        }
                        persisted += value
                    },
                )

            writer.submit(1)
            runCurrent()
            firstWriteStarted.await()
            writer.submit(2)
            writer.submit(3)
            releaseFirstWrite.complete(Unit)
            runCurrent()

            assertEquals(listOf(1, 3), persisted)
        }

    @Test
    fun loadsPersistedOpenSubtitleResultPreferenceWithUnknownFallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                suspend fun loaded(rawValue: String?): SettingsUiState {
                    val settings = OpenSubtitlesSettingsStore(PreferenceSecureStore(rawValue))
                    val viewModel =
                        settingsViewModel(
                            getOpenSubtitleResultPreferenceUseCase = GetOpenSubtitleResultPreferenceUseCase(settings),
                            setOpenSubtitleResultPreferenceAction = SetOpenSubtitleResultPreferenceAction(settings),
                            workDispatcher = dispatcher,
                        )
                    advanceUntilIdle()
                    return viewModel.state.value
                }

                val storedPreference = loaded("PreferForced")
                assertTrue(storedPreference.isOpenSubtitleResultPreferenceLoaded)
                assertFalse(storedPreference.isLoadingOpenSubtitleResultPreference)
                assertFalse(storedPreference.openSubtitleResultPreferenceLoadError)
                assertEquals(OpenSubtitleResultPreference.PreferForced, storedPreference.openSubtitleResultPreference)

                val unknownPreference = loaded("FuturePreference")
                assertTrue(unknownPreference.isOpenSubtitleResultPreferenceLoaded)
                assertFalse(unknownPreference.openSubtitleResultPreferenceLoadError)
                assertEquals(OpenSubtitleResultPreference.NoPreference, unknownPreference.openSubtitleResultPreference)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun openSubtitlePreferenceSelectionWaitsForConfirmedInitialState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore("PreferForced", blockReads = true)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitleResultPreferenceUseCase = GetOpenSubtitleResultPreferenceUseCase(settings),
                        setOpenSubtitleResultPreferenceAction = SetOpenSubtitleResultPreferenceAction(settings),
                        workDispatcher = dispatcher,
                    )
                var preloadSuccessCallbacks = 0

                runCurrent()
                secureStore.readStarted.await()
                assertFalse(viewModel.state.value.isOpenSubtitleResultPreferenceLoaded)
                assertTrue(viewModel.state.value.isLoadingOpenSubtitleResultPreference)

                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.NoPreference) {
                    preloadSuccessCallbacks += 1
                }
                runCurrent()

                assertEquals(0, preloadSuccessCallbacks)
                assertEquals(0, secureStore.preferenceRemovals)
                assertEquals("PreferForced", secureStore.storedPreference())

                secureStore.allowRead.complete(Unit)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.isOpenSubtitleResultPreferenceLoaded)
                assertEquals(OpenSubtitleResultPreference.PreferForced, viewModel.state.value.openSubtitleResultPreference)
                assertEquals(OpenSubtitleResultPreference.PreferForced, settings.resultPreference())
                assertEquals(0, preloadSuccessCallbacks)

                var confirmedSameValueCallbacks = 0
                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.PreferForced) {
                    confirmedSameValueCallbacks += 1
                }
                assertEquals(1, confirmedSameValueCallbacks)
                assertEquals(0, secureStore.preferenceRemovals)

                var persistenceSuccessCallbacks = 0
                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.NoPreference) {
                    persistenceSuccessCallbacks += 1
                }
                advanceUntilIdle()

                assertEquals(1, persistenceSuccessCallbacks)
                assertEquals(1, secureStore.preferenceRemovals)
                assertEquals(null, secureStore.storedPreference())
                assertEquals(OpenSubtitleResultPreference.NoPreference, viewModel.state.value.openSubtitleResultPreference)
                val searchVisiblePreference = settings.resultPreference()
                assertEquals(OpenSubtitleResultPreference.NoPreference, searchVisiblePreference)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOpenSubtitlePreferenceLoadStaysUnconfirmedUntilRetrySucceeds() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore("PreferForced", failReadAttempts = 1)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitleResultPreferenceUseCase = GetOpenSubtitleResultPreferenceUseCase(settings),
                        setOpenSubtitleResultPreferenceAction = SetOpenSubtitleResultPreferenceAction(settings),
                        workDispatcher = dispatcher,
                    )
                var prematureSuccessCallbacks = 0

                advanceUntilIdle()

                assertEquals(1, secureStore.preferenceReads)
                assertFalse(viewModel.state.value.isOpenSubtitleResultPreferenceLoaded)
                assertFalse(viewModel.state.value.isLoadingOpenSubtitleResultPreference)
                assertTrue(viewModel.state.value.openSubtitleResultPreferenceLoadError)
                assertEquals(OpenSubtitleResultPreference.NoPreference, viewModel.state.value.openSubtitleResultPreference)

                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.NoPreference) {
                    prematureSuccessCallbacks += 1
                }
                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.PreferForced) {
                    prematureSuccessCallbacks += 10
                }
                runCurrent()

                assertEquals(0, prematureSuccessCallbacks)
                assertEquals(0, secureStore.preferenceRemovals)
                assertFalse(secureStore.writeStarted.isCompleted)
                assertEquals("PreferForced", secureStore.storedPreference())

                viewModel.retryOpenSubtitleResultPreferenceLoad()
                assertFalse(viewModel.state.value.isOpenSubtitleResultPreferenceLoaded)
                assertTrue(viewModel.state.value.isLoadingOpenSubtitleResultPreference)
                assertFalse(viewModel.state.value.openSubtitleResultPreferenceLoadError)
                advanceUntilIdle()

                assertEquals(2, secureStore.preferenceReads)
                assertTrue(viewModel.state.value.isOpenSubtitleResultPreferenceLoaded)
                assertFalse(viewModel.state.value.isLoadingOpenSubtitleResultPreference)
                assertFalse(viewModel.state.value.openSubtitleResultPreferenceLoadError)
                assertEquals(OpenSubtitleResultPreference.PreferForced, viewModel.state.value.openSubtitleResultPreference)
                assertEquals(OpenSubtitleResultPreference.PreferForced, settings.resultPreference())
                assertEquals(0, prematureSuccessCallbacks)

                var persistenceSuccessCallbacks = 0
                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.NoPreference) {
                    persistenceSuccessCallbacks += 1
                }
                advanceUntilIdle()

                assertEquals(1, persistenceSuccessCallbacks)
                assertEquals(1, secureStore.preferenceRemovals)
                assertEquals(null, secureStore.storedPreference())
                assertEquals(OpenSubtitleResultPreference.NoPreference, viewModel.state.value.openSubtitleResultPreference)
                val searchVisiblePreference = settings.resultPreference()
                assertEquals(OpenSubtitleResultPreference.NoPreference, searchVisiblePreference)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun openSubtitlePreferenceSaveKeepsConfirmedValueSuppressesSecondInputAndDismissesOnSuccessOnly() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore("PreferHearingImpaired", blockWrites = true)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitleResultPreferenceUseCase = GetOpenSubtitleResultPreferenceUseCase(settings),
                        setOpenSubtitleResultPreferenceAction = SetOpenSubtitleResultPreferenceAction(settings),
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()
                var successCallbacks = 0

                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.PreferForced) { successCallbacks += 1 }
                runCurrent()
                secureStore.writeStarted.await()
                assertEquals(OpenSubtitleResultPreference.PreferHearingImpaired, viewModel.state.value.openSubtitleResultPreference)
                assertEquals(true, viewModel.state.value.isSavingOpenSubtitleResultPreference)
                assertEquals(0, successCallbacks)

                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.NoPreference) { successCallbacks += 10 }
                secureStore.allowWrite.complete(Unit)
                advanceUntilIdle()

                assertEquals(OpenSubtitleResultPreference.PreferForced, viewModel.state.value.openSubtitleResultPreference)
                assertEquals(false, viewModel.state.value.isSavingOpenSubtitleResultPreference)
                assertEquals(false, viewModel.state.value.openSubtitleResultPreferenceError)
                assertEquals(1, successCallbacks)
                assertEquals(OpenSubtitleResultPreference.PreferForced, settings.resultPreference())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOpenSubtitlePreferenceSaveRollsBackAndDoesNotDismiss() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val secureStore = PreferenceSecureStore("PreferForced", failWrites = true)
                val settings = OpenSubtitlesSettingsStore(secureStore)
                val viewModel =
                    settingsViewModel(
                        getOpenSubtitleResultPreferenceUseCase = GetOpenSubtitleResultPreferenceUseCase(settings),
                        setOpenSubtitleResultPreferenceAction = SetOpenSubtitleResultPreferenceAction(settings),
                        workDispatcher = dispatcher,
                    )
                advanceUntilIdle()
                var dismissed = false

                viewModel.setOpenSubtitleResultPreference(OpenSubtitleResultPreference.PreferHearingImpaired) { dismissed = true }
                advanceUntilIdle()

                assertEquals(OpenSubtitleResultPreference.PreferForced, viewModel.state.value.openSubtitleResultPreference)
                assertEquals(false, viewModel.state.value.isSavingOpenSubtitleResultPreference)
                assertEquals(true, viewModel.state.value.openSubtitleResultPreferenceError)
                assertEquals(false, dismissed)
                assertEquals(OpenSubtitleResultPreference.PreferForced, settings.resultPreference())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun latestValueWriterContinuesAfterAFailedWrite() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failures = mutableListOf<Throwable>()
            val persisted = mutableListOf<Int>()
            val writer =
                LatestValueWriter<Int>(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    write = { value ->
                        if (value == 1) {
                            error("first write failed")
                        }
                        persisted += value
                    },
                    onFailure = failures::add,
                )

            writer.submit(1)
            runCurrent()
            writer.submit(2)
            runCurrent()

            assertEquals(1, failures.size)
            assertEquals(listOf(2), persisted)
        }
}

private class FakeAuthRepository(
    private val logoutResult: Result<Unit> = Result.success(Unit),
) : AuthRepository {
    var logoutCalls = 0

    override suspend fun validateServer(input: String): Result<ServerInfo> = Result.failure(UnsupportedOperationException())

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> = Result.failure(UnsupportedOperationException())

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> {
        logoutCalls += 1
        return logoutResult
    }
}

private class FakeRemovalPreviewReader(
    private val value: DownloadRemovalPreview,
) : DownloadRemovalPreviewReader {
    override suspend fun invoke(scope: SessionRemovalScope): DownloadRemovalPreview = value
}

private class FakeRemovalAuthorizationIssuer : DownloadRemovalAuthorizationIssuer {
    var preview: DownloadRemovalPreview? = null

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): SessionRemovalAuthorization {
        this.preview = preview
        return SessionRemovalAuthorization.None
    }
}

private class FakeRemovalPreviewReleaser(
    private val failure: Throwable? = null,
) : DownloadRemovalPreviewReleaser {
    var calls = 0
    var preview: DownloadRemovalPreview? = null

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean {
        calls += 1
        this.preview = preview
        failure?.let { error -> throw error }
        return true
    }
}

private class BlockingRemovalPreviewReleaser : DownloadRemovalPreviewReleaser {
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    var calls = 0

    override suspend fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean {
        calls += 1
        started.complete(Unit)
        finish.await()
        return true
    }
}

private class FakeAppThemeStore(
    initialTheme: AppColorThemeId = AppColorThemeId.Ocean,
) : AppThemeStore {
    private val themeFlow = MutableStateFlow(initialTheme)

    override val theme: StateFlow<AppColorThemeId> = themeFlow.asStateFlow()

    override suspend fun setTheme(theme: AppColorThemeId) {
        themeFlow.value = theme
    }
}

private class FakePictureInPictureStore(
    initialEnabled: Boolean = true,
) : PictureInPictureStore {
    private val enabledFlow = MutableStateFlow(initialEnabled)

    override val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        enabledFlow.value = enabled
    }
}

private class FakeTileSizeStore(
    initialTileSize: TileSizeId = TileSizeId.Medium,
) : TileSizeStore {
    private val tileSizeFlow = MutableStateFlow(initialTileSize)

    override val tileSize: StateFlow<TileSizeId> = tileSizeFlow.asStateFlow()

    override suspend fun setTileSize(tileSize: TileSizeId) {
        tileSizeFlow.value = tileSize
    }
}

private class FakeLibraryViewPreferencesStore : LibraryViewPreferencesStore {
    private val rememberFlow = MutableStateFlow(true)
    override val rememberLastView: StateFlow<Boolean> = rememberFlow.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = null

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) = Unit

    override fun savedView(libraryKey: String): LibraryInnerView? = null

    override suspend fun setRememberLastView(enabled: Boolean) {
        rememberFlow.value = enabled
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) = Unit
}

private class FakePlaybackPreferencesStore(
    initialPreferences: PlaybackPreferences,
) : PlaybackPreferencesStore {
    val savedPreferences = mutableListOf<PlaybackPreferences>()
    private var preferences = initialPreferences

    override suspend fun get(accountIdentity: AccountIdentity): PlaybackPreferences = preferences

    override suspend fun save(
        accountIdentity: AccountIdentity,
        preferences: PlaybackPreferences,
    ) {
        this.preferences = preferences
        savedPreferences += preferences
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        preferences = PlaybackPreferences()
    }

    override suspend fun clearServerScoped(serverId: String) {
        preferences = PlaybackPreferences()
    }

    override suspend fun clearServerScoped() {
        preferences = PlaybackPreferences()
    }
}

private class FakePlayerDeviceSettingsStore(
    initialSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
) : PlayerDeviceSettingsStore {
    private val settingsFlow = MutableStateFlow(initialSettings)

    override val settings: StateFlow<PlayerDeviceSettings> = settingsFlow.asStateFlow()

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        settingsFlow.value = settings
    }
}

private class FakeDeviceProfileProvider(
    private val refreshFailure: Throwable? = null,
    private val capabilitiesFailure: Throwable? = null,
) : DeviceProfileProvider {
    var refreshCalls = 0
    var capabilitiesCalls = 0

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
        capabilitiesCalls += 1
        capabilitiesFailure?.let { failure -> throw failure }
        return deviceCapabilities
    }

    override fun refreshCapabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
        refreshCalls += 1
        refreshFailure?.let { failure -> throw failure }
        return deviceCapabilities
    }
}

private class GateDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    private var held: Runnable? = null

    val pendingCount: Int get() = pending.size

    override fun dispatch(
        context: kotlin.coroutines.CoroutineContext,
        block: Runnable,
    ) {
        pending.addLast(block)
    }

    fun holdNext() {
        check(held == null)
        held = pending.removeFirst()
    }

    fun releaseHeld() {
        val block = held ?: error("No work block is held")
        held = null
        block.run()
    }

    fun runNext() {
        pending.removeFirst().run()
    }
}

private class FakeLocalSubtitleAssetStore(
    private val allFailure: Throwable? = null,
) : LocalSubtitleAssetStore {
    var allCalls = 0

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> = flowOf(emptyList())

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = flowOf(emptyList())

    override suspend fun get(assetId: String): LocalSubtitleAsset? = null

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? = null

    override suspend fun upsert(asset: LocalSubtitleAsset) = Unit

    override suspend fun delete(assetId: String) = Unit

    override suspend fun all(): List<LocalSubtitleAsset> {
        allCalls += 1
        allFailure?.let { failure -> throw failure }
        return emptyList()
    }

    override suspend fun clearAll() = Unit
}

private class FakeLocalSubtitleFileStore : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = false

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = emptySet()

    override fun resolvePath(fileId: String): String? = null
}

private class FakeSubtitleSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit
}

private class SettingsDownloadRepository : DownloadRepository {
    var usage =
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
    val usageGates = ArrayDeque<CompletableDeferred<DownloadUsage>>()
    var usageReads = 0
    var usageAccount: AccountIdentity? = null

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = flowOf(emptyList())

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? = null

    override suspend fun getDownloadSettings(): DownloadSettings = settings

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage {
        usageReads += 1
        usageAccount = accountIdentity
        return usageGates.removeFirstOrNull()?.await() ?: usage
    }

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings = settings

    override suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult = DownloadEnqueueResult.RemovalInProgress

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.Deleted

    override suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.Deleted

    override suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
    ): Boolean = false
}

private fun settingsViewModel(
    logoutAction: LogoutAction = LogoutAction(FakeAuthRepository()),
    removalPreviewReader: DownloadRemovalPreviewReader? = null,
    authorizationIssuer: DownloadRemovalAuthorizationIssuer? = null,
    previewReleaser: DownloadRemovalPreviewReleaser? = null,
    appInfoVersion: String = "dev",
    appInfoSourceRevision: String = "test-revision",
    appThemeStore: AppThemeStore = FakeAppThemeStore(),
    tileSizeStore: TileSizeStore = FakeTileSizeStore(),
    pictureInPictureStore: PictureInPictureStore = FakePictureInPictureStore(),
    playbackPreferencesStore: PlaybackPreferencesStore? = null,
    playerBackendPolicy: PlayerBackendPolicy = androidPlayerBackendPolicy(),
    getAvailablePlayerBackendsUseCase: GetAvailablePlayerBackendsUseCase? = null,
    playerDeviceSettingsStore: PlayerDeviceSettingsStore? = null,
    getPlayerDevicePolicyUseCase: GetPlayerDevicePolicyUseCase? = null,
    refreshPlayerDevicePolicyUseCase: RefreshPlayerDevicePolicyUseCase? = null,
    getOpenSubtitlesApiKeyUseCase: GetOpenSubtitlesApiKeyUseCase? = null,
    setOpenSubtitlesApiKeyAction: SetOpenSubtitlesApiKeyAction? = null,
    getOpenSubtitleResultPreferenceUseCase: GetOpenSubtitleResultPreferenceUseCase? = null,
    setOpenSubtitleResultPreferenceAction: SetOpenSubtitleResultPreferenceAction? = null,
    clearLocalSubtitlesAction: ClearLocalSubtitlesAction? = null,
    getDownloadUsageUseCase: GetDownloadUsageUseCase? = null,
    getDownloadSettingsUseCase: GetDownloadSettingsUseCase? = null,
    logCollectionDependencies: LogCollectionTestDependencies? = null,
    workDispatcher: kotlinx.coroutines.CoroutineDispatcher,
): SettingsViewModel {
    val libraryViewStore = FakeLibraryViewPreferencesStore()
    val resolvedPlaybackPreferencesStore = playbackPreferencesStore ?: FakePlaybackPreferencesStore(PlaybackPreferences())
    val resolvedPlayerDeviceSettingsStore = playerDeviceSettingsStore ?: FakePlayerDeviceSettingsStore()
    return SettingsViewModel(
        session = session,
        appInfo = AppInfo(versionName = appInfoVersion, sourceRevision = appInfoSourceRevision),
        playerBackendPolicy = playerBackendPolicy,
        logoutAction = logoutAction,
        getDownloadRemovalPreviewUseCase = removalPreviewReader,
        issueDownloadRemovalAuthorizationUseCase = authorizationIssuer,
        releaseDownloadRemovalPreviewUseCase = previewReleaser,
        getDownloadUsageUseCase = getDownloadUsageUseCase,
        getDownloadSettingsUseCase = getDownloadSettingsUseCase,
        observeAppThemeUseCase = ObserveAppThemeUseCase(appThemeStore),
        setAppThemeAction = SetAppThemeAction(appThemeStore),
        observeTileSizeUseCase = ObserveTileSizeUseCase(tileSizeStore),
        setTileSizeAction = SetTileSizeAction(tileSizeStore),
        observePictureInPictureEnabledUseCase = ObservePictureInPictureEnabledUseCase(pictureInPictureStore),
        setPictureInPictureEnabledAction = SetPictureInPictureEnabledAction(pictureInPictureStore),
        observeRememberLastLibraryViewUseCase = ObserveRememberLastLibraryViewUseCase(libraryViewStore),
        setRememberLastLibraryViewAction = SetRememberLastLibraryViewAction(libraryViewStore),
        observePlayerDeviceSettingsUseCase = ObservePlayerDeviceSettingsUseCase(resolvedPlayerDeviceSettingsStore),
        savePlayerDeviceSettingsAction = SavePlayerDeviceSettingsAction(resolvedPlayerDeviceSettingsStore),
        getPlayerDevicePolicyUseCase =
            getPlayerDevicePolicyUseCase
                ?: GetPlayerDevicePolicyUseCase(AndroidSettingsDeviceProfileProvider, resolvedPlayerDeviceSettingsStore),
        refreshPlayerDevicePolicyUseCase =
            refreshPlayerDevicePolicyUseCase
                ?: RefreshPlayerDevicePolicyUseCase(
                    AndroidSettingsDeviceProfileProvider,
                    resolvedPlayerDeviceSettingsStore,
                ),
        getOpenSubtitlesApiKeyUseCase = getOpenSubtitlesApiKeyUseCase,
        setOpenSubtitlesApiKeyAction = setOpenSubtitlesApiKeyAction,
        getOpenSubtitleResultPreferenceUseCase = getOpenSubtitleResultPreferenceUseCase,
        setOpenSubtitleResultPreferenceAction = setOpenSubtitleResultPreferenceAction,
        getPlaybackPreferencesUseCase = GetPlaybackPreferencesUseCase(resolvedPlaybackPreferencesStore),
        savePlaybackPreferencesAction = SavePlaybackPreferencesAction(resolvedPlaybackPreferencesStore),
        getAvailablePlayerBackendsUseCase =
            getAvailablePlayerBackendsUseCase
                ?: GetAvailablePlayerBackendsUseCase(AndroidSettingsDeviceProfileProvider),
        clearLocalSubtitlesAction = clearLocalSubtitlesAction,
        getLogCollectionStateUseCase = logCollectionDependencies?.getState,
        setLogCollectionEnabledAction = logCollectionDependencies?.setEnabled,
        getVerboseLogcatStateUseCase = logCollectionDependencies?.getVerboseState,
        setVerboseLogcatEnabledAction = logCollectionDependencies?.setVerboseEnabled,
        getPlaybackInfoAtStartStateUseCase = logCollectionDependencies?.getPlaybackInfoAtStartState,
        setPlaybackInfoAtStartEnabledAction = logCollectionDependencies?.setPlaybackInfoAtStartEnabled,
        sendClientLogsAction = logCollectionDependencies?.sendLogs,
        workDispatcher = workDispatcher,
    )
}

private class BlockingSecureStore : SecureStore {
    override suspend fun read(key: String): String? = awaitCancellation()

    override suspend fun write(
        key: String,
        value: String,
    ) = Unit

    override suspend fun remove(key: String) = Unit

    override suspend fun clear() = Unit
}

private class PreferenceSecureStore(
    initialPreference: String?,
    initialApiKey: String? = null,
    failReadAttempts: Int = 0,
    apiKeyReadFailures: Int = 0,
    private val blockReads: Boolean = false,
    private val blockWrites: Boolean = false,
    private val failWrites: Boolean = false,
) : SecureStore {
    private var remainingReadFailures = failReadAttempts
    private var remainingApiKeyReadFailures = apiKeyReadFailures
    val readStarted = CompletableDeferred<Unit>()
    val allowRead = CompletableDeferred<Unit>()
    val writeStarted = CompletableDeferred<Unit>()
    val allowWrite = CompletableDeferred<Unit>()
    var preferenceRemovals = 0
        private set
    var preferenceReads = 0
        private set
    var apiKeyReads = 0
        private set
    private val values = mutableMapOf<String, String>()

    init {
        initialPreference?.let { values[PREFERENCE_KEY] = it }
        initialApiKey?.let { values[API_KEY] = it }
    }

    override suspend fun read(key: String): String? {
        if (key == PREFERENCE_KEY) {
            preferenceReads += 1
            if (remainingReadFailures > 0) {
                remainingReadFailures -= 1
                error("preference read failed")
            }
        }
        if (key == API_KEY) {
            apiKeyReads += 1
            if (remainingApiKeyReadFailures > 0) {
                remainingApiKeyReadFailures -= 1
                error("api key read failed")
            }
        }
        if (key == PREFERENCE_KEY || key == API_KEY) {
            if (blockReads) {
                readStarted.complete(Unit)
                allowRead.await()
            }
        }
        return values[key]
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        if (key == PREFERENCE_KEY || key == API_KEY) {
            writeStarted.complete(Unit)
            if (blockWrites) allowWrite.await()
            if (failWrites) error("preference write failed")
        }
        values[key] = value
    }

    override suspend fun remove(key: String) {
        if (key == PREFERENCE_KEY) preferenceRemovals += 1
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }

    fun storedPreference(): String? = values[PREFERENCE_KEY]

    fun storedApiKey(): String? = values[API_KEY]
}

private const val PREFERENCE_KEY = "opensubtitles_result_preference"
private const val API_KEY = "opensubtitles_api_key"

private data class LogCollectionTestDependencies(
    val getState: GetLogCollectionStateUseCase,
    val setEnabled: SetLogCollectionEnabledAction,
    val getVerboseState: GetVerboseLogcatStateUseCase,
    val setVerboseEnabled: SetVerboseLogcatEnabledAction,
    val getPlaybackInfoAtStartState: GetPlaybackInfoAtStartStateUseCase? = null,
    val setPlaybackInfoAtStartEnabled: SetPlaybackInfoAtStartEnabledAction? = null,
    val sendLogs: SendClientLogsAction,
)

private fun settingsSendLogsAction(
    buffer: LogBufferStore,
    repository: MediaRepository,
    provider: DeviceProfileProvider =
        object : DeviceProfileProvider {
            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = deviceCapabilities
        },
): SendClientLogsAction =
    SendClientLogsAction(
        logBufferStore = buffer,
        mediaRepository = repository,
        diagnosticsEnvironment =
            object : DiagnosticsEnvironment {
                override val platform = "test"
                override val osVersion = "1"
                override val appVersion = "dev"
                override val deviceModel = "test-device"
            },
        deviceProfileProvider = provider,
        playbackDiagnosticsContext = PlaybackDiagnosticsContext(),
    )

private object AndroidSettingsDeviceProfileProvider : DeviceProfileProvider {
    override val backendPolicy = androidPlayerBackendPolicy()

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = deviceCapabilities
}

private class FakeLogCollectionPreferenceStore(
    enabled: Boolean = false,
    var failEnabledWrites: Boolean = false,
) : LogCollectionPreferenceStore {
    override val enabled = MutableStateFlow(enabled)
    override val verboseLogcatEnabled = MutableStateFlow(false)

    override suspend fun setEnabled(enabled: Boolean) {
        check(!failEnabledWrites) { "simulated preference write failure" }
        this.enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        verboseLogcatEnabled.value = enabled
    }

    override val playbackInfoAtStartEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        playbackInfoAtStartEnabled.value = enabled
    }
}

private class SettingsClientLogRepository : MediaRepository {
    val uploads = mutableListOf<String>()

    override suspend fun uploadClientLogs(content: String): SendClientLogsResult {
        uploads += content
        return SendClientLogsResult.Success("client-logs.txt")
    }

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = Result.failure(UnsupportedOperationException())

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(UnsupportedOperationException())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())
}

private val deviceCapabilities =
    DeviceDecodingCapabilities(
        videoCodecs = listOf("h264"),
        audioCodecs = listOf("aac"),
        supportsDolbyVision = false,
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
    )
