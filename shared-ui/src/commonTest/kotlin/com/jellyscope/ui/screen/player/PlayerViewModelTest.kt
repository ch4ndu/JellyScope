// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.repository.DownloadCommandResult
import com.jellyscope.core.data.repository.DownloadDeletionResult
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SavePlaybackSelectionAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.DirectPlayPlanner
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.MAX_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PLAYBACK_HEALTH_EXCLUSION_MS
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlannedVideoPresentation
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackCapabilityResult
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackContentTimelineSource
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackMediaSourceInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerVolumeController
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.VideoCodecResolution
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import com.jellyscope.core.domain.playback.applePlayerBackendPolicy
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetItemsByIdsUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private enum class TestGuidancePolicy {
    Disabled,
    Actionable,
    Advisory,
}

private fun TestGuidancePolicy.toPlaybackHealthGuidancePolicy(): PlaybackHealthGuidancePolicy =
    when (this) {
        TestGuidancePolicy.Disabled -> PlaybackHealthGuidancePolicy.Disabled
        TestGuidancePolicy.Actionable -> PlaybackHealthGuidancePolicy.Actionable
        TestGuidancePolicy.Advisory -> PlaybackHealthGuidancePolicy.Advisory
    }

private val PlayerUiState.Content.qualityReductionGuidance: PlaybackHealthGuidance?
    get() =
        playbackGuidance
            ?.nextLowerQualityRungBps
            ?.takeIf { playbackGuidance.canReduceQuality }
            ?.let { playbackGuidance }

private val PlayerUiState.Content.passiveGuidance: PlaybackHealthGuidance?
    get() =
        playbackGuidance
            ?.takeIf { !it.canReduceQuality }

class PlayerViewModelTest {
    @Test
    fun startWithPlaybackInfoOverlaySnapshotsTheDiagnosticsPreferenceOncePerStart() =
        runPlayerViewModelTest {
            try {
                val enabled = playerFixture(playbackInfoAtStartEnabled = true)
                val disabled = playerFixture()
                runCurrent()

                assertTrue(enabled.viewModel.startWithPlaybackInfoOverlay)
                assertFalse(disabled.viewModel.startWithPlaybackInfoOverlay)

                enabled.viewModel.dispose()
                disabled.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun desktopAutoKeepsTheConcreteBackendOwnedByTheActiveController() {
        assertTrue(
            activeControllerSatisfiesBackend(
                resolvedBackend = PlayerBackend.Auto,
                trackedBackend = PlayerBackend.LibVlc,
                activeBackend = PlayerBackend.LibVlc,
            ),
        )
        assertFalse(
            activeControllerSatisfiesBackend(
                resolvedBackend = PlayerBackend.Auto,
                trackedBackend = PlayerBackend.LibVlc,
                activeBackend = PlayerBackend.Auto,
            ),
        )
    }

    @Test
    fun publishesVolumeControlAndDelegatesVolumeActions() =
        runPlayerViewModelTest {
            try {
                val volumeController = FakeVolumePlayerController(confirmInitialAudio = true)
                val fixture = playerFixture(volumeController = volumeController)
                runCurrent()

                assertEquals(PlayerVolumeState(), (fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)

                fixture.viewModel.setVolume(35)
                fixture.viewModel.toggleMute()
                runCurrent()

                assertEquals(PlayerVolumeState(volumePercent = 35, muted = true), volumeController.volumeStateFlow.value)
                assertEquals(
                    PlayerVolumeState(volumePercent = 35, muted = true),
                    (fixture.viewModel.state.value as PlayerUiState.Content).volumeControl,
                )
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun hidesVolumeControlAndLeavesActionsInertWithoutVolumeInterface() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)
                fixture.viewModel.setVolume(35)
                fixture.viewModel.toggleMute()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun exposesDisplayRefreshRateMatchingSetting() =
        runPlayerViewModelTest {
            try {
                val deviceSettings = FakePlayerDeviceSettingsStore(PlayerDeviceSettings(matchDisplayRefreshRate = true))
                val fixture = playerFixture(playerDeviceSettingsStore = deviceSettings)
                runCurrent()

                assertTrue(fixture.viewModel.matchDisplayRefreshRate.value)

                deviceSettings.setSettings(PlayerDeviceSettings(matchDisplayRefreshRate = false))
                runCurrent()

                assertFalse(fixture.viewModel.matchDisplayRefreshRate.value)
            } finally {
            }
        }

    @Test
    fun exposesInstalledPlanPresentationAndControllerRuntimeDiagnostics() =
        runPlayerViewModelTest {
            try {
                val presentation = PlannedVideoPresentation(1920, 1080, 23.976, "SDR")
                val streams =
                    playbackStreams.map { stream ->
                        if (stream.type == "Video") {
                            stream.copy(
                                width = presentation.width,
                                height = presentation.height,
                                realFrameRate = presentation.frameRate,
                                videoRangeType = presentation.videoRangeType,
                            )
                        } else {
                            stream
                        }
                    }
                val fixture = playerFixture(playbackInfo = playbackInfoWithStreams(streams), mediaStreams = streams)
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(presentation, content.videoPresentation)
                assertEquals(presentation, content.debugInfo?.videoPresentation)
                assertEquals(PlaybackRuntimeDiagnostics.EMPTY, fixture.viewModel.runtimeDiagnostics.value)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playerDiagnosticsCaptureCorrelatedIdentityFreeTrackSnapshots() =
        runPlayerViewModelTest {
            val capturedDiagnostics = mutableListOf<Pair<Severity, String>>()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag != DiagnosticTag.PlayerViewModel.wireValue) return
                            assertNull(throwable)
                            LogScrubber.capture(tag, message)?.let { diagnostic ->
                                capturedDiagnostics += severity to diagnostic
                            }
                        }
                    },
                ),
            )
            var fixture: PlayerFixture? = null
            try {
                fixture =
                    playerFixture(
                        initialAudioStreamIndex = 1,
                        initialSubtitleStreamIndex = 4,
                        publishPrepareEpoch = true,
                    )
                runCurrent()

                fun trackDiagnostics(): List<Pair<Severity, String>> =
                    capturedDiagnostics.filter { (_, line) -> "event=track-state" in line }

                val initialTrackDiagnostics = trackDiagnostics()
                val audioDiagnostic =
                    initialTrackDiagnostics
                        .lastOrNull { (_, line) -> "trackKind=audio" in line }
                        ?.second
                        ?: error("Missing audio track diagnostic")
                val subtitleDiagnostic =
                    initialTrackDiagnostics
                        .lastOrNull { (_, line) -> "trackKind=subtitle" in line }
                        ?.second
                        ?: error("Missing subtitle track diagnostic")
                assertEquals(Severity.Info, initialTrackDiagnostics.first().first)
                assertTrue(audioDiagnostic.contains("trackRequestedState=Selected"))
                assertTrue(audioDiagnostic.contains("trackConfirmedState=Selected"))
                assertTrue(audioDiagnostic.contains("trackMatchesRequest=true"))
                assertTrue(audioDiagnostic.contains("trackActivation=Active"))
                assertTrue(subtitleDiagnostic.contains("trackKind=subtitle"))
                assertTrue(subtitleDiagnostic.contains("trackRequestedState=Selected"))
                assertTrue(subtitleDiagnostic.contains("trackConfirmedState=Selected"))
                assertTrue(subtitleDiagnostic.contains("trackMatchesRequest=true"))
                assertTrue(subtitleDiagnostic.contains("trackActivation=Active"))
                assertTrue(subtitleDiagnostic.contains("subtitleRenderMode=LocalEmbeddedBitmap"))
                assertTrue(subtitleDiagnostic.contains("subtitleRenderStatus=Active"))
                assertTrue(subtitleDiagnostic.contains("subtitleStyleable=false"))

                val initialCount = initialTrackDiagnostics.size
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(positionMs = 1_000L)
                runCurrent()
                assertEquals(initialCount, trackDiagnostics().size)

                fixture.controller.runtimeDiagnosticsFlow.value =
                    fixture.controller.runtimeDiagnosticsFlow.value
                        .copy(prepareEpoch = 99L)
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(positionMs = 2_000L)
                runCurrent()
                val afterPrepareDiagnostics = trackDiagnostics()
                assertTrue(afterPrepareDiagnostics.size > initialCount)
                assertTrue(afterPrepareDiagnostics.any { (_, line) -> "prepareSequence=99" in line })

                fixture.viewModel.retry()
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(positionMs = 3_000L)
                runCurrent()
                val afterSessionDiagnostics = trackDiagnostics()
                assertTrue(afterSessionDiagnostics.size > afterPrepareDiagnostics.size)
                assertTrue(afterSessionDiagnostics.any { (_, line) -> "sessionSequence=2" in line })

                val forbiddenTokens =
                    listOf(
                        "item-1",
                        "source-1",
                        "streamIndex",
                        "target=",
                        "asset=",
                        "requestId=",
                        "serverId=",
                        "userId=",
                        "title=",
                        "label=",
                        "language=",
                        "reason=",
                        "message=",
                        "stack=",
                        "username=",
                        "http:",
                        "path=",
                    )
                assertTrue(
                    trackDiagnostics().all { (_, line) ->
                        forbiddenTokens.none { token -> token in line }
                    },
                )
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun terminalPlayerDiagnosticsCaptureRuntimeAndClosedRecoveryOutcomes() =
        runPlayerViewModelTest {
            val capturedDiagnostics = mutableListOf<Pair<Severity, String>>()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag != DiagnosticTag.PlayerViewModel.wireValue) return
                            assertNull(throwable)
                            LogScrubber.capture(tag, message)?.let { diagnostic ->
                                capturedDiagnostics += severity to diagnostic
                            }
                        }
                    },
                ),
            )
            var fixture: PlayerFixture? = null
            try {
                fixture = playerFixture(publishPrepareEpoch = true)
                runCurrent()
                fixture.controller.runtimeDiagnosticsFlow.value =
                    fixture.controller.runtimeDiagnosticsFlow.value.copy(
                        allocatedBufferBytes = 4_096L,
                        bufferedAheadMs = 2_500L,
                        libVlcCachePercent = 37.5f,
                    )
                runCurrent()
                fixture.viewModel.retry()
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 8_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                val terminalDiagnostics =
                    capturedDiagnostics.filter { (_, line) -> "event=terminal-error" in line }
                val retryDiagnostic =
                    terminalDiagnostics
                        .firstOrNull { (_, line) -> "terminalOutcome=RetryScheduled" in line }
                        ?: error("Missing scheduled recovery diagnostic")
                assertEquals(Severity.Warn, retryDiagnostic.first)
                assertTrue(retryDiagnostic.second.contains("errorCategory=network"))
                assertTrue(retryDiagnostic.second.contains("allocatedBufferBytes=4096"))
                assertTrue(retryDiagnostic.second.contains("bufferedAheadMs=2500"))
                assertTrue(retryDiagnostic.second.contains("libVlcCachePercent=37.5"))

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 9_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                val failedDiagnostics =
                    capturedDiagnostics.filter { (_, line) -> "event=terminal-error" in line }
                val failedDiagnostic =
                    failedDiagnostics
                        .firstOrNull { (_, line) -> "terminalOutcome=Failed" in line }
                        ?: error("Missing surfaced failure diagnostic")
                assertEquals(Severity.Warn, failedDiagnostic.first)
                val terminalCount = failedDiagnostics.size

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 10_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                assertEquals(
                    terminalCount,
                    capturedDiagnostics.count { (_, line) -> "event=terminal-error" in line },
                )

                fixture.viewModel.stop()
                runCurrent()
                val healthSummary =
                    capturedDiagnostics
                        .firstOrNull { (_, line) ->
                            "event=health-summary" in line &&
                                "allocatedBufferBytes=4096" in line &&
                                "bufferedAheadMs=2500" in line &&
                                "libVlcCachePercent=37.5" in line
                        }?.second
                        ?: error("Missing health summary diagnostic")
                assertTrue(healthSummary.contains("allocatedBufferBytes=4096"))
                assertTrue(healthSummary.contains("bufferedAheadMs=2500"))
                assertTrue(healthSummary.contains("libVlcCachePercent=37.5"))

                val forbiddenTokens =
                    listOf(
                        "item-1",
                        "source-1",
                        "streamIndex",
                        "target=",
                        "asset=",
                        "requestId=",
                        "serverId=",
                        "userId=",
                        "title=",
                        "label=",
                        "language=",
                        "reason=",
                        "message=",
                        "stack=",
                        "username=",
                        "http:",
                        "path=",
                    )
                assertTrue(
                    failedDiagnostics.all { (_, line) ->
                        forbiddenTokens.none { token -> token in line }
                    },
                )
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun launchToFirstFrameUsesMonotonicClockAndMatchingPrepareEpoch() =
        runPlayerViewModelTest {
            try {
                var monotonicMs = 100L
                val fixture =
                    playerFixture(
                        publishPrepareEpoch = true,
                        monotonicTimeMs = { monotonicMs },
                    )
                runCurrent()

                fixture.controller.runtimeDiagnosticsFlow.value =
                    fixture.controller.runtimeDiagnosticsFlow.value.copy(
                        prepareEpoch = 99L,
                        nativePrepareToFirstFrameMs = 250L,
                    )
                monotonicMs = 900L
                runCurrent()
                assertNull(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.launchToFirstFrameMs)

                fixture.controller.runtimeDiagnosticsFlow.value =
                    fixture.controller.runtimeDiagnosticsFlow.value.copy(
                        prepareEpoch = 1L,
                        nativePrepareToFirstFrameMs = 400L,
                    )
                monotonicMs = 1_600L
                runCurrent()

                assertEquals(
                    1_500L,
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.launchToFirstFrameMs,
                )
                assertEquals(listOf(1L to 1_500L), fixture.controller.recordedLaunchDurations)
            } finally {
            }
        }

    @Test
    fun launchMarkerMatchRejectsStaleControllerIdentityAndPrepareEpoch() {
        val activeController = FakePlayerController(confirmInitialAudio = true)
        val staleController = FakePlayerController(confirmInitialAudio = true)

        assertTrue(
            playbackLaunchMarkerMatches(
                markerController = activeController,
                markerPrepareEpoch = 4L,
                callbackController = activeController,
                callbackPrepareEpoch = 4L,
            ),
        )
        assertFalse(
            playbackLaunchMarkerMatches(
                markerController = activeController,
                markerPrepareEpoch = 4L,
                callbackController = staleController,
                callbackPrepareEpoch = 4L,
            ),
        )
        assertFalse(
            playbackLaunchMarkerMatches(
                markerController = activeController,
                markerPrepareEpoch = 4L,
                callbackController = activeController,
                callbackPrepareEpoch = 5L,
            ),
        )
    }

    @Test
    fun retryBeforeFirstFrameClearsThePreviousLaunchMarker() =
        runPlayerViewModelTest {
            try {
                var monotonicMs = 100L
                val fixture =
                    playerFixture(
                        publishPrepareEpoch = true,
                        monotonicTimeMs = { monotonicMs },
                    )
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()
                fixture.viewModel.retry()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                monotonicMs = 1_000L
                fixture.controller.runtimeDiagnosticsFlow.value =
                    PlaybackRuntimeDiagnostics.EMPTY.copy(
                        prepareEpoch = 1L,
                        nativePrepareToFirstFrameMs = 300L,
                    )
                runCurrent()

                assertNull(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.launchToFirstFrameMs)
                assertTrue(fixture.controller.recordedLaunchDurations.isEmpty())
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun debugInfoUsesInstalledSettingsCapAndOrigin() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultMaxBitrateBps = 8_000_000L),
                            ),
                    )
                runCurrent()

                val debugInfo = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo
                assertEquals(8_000_000L, debugInfo?.requestCapBitrateBps)
                assertEquals(PlaybackQualityCapOrigin.SettingsDefault, debugInfo?.qualityCapOrigin)
                assertNull(debugInfo?.effectiveTranscodeCapBitrateBps)
                assertEquals(PlaybackQualityPolicyOrigin.SettingsDefault, debugInfo?.qualityPolicyOrigin)
                assertEquals("Exact user limit: 8000000 bps", debugInfo?.clientLimiter)
                assertEquals(PlaybackCapabilityResult.SourceCopyAllowed, debugInfo?.capabilityResult)
                assertEquals("Use playback default", debugInfo?.configuredVlcTranscodeBudgetSummary())
                assertEquals("None (not transcoding)", debugInfo?.effectiveTranscodeCap)
                assertEquals("Initial request", debugInfo?.recoveryKind)
                assertEquals(
                    "Compatibility: 1 remaining (Fixed quality) · Quality: 0 remaining (Fixed quality)",
                    debugInfo?.remainingRecoveryBudget,
                )
            } finally {
            }
        }

    @Test
    fun debugInfoShowsFixedCompatibilityBudgetAfterItIsUsed() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultMaxBitrateBps = 8_000_000L),
                            ),
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()

                assertEquals(
                    "Compatibility: 0 remaining (Fixed quality) · Quality: 0 remaining (Fixed quality)",
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                        .debugInfo
                        ?.remainingRecoveryBudget,
                )
            } finally {
            }
        }

    @Test
    fun debugInfoMarksInheritedAutoQualityRecoveryAsRequiringExplicitAuto() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Auto),
                            ),
                    )
                runCurrent()

                val debugInfo = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo
                assertEquals(
                    "Compatibility: 1 remaining · Quality: unavailable (requires explicit Auto)",
                    debugInfo?.remainingRecoveryBudget,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun debugInfoKeepsOriginalPolicyOriginWhenTheRequestIsUnbounded() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Original)
                runCurrent()

                val debugInfo = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo
                assertEquals("Original", debugInfo?.qualityPolicyMode)
                assertEquals(PlaybackQualityPolicyOrigin.SessionOverride, debugInfo?.qualityPolicyOrigin)
                assertEquals("No client limit", debugInfo?.clientLimiter)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun debugInfoDistinguishesAwaitingAndObservedFirstVideoOutput() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        publishPrepareEpoch = true,
                        hasReliableFirstVideoOutput = true,
                        videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.NativeFirstOutput,
                    )
                runCurrent()

                assertEquals(
                    PlaybackFirstVideoOutputState.Awaiting,
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.firstVideoOutput?.state,
                )
                fixture.controller.videoOutputObservationsChannel.trySend(
                    VideoOutputObservation(
                        generation = 1L,
                        presented = true,
                        observedAtMs = 1L,
                    ),
                )
                runCurrent()

                assertEquals(
                    PlaybackFirstVideoOutputState.Observed,
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.firstVideoOutput?.state,
                )
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    // No-video-output requires an ADVANCING
    // playback clock, because a clock progressing with no displayed picture is the
    // black-video symptom while a frozen clock is ordinary slow startup (already
    // reported as SlowStartup). This fixture's clock never moves, so the honest
    // ViewModel-level expectation is that it does NOT report TimedOut. The
    // advancing-clock case that DOES time out is covered deterministically by
    // PlaybackHealthSessionCoordinatorTest.noVideoOutputRequiresAnAdvancingPlaybackClock.
    @Test
    fun debugInfoDoesNotTimeOutFirstVideoOutputWhileThePlaybackClockIsFrozen() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        publishPrepareEpoch = true,
                        hasReliableFirstVideoOutput = true,
                        videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.NativeFirstOutput,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()

                // Well past the two-second prepare exclusion and the five-second
                // no-output deadline.
                advanceTimeBy(15_000L)
                runCurrent()

                assertNotEquals(
                    PlaybackFirstVideoOutputState.TimedOut,
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.firstVideoOutput?.state,
                )
                fixture.viewModel.dispose()
                // Drain delayed work, not just already-due work: this path schedules
                // a deadline timer, and runCurrent() would leave it pending.
                advanceUntilIdle()
            } finally {
            }
        }

    @Test
    fun unsupportedFirstVideoOutputEvidenceCannotTriggerRecoveryOrGuidance() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        publishPrepareEpoch = true,
                        hasReliableFirstVideoOutput = true,
                        videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.Unsupported,
                        monotonicTimeMs = { testScheduler.currentTime },
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 100L)
                runCurrent()

                advanceTimeBy(6_999L)
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 101L)
                runCurrent()
                advanceTimeBy(2L)
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackFirstVideoOutputState.Unsupported, content.debugInfo?.firstVideoOutput?.state)
                assertNull(content.playbackGuidance)
                assertEquals(1, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                advanceUntilIdle()
            } finally {
            }
        }

    @Test
    fun reportsStartAndPeriodicProgressWhilePlaying() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                assertEquals<List<Report>>(listOf(Report.Start(positionMs = 1_000L)), fixture.reporter.reports)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 11_000L)
                advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(
                    Report.Progress(
                        positionMs = 11_000L,
                        isPaused = false,
                        eventName = PlaybackProgressEvent.TimeUpdate,
                    ),
                    fixture.reporter.reports.last(),
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun positionTicksStayOnPlaybackStateFlowWithoutRepublishingContent() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(confirmInitialAudio = false)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                val contentAfterStatus = fixture.viewModel.state.value as PlayerUiState.Content

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                assertSame(contentAfterStatus, fixture.viewModel.state.value)
                assertEquals(2_000L, fixture.viewModel.playbackState.value.positionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun reportsPauseUnpauseSeekAndCompletionEdges() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 2_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_500L)
                runCurrent()
                fixture.viewModel.seekTo(5_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(5_000L, fixture.controller.seekPositions.single())
                assertEquals<List<Report>>(
                    listOf(
                        Report.Start(positionMs = 1_000L),
                        Report.Progress(
                            positionMs = 2_000L,
                            isPaused = true,
                            eventName = PlaybackProgressEvent.Pause,
                        ),
                        Report.Progress(
                            positionMs = 2_500L,
                            isPaused = false,
                            eventName = PlaybackProgressEvent.Unpause,
                        ),
                        Report.Progress(
                            positionMs = 5_000L,
                            isPaused = false,
                            eventName = PlaybackProgressEvent.TimeUpdate,
                        ),
                        Report.Stopped(positionMs = 6_000L),
                    ),
                    fixture.reporter.reports,
                )
            } finally {
            }
        }

    @Test
    fun failureShowsRetryableErrorAndRetryDelegatesToController() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(Report.Stopped(positionMs = 0L), fixture.reporter.reports.last())

                fixture.viewModel.retry()
                runCurrent()

                assertEquals(1, fixture.controller.retryCount)
            } finally {
            }
        }

    @Test
    fun offlineRetryReacquiresLocalPlanWithoutRemoteDependencies() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-retry")
                val downloads = OfflinePlaybackDownloadRepository(offlineRecord(downloadId))
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.LibVlc,
                    )
                runCurrent()

                assertEquals(1, fixture.controller.offlinePrepareCount)
                assertEquals(1, downloads.getDownloadCalls)
                assertEquals(1, downloads.hasCompletedArtifactCalls)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()
                fixture.viewModel.retry()
                runCurrent()

                assertEquals(2, fixture.controller.offlinePrepareCount)
                assertEquals(2, downloads.getDownloadCalls)
                assertEquals(2, downloads.hasCompletedArtifactCalls)
                assertEquals(0, fixture.controller.retryCount)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun originalOfflineLaunchUsesNormalFactoryFallbackWhenRequestedBackendFails() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-fallback")
                val downloads = OfflinePlaybackDownloadRepository(offlineRecord(downloadId))
                val fallbackController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.ExoPlayer
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.LibVlc,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.LibVlc) {
                                error("requested offline backend unavailable")
                            }
                            check(backend == PlayerBackend.ExoPlayer)
                            fallbackController
                        },
                    )
                runCurrent()

                assertSame(fallbackController, fixture.viewModel.currentPlayerController)
                assertEquals(PlayerBackend.ExoPlayer, fallbackController.activeBackend)
                assertEquals(1, fallbackController.offlinePrepareCount)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsLaunchRequiresVlcKitWithoutPreparingAvPlayer() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val vlcKitController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.VlcKit
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider = appleOfflineProfileProvider(),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            check(backend == PlayerBackend.VlcKit)
                            vlcKitController
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertSame(vlcKitController, fixture.viewModel.currentPlayerController)
                assertEquals(0, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.offlinePrepareCount)
                assertEquals(1, vlcKitController.offlinePrepareCount)
                assertEquals(
                    DownloadArtifactKind.LocalHlsPackage,
                    vlcKitController.preparedPlan?.offlineArtifactKind,
                )
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)
                assertEquals(DownloadState.Completed, record.state)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsVlcKitFactoryFailureIsTypedAndDoesNotFallback() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls-missing")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider =
                            appleOfflineProfileProvider(
                                supportedBackends = setOf(PlayerBackend.AVPlayer),
                            ),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            error("native VLCKit unavailable at construction")
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(
                    PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
                    (fixture.viewModel.state.value as PlayerUiState.Error).error,
                )
                assertEquals(0, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.offlinePrepareCount)
                assertEquals(DownloadState.Completed, record.state)
                assertTrue(fixture.repository.requests.isEmpty())
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsWrongVlcKitCandidateIsReleasedAndTyped() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls-wrong")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val wrongController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.AVPlayer
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider = appleOfflineProfileProvider(),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            wrongController
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertEquals(1, wrongController.releaseCount)
                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(
                    PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
                    (fixture.viewModel.state.value as PlayerUiState.Error).error,
                )
                assertEquals(DownloadState.Completed, record.state)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun successfulPlaybackAfterControllerFailureRetainsTheInterestingDiagnostics() =
        runPlayerViewModelTest {
            try {
                val diagnosticsContext = PlaybackDiagnosticsContext()
                val capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                    )
                val provider =
                    object : DeviceProfileProvider {
                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = capabilities
                    }
                val fixture =
                    playerFixture(
                        deviceProfileProvider = provider,
                        playbackDiagnosticsContext = diagnosticsContext,
                    )
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()
                val capturedFailure = assertNotNull(diagnosticsContext.snapshot())

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()

                assertEquals(capturedFailure, diagnosticsContext.snapshot())
                assertEquals(PlayerBackend.AVPlayer, capturedFailure.backend)
                assertEquals(capabilities, capturedFailure.capabilities)
                assertEquals("h264", capturedFailure.source?.videoCodec)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun initialPlaybackInfoFailureKeepsVideoAndDoesNotGuessSubtitleDelivery() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults = ArrayDeque(listOf(Result.failure(IllegalStateException("offline")))),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                assertEquals(1, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun originalCapabilityRefusalShowsNonRetryableUnsupportedMediaAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertFalse(error.retryable)
                assertEquals(PlaybackError.UnsupportedMedia, error.error)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun exhaustedCapabilityRecoveryShowsNonRetryableUnsupportedMediaAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(noSupportedPlaybackInfo),
                                ),
                            ),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertFalse(error.retryable)
                assertEquals(PlaybackError.UnsupportedMedia, error.error)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun capabilityRecoveryRepositoryFailureKeepsRetryableNetworkErrorAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.failure(JellyfinApiException.NotReachable),
                                ),
                            ),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertTrue(error.retryable)
                assertEquals(PlaybackError.Network, error.error)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun unrelatedStartupPlanningFailureUsesUnknownError() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(Result.success(noSupportedPlaybackInfo)),
                            ),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertTrue(error.retryable)
                assertEquals(PlaybackError.Unknown, error.error)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun decoderFailureReplansWithDirectPlayDisabledBeforeShowingError() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()

                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)
                assertEquals(2, fixture.controller.prepareCount)
                assertTrue(fixture.controller.playCount >= 1)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun decoderRecoveryWaitsUntilPictureInPictureExits() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.viewModel.setPictureInPictureMode(true)

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()

                assertEquals(1, fixture.repository.requests.size)
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)

                fixture.viewModel.setPictureInPictureMode(false)
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun networkFailureRetriesOnceAtPositionWithDefaultPolicy() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()

                // One position-preserving replan with the DEFAULT policy (a
                // network drop is not a codec problem, so no ladder step).
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                val retryRequest = fixture.repository.requests.last()
                assertEquals(true, retryRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, retryRequest.requestPolicy.enableDirectStream)
                assertEquals(PlaybackClientTrigger.NetworkRetry, retryRequest.requestPolicy.clientTrigger)
                assertEquals(10_000_000L, retryRequest.startTimeTicks)
                assertEquals(2, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun secondNetworkFailureInSameItemSessionIsFatal() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 2_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()

                // The one-shot is per item playback session (NOT per installed
                // plan): the second failure surfaces fatally instead of looping.
                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(PlaybackError.Network, error.error)
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun slowStartupTimerShowsPersistentDismissibleMessageOnly() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        monotonicTimeMs = { testScheduler.currentTime },
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Loading)
                runCurrent()

                advanceTimeBy(9_999L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)

                advanceTimeBy(1L)
                runCurrent()
                val guidance =
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)
                assertEquals(PlaybackHealthGuidanceReason.SlowStartup, guidance.reason)
                assertFalse(guidance.canReduceQuality)
                assertFalse(guidance.canOpenPlaybackSettings)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.SlowStartup,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance).reason,
                )
                fixture.viewModel.dismissPlaybackGuidance()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun startupWarningDoesNotConsumePostStartGuidanceBudget() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        monotonicTimeMs = { testScheduler.currentTime },
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Loading)
                runCurrent()
                advanceTimeBy(10_000L)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.SlowStartup,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance).reason,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering)
                runCurrent()
                advanceTimeBy(5_000L)
                runCurrent()

                val postStartGuidance =
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)
                assertEquals(PlaybackHealthGuidanceReason.LongBuffering, postStartGuidance.reason)
                assertTrue(postStartGuidance.canOpenPlaybackSettings)

                fixture.viewModel.dismissPlaybackGuidance()
                runCurrent()
                repeat(2) {
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                    runCurrent()
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering)
                    runCurrent()
                }
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun disabledPolicyKeepsDelayedCumulativeEvidenceMeasurementOnly() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Disabled,
                        monotonicTimeMs = { testScheduler.currentTime },
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering)
                runCurrent()

                // Emit LongBuffering diagnostically without consuming the UI
                // budget, then close a nine-second historical interval.
                advanceTimeBy(5_000L)
                runCurrent()
                advanceTimeBy(4_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()

                advanceTimeBy(51_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering)
                runCurrent()

                // The first bounded estimate lands at 69 seconds while the old
                // interval drains from the rolling window. It emits nothing;
                // the ViewModel must schedule the remaining one second.
                advanceTimeBy(9_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)
                advanceTimeBy(1_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun stopInvalidatesPendingStartupTimer() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(monotonicTimeMs = { testScheduler.currentTime })
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Loading)
                runCurrent()
                advanceTimeBy(5_000L)
                runCurrent()

                fixture.viewModel.stop()
                runCurrent()
                advanceTimeBy(10_000L)
                runCurrent()

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackGuidance)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    // The redesign's load-bearing property: the detector measures how much bad
    // playback TIME it has seen, so the two Android backends — whose callback
    // cadences differ by ~25x — need equivalent evidence to nudge. Counting raw
    // reports would give ExoPlayer ~50 s of evidence and
    // LibVLC ~2 s for the same "two reports".
    @Test
    fun media3ShapedAndLibVlcShapedEvidenceRequireEquivalentMeasuredDuration() =
        runPlayerViewModelTest {
            try {
                // Media3 shape: ONE callback carrying a 50-frame batch accumulated
                // over 25 s. That is 2 fps sustained for 25 s, so it alone clears the
                // 20 s sustained threshold.
                var batchNowMs = 100_000L
                val batchFixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { batchNowMs },
                    )
                runCurrent()
                batchNowMs += 10_000L
                batchFixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                // The batch describes the 25 s of playback that just elapsed, so the
                // clock must actually advance across it — an interval reaching back
                // before the session's own start is not a thing a backend can report.
                batchNowMs += 25_000L
                batchFixture.controller.emitMeasurement(droppedFrames = 50L, intervalMs = 25_000L)
                runCurrent()
                val batchNudge =
                    assertNotNull((batchFixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)
                assertTrue((batchNudge.nextLowerQualityRungBps ?: 0L) > 0L)

                // Once per session: further batches do not raise a second nudge.
                batchFixture.viewModel.dismissPlaybackGuidance()
                runCurrent()
                batchNowMs += 25_000L
                batchFixture.controller.emitMeasurement(droppedFrames = 50L, intervalMs = 25_000L)
                runCurrent()
                assertNull((batchFixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)
                batchFixture.viewModel.dispose()
                runCurrent()

                // LibVLC shape: 1 s polls at the same 2 fps. Nineteen seconds of
                // evidence is not enough, and the twentieth second is — the same
                // ~20 s of bad playback the single Media3 batch represented.
                var pollNowMs = 100_000L
                val pollFixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { pollNowMs },
                    )
                runCurrent()
                pollNowMs += 10_000L
                pollFixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                repeat(19) {
                    pollNowMs += 1_000L
                    pollFixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                    runCurrent()
                }
                assertNull((pollFixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                pollNowMs += 1_000L
                pollFixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()
                assertNotNull((pollFixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                pollFixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    // A long interval qualifies only if its AVERAGE rate is bad, so a short burst
    // cannot claim a whole calm interval as evidence; and a severe but brief
    // interval cannot substitute for sustain.
    @Test
    fun neitherASmearedBurstNorABriefSevereIntervalQualifiesAsSustainedEvidence() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                // 50 frames over 60 s averages 0.83 fps: the interval is long enough
                // to clear the sustained threshold, but it does not qualify at all.
                nowMs += 60_000L
                fixture.controller.emitMeasurement(droppedFrames = 50L, intervalMs = 60_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // 100 frames over 5 s is a severe 20 fps, but only 5 s of evidence.
                nowMs += 5_000L
                fixture.controller.emitMeasurement(droppedFrames = 100L, intervalMs = 5_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun qualifyingEvidenceOlderThanTheWindowIsPrunedAndNeverAccumulates() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fun poll(seconds: Int) {
                    repeat(seconds) {
                        nowMs += 1_000L
                        fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                        runCurrent()
                    }
                }

                poll(seconds = 15)
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // A long calm stretch pushes the first 15 s out of the 60 s window.
                nowMs += 61_000L

                poll(seconds = 15)
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // The window itself still works: five more seconds inside it reaches 20.
                poll(seconds = 5)
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun measurementsArrivingWhileNotPlayingDoNotQualify() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = 1_000L)
                runCurrent()

                // Well past the sustained threshold, but a drop rate measured while
                // buffering is not evidence about playback quality.
                nowMs += 40_000L
                fixture.controller.emitMeasurement(droppedFrames = 200L, intervalMs = 40_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    // Events carry their own identity, so a measurement declined during the
    // seek/prepare exclusion is gone rather than pending. The id-based design kept
    // reintroducing exactly this bug: a declined sighting resurfacing later.
    @Test
    fun aMeasurementDeclinedInsideTheExclusionIsNotResurrectedAfterwards() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                // A seek re-arms the exclusion window.
                fixture.viewModel.seekTo(300_000L)
                runCurrent()

                // Abundant evidence, but inside the exclusion: declined outright.
                fixture.controller.emitMeasurement(droppedFrames = 100L, intervalMs = 50_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // Past the exclusion, one qualifying second must not be able to
                // complete the discarded 50 s.
                nowMs += PLAYBACK_HEALTH_EXCLUSION_MS + 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    // A measurement's interval ENDS when it arrives, so a Media3 batch can span a
    // seek and land after the exclusion window has already elapsed. Those drops
    // describe playback we discarded, and must not be credited.
    @Test
    fun aMeasurementWhoseIntervalPredatesASeekIsRejectedEvenWhenItArrivesLater() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.viewModel.seekTo(300_000L)
                runCurrent()

                // Arrives 30 s after the seek — long past the 2 s exclusion — but its
                // 50 s interval reaches back before the seek.
                nowMs += 30_000L
                fixture.controller.emitMeasurement(droppedFrames = 100L, intervalMs = 50_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // A measurement wholly after the seek is still honoured, so the
                // rejection above is boundary logic rather than a dead path.
                nowMs += 25_000L
                fixture.controller.emitMeasurement(droppedFrames = 50L, intervalMs = 25_000L)
                runCurrent()
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun evidenceStraddlingTheWindowEdgeIsCreditedOnlyForItsInWindowPortion() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                // Span A covers [t+5 s, t+20 s].
                nowMs += 20_000L
                fixture.controller.emitMeasurement(droppedFrames = 30L, intervalMs = 15_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // Span B covers [t+65 s, t+75 s]. The 60 s window now starts at
                // t+15 s, so only 5 s of span A is still inside it: 5 + 10 = 15 s.
                // Crediting span A in full would total 25 s and nudge here.
                nowMs += 55_000L
                fixture.controller.emitMeasurement(droppedFrames = 20L, intervalMs = 10_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // Span C covers [t+75 s, t+85 s]; span A has now left the window
                // entirely and B + C reach the 20 s threshold.
                nowMs += 10_000L
                fixture.controller.emitMeasurement(droppedFrames = 20L, intervalMs = 10_000L)
                runCurrent()
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun leavingPlayingClearsAccumulatedEvidenceSoPrePauseTimeDoesNotCombine() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                nowMs += 15_000L
                fixture.controller.emitMeasurement(droppedFrames = 30L, intervalMs = 15_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 16_000L)
                runCurrent()
                nowMs += 5_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 16_000L)
                runCurrent()

                // Fifteen more seconds after resuming. Without the clear-on-leaving-
                // Playing reset this would total 30 s and nudge.
                nowMs += 15_000L
                fixture.controller.emitMeasurement(droppedFrames = 30L, intervalMs = 15_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun droppedFrameGuidanceStaysOffWithoutControllerCapability() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture = playerFixture(monotonicTimeMs = { nowMs })
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                repeat(4) {
                    nowMs += 25_000L
                    fixture.controller.emitMeasurement(droppedFrames = 250L, intervalMs = 25_000L)
                    runCurrent()
                }

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun repeatedStallsSurfaceActionableGuidanceOncePerSession() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        monotonicTimeMs = {
                            nowMs
                        },
                    )
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                }

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                val guidance = assertNotNull(content.qualityReductionGuidance)
                assertTrue((guidance.nextLowerQualityRungBps ?: 0L) > 0L)

                fixture.viewModel.dismissPlaybackGuidance()
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                // Once per item playback session: further stalls never re-nudge.
                nowMs += 5_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 9_000L)
                runCurrent()
                nowMs += 5_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = 9_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun advisoryDroppedFrameEvidenceIsDismissOnly() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()

                nowMs += 20_000L
                fixture.controller.emitMeasurement(droppedFrames = 40L, intervalMs = 20_000L)
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertNull(content.qualityReductionGuidance)
                assertEquals(
                    PlaybackHealthGuidanceReason.DroppedFrames,
                    assertNotNull(content.passiveGuidance).reason,
                )
                fixture.viewModel.dismissPlaybackGuidance()
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                // Dismissal consumes the item-session budget, so another reason
                // cannot replace the user's dismissal.
                nowMs += 20_000L
                fixture.controller.emitMeasurement(droppedFrames = 40L, intervalMs = 20_000L)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun inheritedAutoDefaultPromptsForManualQualityChoiceWithoutReplanning() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    playbackWarningsEnabled = true,
                                    defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                nowMs += 10_000L
                val initialRequestCount = fixture.repository.requests.size

                nowMs += 19_000L
                fixture.controller.emitMeasurement(droppedFrames = 38L, intervalMs = 19_000L)
                runCurrent()
                nowMs += 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()

                assertEquals(initialRequestCount, fixture.repository.requests.size)
                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(
                    setOf(
                        PlaybackAction.ChooseLowerQuality,
                        PlaybackAction.TryHigherQuality,
                        PlaybackAction.TryOriginal,
                        PlaybackAction.Dismiss,
                    ),
                    content.playbackActionNotice?.actions,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    // Same scenario as above with the warnings preference off: the prompt must be
    // suppressed. Presentation is the only thing the switch controls.
    @Test
    fun disabledPlaybackWarningsSuppressTheActionNoticePrompt() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                                    playbackWarningsEnabled = false,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                nowMs += 10_000L

                nowMs += 19_000L
                fixture.controller.emitMeasurement(droppedFrames = 38L, intervalMs = 19_000L)
                runCurrent()
                nowMs += 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()

                assertNull(
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackActionNotice,
                )
                assertNull(
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackGuidance,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    // The regression guard for the switch: automatic recovery must still replan
    // silently when warnings are off. Explicit in-player Auto is what authorizes a
    // lower-quality recovery, so this asserts the replan happens with no notice.
    @Test
    fun disabledPlaybackWarningsStillAllowAutomaticQualityRecovery() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                                    playbackWarningsEnabled = false,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                // An explicit in-player Auto choice is the only authorization for an
                // automatic lower-quality replan.
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                val requestsBeforeRecovery = fixture.repository.requests.size
                nowMs += 10_000L

                nowMs += 19_000L
                fixture.controller.emitMeasurement(droppedFrames = 38L, intervalMs = 19_000L)
                runCurrent()
                nowMs += 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()

                assertTrue(
                    fixture.repository.requests.size > requestsBeforeRecovery,
                    "automatic quality recovery must still replan when warnings are disabled",
                )
                assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                    fixture.repository.requests
                        .last()
                        .requestPolicy.bitrateConstraint,
                )
                assertNull(
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackActionNotice,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    @Test
    fun actionableDroppedFramesLowerAutoOnlyOnceAfterTheSharedSustainedThreshold() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                nowMs += 10_000L
                val initialRequestCount = fixture.repository.requests.size

                nowMs += 19_000L
                fixture.controller.emitMeasurement(droppedFrames = 38L, intervalMs = 19_000L)
                runCurrent()
                assertEquals(initialRequestCount, fixture.repository.requests.size)

                nowMs += 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()

                val recoveredRequest = fixture.repository.requests.last()
                val sessionCap =
                    assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                        recoveredRequest.requestPolicy.bitrateConstraint,
                    )
                assertEquals(initialRequestCount + 1, fixture.repository.requests.size)
                assertTrue(sessionCap.bitrateBps < 17_100_000L)
                val recoveredContent = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackQualityMode.Auto, recoveredContent.selectedQualityPolicy.mode)
                assertEquals(sessionCap.bitrateBps, recoveredContent.selectedQualityMaxBitrate)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()
                assertEquals(
                    setOf(
                        PlaybackAction.KeepCurrentQuality,
                        PlaybackAction.TryHigherQuality,
                        PlaybackAction.ChooseLowerQuality,
                        PlaybackAction.Dismiss,
                    ),
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackActionNotice?.actions,
                )
                nowMs += 20_000L
                fixture.controller.emitMeasurement(droppedFrames = 40L, intervalMs = 20_000L)
                runCurrent()
                assertEquals(initialRequestCount + 1, fixture.repository.requests.size)
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    @Test
    fun failedLowerQualityRecoveryPromptThenTryHigherDoesNotPublishStaleLowerNotice() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                nowMs += 10_000L
                nowMs += 19_000L
                fixture.controller.emitMeasurement(droppedFrames = 38L, intervalMs = 19_000L)
                runCurrent()
                nowMs += 1_000L
                fixture.controller.emitMeasurement(droppedFrames = 2L, intervalMs = 1_000L)
                runCurrent()
                assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                    fixture.repository.requests
                        .last()
                        .requestPolicy.bitrateConstraint,
                )

                // The lower-quality plan fails, then its one compatibility retry
                // fails too, exhausting automatic recovery and surfacing a prompt.
                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()
                assertEquals(
                    PlaybackActionNoticeReason.CompatibilityRecoveryExhausted,
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackActionNotice?.reason,
                )

                fixture.viewModel.handlePlaybackAction(PlaybackAction.TryHigherQuality)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                assertNull(
                    assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).playbackActionNotice,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    @Test
    fun passiveRepeatedStallsWinBeforeLaterDroppedFrameEvidence() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        hasDroppedFrameMeasurements = true,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                assertEquals(
                    PlaybackHealthGuidanceReason.RepeatedStalls,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                nowMs += 20_000L
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                fixture.controller.emitMeasurement(droppedFrames = 40L, intervalMs = 20_000L)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.RepeatedStalls,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun autoRepeatedStallsApplyOneSessionLimitWithoutChangingTheAutoPolicy() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()

                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }

                val recoveryRequest = fixture.repository.requests.last()
                val sessionLimit =
                    assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                        recoveryRequest.requestPolicy.bitrateConstraint,
                    )
                assertTrue(sessionLimit.bitrateBps < 17_100_000L)
                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackQualityMode.Auto, content.selectedQualityPolicy.mode)
                assertEquals(sessionLimit.bitrateBps, content.selectedQualityMaxBitrate)

                fixture.viewModel.tryHigherQualityOrOriginal()
                runCurrent()

                val tryHigherRequest = fixture.repository.requests.last()
                assertIs<PlaybackBitrateConstraint.NoClientLimit>(
                    tryHigherRequest.requestPolicy.bitrateConstraint,
                )
                val higherContent = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackQualityMode.Auto, higherContent.selectedQualityPolicy.mode)
                assertNull(higherContent.selectedQualityMaxBitrate)
                assertTrue(higherContent.qualityOverrideExplicit)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun keepingRecoveredQualityReplansAsAnExplicitFixedSessionChoice() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                val initialRequestCount = fixture.repository.requests.size

                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }

                val recoveredLimit =
                    assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                        fixture.repository.requests
                            .last()
                            .requestPolicy.bitrateConstraint,
                    )
                fixture.viewModel.handlePlaybackAction(PlaybackAction.KeepCurrentQuality)
                runCurrent()

                assertEquals(initialRequestCount + 2, fixture.repository.requests.size)
                val keptRequest = fixture.repository.requests.last()
                assertEquals(recoveredLimit.bitrateBps, keptRequest.maxStreamingBitrate)
                assertIs<PlaybackBitrateConstraint.ExactUserLimit>(keptRequest.requestPolicy.bitrateConstraint)
                assertEquals(PlaybackQualityCapOrigin.ExplicitSessionChoice, keptRequest.requestPolicy.qualityCapOrigin)
                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals("Fixed", content.debugInfo?.qualityPolicyMode)
                assertEquals(PlaybackQualityPolicyOrigin.SessionOverride, content.debugInfo?.qualityPolicyOrigin)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun explicitAutoRecoveryThenAudioChangePersistsAutoInsteadOfTheSessionCap() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()

                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                runCurrent()

                val recoveryRequest = fixture.repository.requests.last()
                assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                    recoveryRequest.requestPolicy.bitrateConstraint,
                )

                fixture.viewModel.selectAudio(streamIndex = 3)
                runCurrent()
                advanceTimeBy(150L)
                runCurrent()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertEquals(3, assertNotNull(saved).audioStreamIndex)
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    @Test
    fun explicitQualityChangeClearsAdvisoryGuidanceButDoesNotRearmTheItemSession() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.selectQuality(null)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L + stall)
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = 10_000L + stall)
                    runCurrent()
                }
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun recoveredGuidanceWaitsForReplacementPlanToReachPlaying() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        guidancePolicy = TestGuidancePolicy.Advisory,
                    )
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()
                assertNull((fixture.viewModel.state.value as? PlayerUiState.Content)?.passiveGuidance)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.RecoveredPlaybackFailure,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun advisoryRecoverySurvivesReplacementWithAnotherController() =
        runPlayerViewModelTest {
            try {
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
                val profileProvider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = androidPlayerBackendPolicy()

                        override val availableBackends: Set<PlayerBackend> =
                            setOf(PlayerBackend.LibVlc, PlayerBackend.ExoPlayer)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                            )
                    }
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        deviceProfileProvider = profileProvider,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    playbackWarningsEnabled = true,
                                    defaultPlayerBackend = PlayerBackend.LibVlc,
                                ),
                            ),
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) replacementController else initialController
                        },
                    )
                initialController = fixture.controller
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()
                assertNull((fixture.viewModel.state.value as? PlayerUiState.Content)?.passiveGuidance)

                replacementController.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.RecoveredPlaybackFailure,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun alternateControllerFactoryRunsOnWorkDispatcherAndGuardsOldControls() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val workDispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
                lateinit var viewModel: PlayerViewModel
                val profileProvider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = androidPlayerBackendPolicy()

                        override val availableBackends: Set<PlayerBackend> =
                            setOf(PlayerBackend.LibVlc, PlayerBackend.ExoPlayer)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                            )
                    }
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        deviceProfileProvider = profileProvider,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc),
                            ),
                        workDispatcher = workDispatcher,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) {
                                check(workDispatcher.running)
                                val previousPlayCount = initialController.playCount
                                viewModel.play()
                                assertEquals(previousPlayCount, initialController.playCount)
                                replacementController
                            } else {
                                initialController
                            }
                        },
                    )
                initialController = fixture.controller
                viewModel = fixture.viewModel
                runCurrent()

                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder, positionMs = 4_000L)
                runCurrent()

                assertEquals(PlayerBackend.ExoPlayer, replacementController.activeBackend)
                assertTrue(replacementController.prepareCount > 0)
                assertTrue(workDispatcher.dispatchCount > 0)

                viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stopDuringControllerInstallReportsOnceAndPreventsFallbackReplan() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val workDispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
                lateinit var viewModel: PlayerViewModel
                val profileProvider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = androidPlayerBackendPolicy()
                        override val availableBackends: Set<PlayerBackend> =
                            setOf(PlayerBackend.LibVlc, PlayerBackend.ExoPlayer)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                            )
                    }
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        deviceProfileProvider = profileProvider,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc),
                            ),
                        workDispatcher = workDispatcher,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) {
                                check(workDispatcher.running)
                                viewModel.stop()
                                assertEquals(0, initialController.stopCount)
                                replacementController
                            } else {
                                initialController
                            }
                        },
                    )
                initialController = fixture.controller
                viewModel = fixture.viewModel
                runCurrent()

                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Start>()
                        .size,
                )
                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder, positionMs = 4_000L)
                runCurrent()

                assertEquals(1, replacementController.stopCount)
                assertEquals(0, replacementController.prepareCount)
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )

                viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stoppingTheItemSessionClearsAdvisoryGuidance() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.stop()
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun retryStartsAFreshGuidanceSessionThatCanFireAgain() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        monotonicTimeMs = {
                            nowMs
                        },
                    )
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                }
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.retry()
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 10_000L + 1_000L * (stall + 1))
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 10_000L + 1_000L * (stall + 1))
                    runCurrent()
                }
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun seekAdjacentBufferingDoesNotCountTowardStallGuidance() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture = playerFixture(hasReliableBufferingTransitions = true, monotonicTimeMs = { nowMs })
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                    // Buffering right after a seek is contractual, not a stall.
                    fixture.viewModel.seekTo(2_000L * (stall + 1))
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 2_000L * (stall + 1))
                    runCurrent()
                    nowMs += 5_000L
                }

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun stallGuidanceStaysOffWithoutControllerCapability() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture = playerFixture(monotonicTimeMs = { nowMs })
                runCurrent()

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = 1_000L * (stall + 1))
                    runCurrent()
                }

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).qualityReductionGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun unavailableInitialAudioRecoversOnceWithDirectPlayDisabled() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(transcodePlaybackInfo),
                                ),
                            ),
                    )
                runCurrent()
                val failedTarget = requireNotNull(fixture.controller.preparedPlan?.audioActivationTarget)

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectPlay,
                )
                assertEquals(
                    PlaybackClientTrigger.AudioActivationFallback,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.clientTrigger,
                )
                assertEquals(
                    120_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(1, (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex)

                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun audioRecoveryRejectsAnotherDirectPlayPlanWithoutLooping() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                // This test exists to prove the recovery rejects a server that
                // IGNORES EnableDirectPlay, so the fake must stay non-compliant.
                fixture.repository.honoursRequestPolicy = false
                runCurrent()
                val failedTarget = requireNotNull(fixture.controller.preparedPlan?.audioActivationTarget)

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing).copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(1, fixture.controller.prepareCount)
            } finally {
            }
        }

    @Test
    fun responseSelectedAudioSubstitutionBecomesRequestedAndInstalledTruth() =
        runPlayerViewModelTest {
            try {
                val memory = PlaybackSelectionMemory()
                val serverSubstitution =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            directPlayPlaybackInfo.mediaSources.map { source ->
                                source.copy(defaultAudioStreamIndex = 3)
                            },
                    )
                val fixture = playerFixture(playbackInfo = serverSubstitution, memory = memory)
                runCurrent()

                assertEquals(
                    1,
                    fixture.repository.requests
                        .single()
                        .audioStreamIndex,
                )
                assertEquals(3, (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex)
                assertEquals(3, memory.selectionFor(session.accountIdentity(), "item-1")?.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun decoderFallbackPlaybackInfoFailureDoesNotReinstallDirectPlay() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.failure(IllegalStateException("retry failed")),
                                ),
                            ),
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectPlay,
                )
            } finally {
            }
        }

    @Test
    fun audioUnavailablePlaybackStateStaysContentAndPublishesNoticeState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Playing,
                        positionMs = 2_000L,
                        audioUnavailable = true,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(true, content.audioUnavailable)
                assertEquals(true, content.playbackState.audioUnavailable)
                assertEquals(PlaybackStatus.Playing, content.playbackState.status)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun retryAfterInitialPlanningFailureRunsFullLoadPath() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(noSupportedPlaybackInfo),
                                ),
                            ),
                    )
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(0, fixture.controller.prepareCount)

                fixture.viewModel.retry()
                runCurrent()

                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.retryCount)
                assertEquals(2, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun usesPlaybackInfoTranscodePlanAndServerPlaySessionIdForReporting() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfo =
                            PlaybackInfo(
                                playSessionId = "server-play-session-1",
                                mediaSources =
                                    listOf(
                                        PlaybackMediaSourceInfo(
                                            id = "source-1",
                                            supportsDirectPlay = false,
                                            supportsDirectStream = false,
                                            supportsTranscoding = true,
                                            transcodingUrl = "/Videos/item-1/master.m3u8",
                                            container = "mkv",
                                            bitrate = 17_100_000L,
                                            mediaStreams = playbackStreams,
                                        ),
                                    ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(
                    "https://jellyfin.example/Videos/item-1/master.m3u8",
                    fixture.controller.preparedPlan?.streamUrl,
                )
                assertEquals<List<Report>>(
                    listOf(
                        Report.Start(
                            positionMs = 1_000L,
                            playSessionId = "server-play-session-1",
                            playMethod = "Transcode",
                        ),
                    ),
                    fixture.reporter.reports,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun transcodeSeekOutsideProducedWindowRestartsTranscodeAtTarget() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                // No in-stream seek; the transcode is restarted at the target instead.
                assertEquals(emptyList<Long>(), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore + 1, fixture.controller.prepareCount)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(30_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun transcodeSeekInsideProducedWindowSeeksInStream() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                // 4s is inside [start=1s, buffered=6s]; already produced, so seek in-stream.
                fixture.viewModel.seekTo(4_000L)
                runCurrent()

                assertEquals(listOf(4_000L), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun transcodeSeekSeeksInStreamWhenControllerDoesNotRestart() =
        runPlayerViewModelTest {
            try {
                // Android/desktop controllers leave transcodeSeekRestartsStream = false:
                // even an out-of-window transcode seek stays an in-stream seek (unchanged behavior).
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                assertEquals(listOf(30_000L), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun selectingEmbeddedAudioOnDirectPlayUsesControllerOrdinalWithoutReplan() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.selectAudio(streamIndex = 3)
                runCurrent()

                // Ordinal 0 is the deliberate prepare-time application of the
                // Jellyfin default track (keeps ExoPlayer's auto-selection in
                // sync with the UI); ordinal 1 is the user's selection.
                assertEquals(listOf(0, 1), fixture.controller.embeddedAudioOrdinals)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(
                    3,
                    (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex,
                )
            } finally {
            }
        }

    @Test
    fun seekAfterSubtitleEncodeFallbackUsesDefaultPolicyAgain() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                    Result.success(playbackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                val seekRequest = fixture.repository.requests.last()
                assertEquals(300_000_000L, seekRequest.startTimeTicks)
                assertEquals(true, seekRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, seekRequest.requestPolicy.enableDirectStream)
                assertNull(seekRequest.requestPolicy.forceEncodeSubtitle)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(30_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun subtitleReplanKeepsPreviouslyActiveTrackUntilFrenchPlanIsInstalled() =
        runPlayerViewModelTest {
            try {
                val streams = englishTextAndFrenchPgsPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(subtitleTranscodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val englishContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, englishContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, englishContent.subtitleRenderInfo.status)
                assertEquals("English SRT", englishContent.subtitleRenderInfo.label)

                fixture.viewModel.selectSubtitle(streamIndex = 5)

                val replanningContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, replanningContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, replanningContent.subtitleRenderInfo.status)
                assertEquals("English SRT", replanningContent.subtitleRenderInfo.label)

                runCurrent()

                val frenchContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(5, frenchContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, frenchContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, frenchContent.subtitleRenderInfo.mode)
                assertEquals("French PGS", frenchContent.subtitleRenderInfo.label)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun textSubtitleReplanInVideoTranscodePlanStaysServerRenderedWithoutUnavailableState() =
        runPlayerViewModelTest {
            try {
                val streams = englishAndFrenchAssPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val englishContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, englishContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, englishContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, englishContent.subtitleRenderInfo.mode)

                fixture.viewModel.selectSubtitle(streamIndex = 5)

                val replanningContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, replanningContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, replanningContent.subtitleRenderInfo.status)
                assertEquals("English ASS", replanningContent.subtitleRenderInfo.label)

                runCurrent()

                val frenchContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(5, frenchContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, frenchContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, frenchContent.subtitleRenderInfo.mode)
                assertEquals("French ASS", frenchContent.subtitleRenderInfo.label)
                assertEquals("Server rendered", frenchContent.subtitleRenderInfo.reason)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun newerDirectSubtitleChoiceCancelsPendingFrenchReplan() =
        runPlayerViewModelTest {
            try {
                val streams = englishTextAndFrenchPgsPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(subtitleTranscodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 5)
                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Off, content.subtitleRenderInfo.status)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(null, fixture.controller.embeddedTextOrdinals.last())
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun embeddedSubtitleOffThenOnUsesControllerWithoutReplan() =
        runPlayerViewModelTest {
            val workDispatcher = StandardTestDispatcher(testScheduler)
            try {
                val fixture =
                    playerFixture(
                        mediaStreams = defaultSubtitlePlaybackStreams(),
                        playbackInfo = playbackInfoWithStreams(defaultSubtitlePlaybackStreams()),
                        initialSubtitleStreamIndex = -1,
                        workDispatcher = workDispatcher,
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        status = PlaybackStatus.Playing,
                        positionMs = 15_000L,
                        bufferedPositionMs = 15_000L,
                    )
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertIs<PlannedSubtitle.Off>(fixture.controller.preparedPlan?.plannedSubtitle)
                assertEquals(15_000L, fixture.controller.playbackStateFlow.value.positionMs)
                assertEquals(listOf<Int?>(null, 0), fixture.controller.embeddedTextOrdinals)
                val activeTarget =
                    assertIs<SubtitleActivationState.Active>(fixture.controller.playbackStateFlow.value.subtitleActivation).target
                assertEquals(4, activeTarget.streamIndex)
                assertEquals(LocalSubtitleKind.EmbeddedText, activeTarget.kind)
                assertEquals(
                    SubtitleRenderStatus.Active,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )
                assertEquals(
                    SubtitleRenderMode.LocalEmbeddedText,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.mode,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 15_000L)
                runCurrent()
                fixture.viewModel.dispose()
                advanceUntilIdle()
            } finally {
            }
        }

    @Test
    fun selectingPgsSubtitleReplansWithSubtitleIndexAtCurrentPosition() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(subtitleTranscodePlaybackInfo),
                                ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 42_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    4,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    420_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertFalse(fixture.controller.embeddedTextOrdinals.contains(0))
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.subtitleRenderInfo.mode)
                assertFalse(content.subtitleRenderInfo.styleable)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.debugInfo?.subtitleRenderMode)
                assertFalse(content.debugInfo?.subtitleStyleable ?: true)
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )
            } finally {
            }
        }

    @Test
    fun selectingNoneAfterBurnedSubtitleReplansAndKeepsPlaybackActive() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(subtitleTranscodePlaybackInfo),
                                    Result.success(directPlayPlaybackInfo),
                                ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 42_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 44_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                assertEquals(3, fixture.controller.prepareCount)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    440_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(null, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(null, fixture.controller.preparedPlan?.subtitleActivationTarget)
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Off, content.subtitleRenderInfo.status)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(3, fixture.controller.playCount)
                assertEquals(
                    2,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )
            } finally {
            }
        }

    @Test
    fun replanWhilePausedRemainsPaused() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 30_000L)
                runCurrent()
                val playCountBefore = fixture.controller.playCount

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()

                assertEquals(
                    300_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(playCountBefore, fixture.controller.playCount)
                assertEquals(1, fixture.controller.pauseCount)
                assertEquals(PlaybackStatus.Paused, fixture.controller.playbackState.value.status)
            } finally {
            }
        }

    @Test
    fun selectingQualityCapReplansWithMaxBitrateAndResumesPosition() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 55_000L)
                runCurrent()

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    8_000_000L,
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    550_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(8_000_000L, fixture.controller.preparedPlan?.maxStreamingBitrate)
            } finally {
            }
        }

    @Test
    fun selectingOriginalReplansWithOriginalPolicyAndNoFiniteClientLimit() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.selectQuality(maxBitrateBps = null)
                runCurrent()

                assertEquals(
                    null,
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun originalSettingsDefaultReachesPlannerAsOriginalPolicy() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                assertEquals(
                    null,
                    fixture.repository.requests
                        .single()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun vlcDefaultQualityAppliesToTheInitialRequestAndExplainsItsSource() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                                    vlcTranscodeMaxBitrateBps = 8_000_000L,
                                ),
                            ),
                    )
                runCurrent()

                val request = fixture.repository.requests.single()
                assertEquals(8_000_000L, request.maxStreamingBitrate)
                assertIs<PlaybackBitrateConstraint.ExactUserLimit>(request.requestPolicy.bitrateConstraint)
                assertEquals(PlaybackQualityCapOrigin.SettingsDefault, request.requestPolicy.qualityCapOrigin)
                assertEquals(VideoCodecResolution(1_920, 1_080), request.requestPolicy.qualityResolutionCap)

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackQualityPolicy.fixed(8_000_000L), content.inheritedQualityPolicy)
                assertTrue(content.inheritedQualityUsesVlcSetting)
                assertFalse(content.qualityOverrideExplicit)
            } finally {
            }
        }

    @Test
    fun explicitOriginalOverridesVlcDefaultOnlyForTheCurrentPlayback() =
        runPlayerViewModelTest {
            try {
                val preferences =
                    FakePlaybackPreferencesStore(
                        PlaybackPreferences(
                            defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                            vlcTranscodeMaxBitrateBps = 8_000_000L,
                        ),
                    )
                val first =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore = preferences,
                    )
                runCurrent()

                first.viewModel.selectQuality(PlaybackQualityPolicy.Original)
                runCurrent()

                assertNull(
                    first.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    first.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
                first.viewModel.dispose()
                runCurrent()

                val reopened =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore = preferences,
                    )
                runCurrent()

                assertEquals(
                    8_000_000L,
                    reopened.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Fixed,
                    reopened.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun chooseLowerQualityNoticeActionOpensTheInPlayerQualityPicker() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.handlePlaybackAction(PlaybackAction.ChooseLowerQuality)

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlayerPicker.Quality, content.pickerVisible)
            } finally {
            }
        }

    @Test
    fun playbackSelectionMemoryReappliesForSameItemButNotQualityForNextItem() =
        runPlayerViewModelTest {
            try {
                val memory = PlaybackSelectionMemory()
                val first = playerFixture(memory = memory)
                runCurrent()
                first.viewModel.selectAudio(streamIndex = 3)
                first.viewModel.selectSubtitle(streamIndex = null)
                first.viewModel.selectQuality(maxBitrateBps = 12_000_000L)
                runCurrent()

                val sameItem = playerFixture(memory = memory)
                runCurrent()

                assertEquals(
                    3,
                    sameItem.repository.requests
                        .first()
                        .audioStreamIndex,
                )
                assertEquals(
                    -1,
                    sameItem.repository.requests
                        .first()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    null,
                    sameItem.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )

                val nextItem = playerFixture(itemId = "item-2", memory = memory)
                runCurrent()

                assertEquals(
                    1,
                    nextItem.repository.requests
                        .first()
                        .audioStreamIndex,
                )
                assertEquals(
                    null,
                    nextItem.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )
            } finally {
            }
        }

    @Test
    fun selectingQualityDoesNotFreezeAutoResolvedAudioInDurableStore() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture = playerFixture(selectionStore = store)
                runCurrent()

                // Audio is auto-resolved (never explicitly picked); the user changes
                // ONLY quality. Auto-resolved audio must not freeze into a
                // durable title override.
                fixture.viewModel.selectQuality(maxBitrateBps = 4_000_000L)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
            } finally {
            }
        }

    @Test
    fun selectingAutoIsSessionOnly() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
                assertEquals(
                    PlaybackQualityMode.Auto,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun clearingQualityOverrideReinheritsTheServerDefault() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.fixed(4_000_000L))
                advanceUntilIdle()

                fixture.viewModel.clearQualityOverride()
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
                assertNull(
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
            } finally {
            }
        }

    @Test
    fun selectingAudioDoesNotFreezeAutoResolvedQualityInDurableStore() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture = playerFixture(selectionStore = store)
                runCurrent()

                // Quality is auto-resolved; the user changes ONLY audio. The auto
                // quality must not freeze durably.
                fixture.viewModel.selectAudio(streamIndex = 3)
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun explicitDetailAudioPickMatchingAutomaticDefaultIsPersistedAtLaunch() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                // Detail sends a non-null route only after an explicit action. Even though
                // Spanish also matches automatic language resolution, provenance makes this
                // an explicit choice and it must survive process death.
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        initialAudioStreamIndex = 3,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(preferredAudioLanguage = "spa"),
                            ),
                    )
                runCurrent()
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun serverSelectedAudioSubstitutionIsNotWrittenDurably() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val memory = PlaybackSelectionMemory()
                val substitutedResponse =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            directPlayPlaybackInfo.mediaSources.map { source ->
                                source.copy(defaultAudioStreamIndex = 3)
                            },
                    )
                val first =
                    playerFixture(
                        playbackInfo = substitutedResponse,
                        selectionStore = store,
                        memory = memory,
                    )
                runCurrent()
                first.viewModel.dispose()
                runCurrent()

                val reopened =
                    playerFixture(
                        playbackInfo = substitutedResponse,
                        selectionStore = store,
                        memory = memory,
                    )
                runCurrent()

                reopened.viewModel.selectQuality(maxBitrateBps = 4_000_000L)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
            } finally {
            }
        }

    @Test
    fun explicitDetailAudioPickDistinctFromAutoIsPersistedAtLaunch() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                // Auto-resolution is English (index 1, the default track); the user explicitly
                // picked Spanish (index 3) in Detail. That genuine pick — distinct from the
                // auto-resolved track — must persist durably at launch even with no in-player
                // change afterward (an explicit user choice wins).
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        initialAudioStreamIndex = 3,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(preferredAudioLanguage = "eng"),
                            ),
                    )
                runCurrent()
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun playbackPreferencesApplyInitialTracksAndBitrateWhileResumeAlwaysResumes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultMaxBitrateBps = 8_000_000L,
                                    preferredAudioLanguage = "spa",
                                    preferredSubtitleLanguage = "eng",
                                ),
                            ),
                        workDispatcher = dispatcher,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                // Resume is unconditional now: the launch position is always honoured.
                assertEquals(10_000_000L, request.startTimeTicks)
                assertEquals(3, request.audioStreamIndex)
                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(8_000_000L, request.maxStreamingBitrate)
                assertEquals(3, content.selectedAudioStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)
                assertEquals(8_000_000L, content.selectedQualityMaxBitrate)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun defaultSubtitleIsSelectedAtPlaybackStartWhenNoRememberedOrPreferredSelection() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(listOf<Int?>(0), fixture.controller.embeddedTextOrdinals)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun externalSubtitleSelectionIsReportedAsStyleableInDebugInfo() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                val request = fixture.repository.requests.first()
                val activationTarget = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")

                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(LocalSubtitleKind.ExternalText, activationTarget.kind)
                assertEquals(4, activationTarget.streamIndex)
                assertEquals(
                    "https://jellyfin.example/Videos/item-1/subtitles/4.srt",
                    fixture.controller.preparedExternalSubtitle?.url,
                )
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.LocalExternalText, content.subtitleRenderInfo.mode)
                assertTrue(content.subtitleRenderInfo.styleable)
                assertEquals("English External", content.debugInfo?.subtitleLabel)
                assertEquals("eng", content.debugInfo?.subtitleLanguage)
                assertEquals(4, content.debugInfo?.subtitleStreamIndex)
                assertEquals(SubtitleRenderMode.LocalExternalText, content.debugInfo?.subtitleRenderMode)
                assertTrue(content.debugInfo?.subtitleStyleable == true)

                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        subtitleActivation =
                            SubtitleActivationState.Active(
                                activationTarget.copy(requestId = activationTarget.requestId - 1L),
                            ),
                    )
                runCurrent()

                val staleContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Pending, staleContent.subtitleRenderInfo.status)
                assertEquals(null, staleContent.selectedSubtitleStreamIndex)

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(activationTarget),
                    )
                runCurrent()

                val recoveredContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Active, recoveredContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, recoveredContent.subtitleRenderInfo.mode)
                assertEquals(4, recoveredContent.selectedSubtitleStreamIndex)
                assertFalse(recoveredContent.subtitleRenderInfo.styleable)
                assertTrue(recoveredContent.subtitleNotice?.message?.contains("server-rendered") == true)
                assertEquals(12_000L, fixture.controller.preparedPlan?.startPositionMs)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(
                    SubtitleDeliveryMethod.Encode,
                    assertIs<PlannedSubtitle.Track>(fixture.controller.preparedPlan?.plannedSubtitle).deliveryMethod,
                )
                assertEquals(2, fixture.controller.playCount)
                assertEquals(
                    "srt",
                    fixture.repository.requests
                        .last()
                        .requestPolicy.forceEncodeSubtitle
                        ?.normalizedFormat,
                )
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectPlay,
                )
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectStream,
                )
                assertEquals(
                    4,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.forceEncodeSubtitle
                        ?.streamIndex,
                )

                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(activationTarget),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun subtitleEncodeFallbackForPausedPlaybackPreservesPositionAndPauseIntent() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Paused, positionMs = 8_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(80_000_000L, fallbackRequest.startTimeTicks)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)
                assertEquals(4, fallbackRequest.requestPolicy.forceEncodeSubtitle?.streamIndex)
                assertEquals("srt", fallbackRequest.requestPolicy.forceEncodeSubtitle?.normalizedFormat)
                assertEquals(PlaybackClientTrigger.SubtitleActivationFallback, fallbackRequest.requestPolicy.clientTrigger)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(1, fixture.controller.pauseCount)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(
                    SubtitleDeliveryMethod.Encode,
                    assertIs<PlannedSubtitle.Track>(fixture.controller.preparedPlan?.plannedSubtitle).deliveryMethod,
                )
                assertEquals(8_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun offDuringPendingSubtitleEncodeFallbackRestoresDirectPlayAndRejectsStaleResult() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(playbackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.selectSubtitle(null)
                runCurrent()
                fallbackGate.complete(Unit)
                runCurrent()

                val offRequest = fixture.repository.requests.last()
                assertEquals(true, offRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, offRequest.requestPolicy.enableDirectStream)
                assertNull(offRequest.requestPolicy.forceEncodeSubtitle)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertIs<PlannedSubtitle.Off>(fixture.controller.preparedPlan?.plannedSubtitle)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun duplicateSubtitleUnavailableWhileEncodeFallbackIsPendingDoesNotRetry() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        bufferedPositionMs = 10_000L,
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                fallbackGate.complete(Unit)
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun unknownExternalSubtitleMimeAutomaticallyRequestsEncodeOnce() =
        runPlayerViewModelTest {
            try {
                val externalStreams =
                    externalSubtitlePlaybackStreams().map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(codec = "mystery", deliveryUrl = "/Videos/item-1/subtitles/4.mystery")
                        } else {
                            stream
                        }
                    }
                val encodedStreams =
                    externalStreams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(externalStreams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = externalStreams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    "mystery",
                    fixture.repository.requests
                        .last()
                        .requestPolicy.forceEncodeSubtitle
                        ?.normalizedFormat,
                )
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.subtitleRenderInfo.mode)
                assertTrue(content.subtitleNotice != null)
            } finally {
            }
        }

    @Test
    fun subtitleEncodeFallbackFailureKeepsVideoPlayingAndMarksSubtitleUnavailable() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.failure(IllegalStateException("encode unavailable")),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()
                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(PlaybackStatus.Playing, fixture.controller.playbackStateFlow.value.status)
                assertTrue(content.subtitleNotice != null)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun subtitleFallbackRejectsNonEncodeAndWrongStreamResponses() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val wrongStream =
                    transcodePlaybackInfoWithStreams(
                        streams.map { stream ->
                            if (stream.index == 4) stream.copy(index = 5) else stream
                        },
                    ).let { info ->
                        info.copy(
                            mediaSources = info.mediaSources.map { source -> source.copy(defaultSubtitleStreamIndex = 5) },
                        )
                    }
                val fallbackResponses =
                    listOf(
                        playbackInfoWithStreams(streams),
                        wrongStream,
                    )

                fallbackResponses.forEach { fallbackResponse ->
                    val fixture =
                        playerFixture(
                            playbackInfoResults =
                                ArrayDeque(
                                    listOf(
                                        Result.success(playbackInfoWithStreams(streams)),
                                        Result.success(fallbackResponse),
                                    ),
                                ),
                            mediaStreams = streams,
                            initialSubtitleStreamIndex = 4,
                        )
                    runCurrent()
                    val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Paused, positionMs = 8_000L).copy(
                            subtitleActivation = SubtitleActivationState.Unavailable(target),
                        )
                    runCurrent()

                    val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                    assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                    assertEquals(1, fixture.controller.prepareCount)
                    assertEquals(PlaybackStatus.Paused, fixture.controller.playbackStateFlow.value.status)
                    fixture.viewModel.dispose()
                    runCurrent()
                }
            } finally {
            }
        }

    @Test
    fun stopInvalidatesSuspendedSubtitleFallbackBeforeItCanInstallOrResume() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()
                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.stop()
                fallbackGate.complete(Unit)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.controller.playCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun externalSubtitleTransitionsReplanWithoutServerSubtitleIndex() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 25_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                val externalRequest = fixture.repository.requests.last()
                assertEquals(4, externalRequest.subtitleStreamIndex)
                assertEquals(250_000_000L, externalRequest.startTimeTicks)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(
                    LocalSubtitleKind.ExternalText,
                    fixture.controller.preparedPlan
                        ?.subtitleActivationTarget
                        ?.kind,
                )
                assertEquals(
                    "https://jellyfin.example/Videos/item-1/subtitles/4.srt",
                    fixture.controller.preparedExternalSubtitle?.url,
                )
                assertEquals(
                    SubtitleRenderStatus.Active,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 27_000L)
                runCurrent()
                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    270_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(3, fixture.controller.prepareCount)
                assertEquals(null, fixture.controller.preparedPlan?.subtitleActivationTarget)
                assertEquals(null, fixture.controller.preparedExternalSubtitle)
                assertEquals(
                    SubtitleRenderStatus.Off,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )
                assertEquals(
                    2,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )
            } finally {
            }
        }

    @Test
    fun explicitSubtitleOffSuppressesDefaultSubtitleAtPlaybackStart() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val memory =
                    PlaybackSelectionMemory().also { selectionMemory ->
                        selectionMemory.remember(
                            accountIdentity = session.accountIdentity(),
                            itemId = "item-1",
                            selection =
                                PlaybackSelection(audioStreamIndex = null),
                        )
                    }
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                        memory = memory,
                        initialSubtitleStreamIndex = -1,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(-1, request.subtitleStreamIndex)
                assertEquals(null, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(listOf<Int?>(null), fixture.controller.embeddedTextOrdinals)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun initialTrackIndicesOverrideRememberedAndPreferredDefaults() =
        runPlayerViewModelTest {
            try {
                val memory =
                    PlaybackSelectionMemory().also { selectionMemory ->
                        selectionMemory.remember(
                            accountIdentity = session.accountIdentity(),
                            itemId = "item-1",
                            selection =
                                PlaybackSelection(audioStreamIndex = 1),
                        )
                    }
                val fixture =
                    playerFixture(
                        initialAudioStreamIndex = 3,
                        initialSubtitleStreamIndex = 4,
                        memory = memory,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    preferredAudioLanguage = "eng",
                                    preferredSubtitleLanguage = "eng",
                                ),
                            ),
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(3, request.audioStreamIndex)
                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(3, content.selectedAudioStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun completedPlaylistItemWaitsForUpNextActionBeforeAdvancing() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(0, playbackEndedEvents.size)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    PlaybackStatus.Completed,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackState.status,
                )
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext?.itemId,
                )

                fixture.viewModel.playNext(auto = true)
                runCurrent()

                assertEquals(
                    "item-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
                assertEquals(
                    0L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    1,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )
            } finally {
            }
        }

    @Test
    fun autoAdvanceWithStaleGenerationIsIgnored() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                runCurrent()

                val generation =
                    (fixture.viewModel.state.value as PlayerUiState.Content)
                        .autoplayPolicy.playbackGeneration

                // A stale countdown carrying an older generation must not advance.
                fixture.viewModel.playNext(auto = true, expectedGeneration = generation + 1L)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    0,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )

                // The matching generation advances normally.
                fixture.viewModel.playNext(auto = true, expectedGeneration = generation)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    "item-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
                assertEquals(
                    1,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playlist?.currentIndex,
                )
            } finally {
            }
        }

    @Test
    fun playNextReportsWhetherTheQueueSwitchWasAccepted() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                runCurrent()

                // Accepted: a next item exists. The UI dismisses Up Next on this.
                assertTrue(fixture.viewModel.playNext())
                runCurrent()

                // Refused: the queue is exhausted, so the card must stay on screen.
                assertFalse(fixture.viewModel.playNext())
            } finally {
            }
        }

    @Test
    fun playNextReportsRefusalForASingleItemQueue() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1"))
                runCurrent()

                assertFalse(fixture.viewModel.playNext())
                assertEquals(1, fixture.controller.prepareCount)
            } finally {
            }
        }

    @Test
    fun playNextReportsRefusalForAStaleAutoAdvanceGeneration() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                runCurrent()

                val generation =
                    (fixture.viewModel.state.value as PlayerUiState.Content)
                        .autoplayPolicy.playbackGeneration

                assertFalse(
                    fixture.viewModel.playNext(auto = true, expectedGeneration = generation + 1L),
                )
                runCurrent()

                assertTrue(
                    fixture.viewModel.playNext(auto = true, expectedGeneration = generation),
                )
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playNextReportsRefusalWhenTheStillWatchingGateTakesOver() =
        runPlayerViewModelTest {
            try {
                val queue = (1..STILL_WATCHING_THRESHOLD + 2).map { index -> "item-$index" }
                val fixture = playerFixture(queue = queue)
                runCurrent()

                // Auto-advance until the gate arms; the advance that raises the
                // prompt is a refusal, because the prompt owns the continuation.
                var refusedAt = -1
                repeat(STILL_WATCHING_THRESHOLD) { attempt ->
                    val accepted = fixture.viewModel.playNext(auto = true)
                    runCurrent()
                    if (!accepted && refusedAt < 0) {
                        refusedAt = attempt
                    }
                }

                assertEquals(STILL_WATCHING_THRESHOLD - 1, refusedAt)
                assertTrue(
                    (fixture.viewModel.state.value as PlayerUiState.Content).stillWatchingPrompt,
                )
            } finally {
            }
        }

    @Test
    fun completedLastPlaylistItemEmitsPlaybackEndedAndKeepsFinishState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                fixture.viewModel.playQueueItem(1)
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 6_000L)
                runCurrent()

                assertEquals(1, playbackEndedEvents.size)
                assertEquals(
                    PlaybackStatus.Completed,
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackState.status,
                )
            } finally {
            }
        }

    @Test
    fun playQueueItemReportsStopThenStartsSelectedId() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()

                fixture.viewModel.playQueueItem(2)
                runCurrent()

                assertEquals(Report.Stopped(positionMs = 4_000L), fixture.reporter.reports.last())
                assertEquals(
                    "item-3",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
                assertEquals(
                    0L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(2, fixture.controller.playCount)
            } finally {
            }
        }

    @Test
    fun queueSwitchPublishesUnstableIdentitySynchronouslyThenNewIdentityAfterPlanning() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2", "item-3"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    "item-1",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )

                fixture.viewModel.playQueueItem(2)
                // BEFORE the switch coroutine runs: identity is already
                // unstable, so a pending hold-to-seek session cancels instead
                // of racing the next item's plan install.
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                runCurrent()
                assertEquals(
                    "item-3",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
            }
        }

    @Test
    fun playNextPublishesUnstableIdentitySynchronously() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(queue = listOf("item-1", "item-2"))
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                fixture.viewModel.playNext()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                runCurrent()
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
            }
        }

    @Test
    fun queueMetadataIsReorderedToQueueOrder() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems =
                            listOf(
                                mediaItem("item-3", "Third"),
                                mediaItem("item-1", "First"),
                                mediaItem(
                                    id = "item-2",
                                    name = "Second",
                                    primaryTag = "primary-2",
                                    seasonNumber = 2,
                                    episodeNumber = 8,
                                ),
                            ),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals(
                    queue,
                    fixture.repository.itemIdRequests.single(),
                )
                assertEquals(queue, playlist?.items?.map { item -> item.id })
                assertEquals(listOf("First", "Second", "Third"), playlist?.items?.map { item -> item.title })
                assertEquals(
                    "https://jellyfin.example/Items/item-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                    playlist?.items?.first { item -> item.id == "item-2" }?.imageUrl,
                )
                assertEquals(2, playlist?.items?.first { item -> item.id == "item-2" }?.seasonNumber)
                assertEquals(8, playlist?.items?.first { item -> item.id == "item-2" }?.episodeNumber)
            } finally {
            }
        }

    @Test
    fun queueMetadataFallsBackToItemDetailForBatchMisses() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems = listOf(mediaItem("item-1", "First")),
                        detailItems =
                            mapOf(
                                "item-2" to mediaItem("item-2", "Second", primaryTag = "primary-2"),
                                "item-3" to mediaItem("item-3", "Third", primaryTag = "primary-3"),
                            ),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals(listOf("First", "Second", "Third"), playlist?.items?.map { item -> item.title })
                assertEquals(
                    "https://jellyfin.example/Items/item-3/Images/Primary?tag=primary-3&maxWidth=300&quality=90",
                    playlist?.items?.first { item -> item.id == "item-3" }?.imageUrl,
                )
            } finally {
            }
        }

    @Test
    fun unresolvableQueueMetadataDoesNotExposeRawIdAsTitle() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems = listOf(mediaItem("item-1", "First")),
                        detailFailures = setOf("item-2"),
                    )
                runCurrent()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals("", playlist?.items?.first { item -> item.id == "item-2" }?.title)
            } finally {
            }
        }

    @Test
    fun shuffleQueueKeepsCurrentItemFirstAndPreservesQueueItems() =
        runPlayerViewModelTest {
            try {
                val queue = listOf("item-1", "item-2", "item-3", "item-4")
                val fixture =
                    playerFixture(
                        queue = queue,
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    )
                runCurrent()

                fixture.viewModel.playQueueItem(2)
                runCurrent()
                fixture.viewModel.shuffleQueue()

                val playlist = (fixture.viewModel.state.value as PlayerUiState.Content).playlist
                assertEquals("item-3", playlist?.items?.firstOrNull()?.id)
                assertEquals(0, playlist?.currentIndex)
                assertEquals(queue.toSet(), playlist?.items?.map { item -> item.id }?.toSet())
                assertEquals(queue.size, playlist?.items?.size)
            } finally {
            }
        }

    @Test
    fun exposesPlaybackExtensionsInStateAndPreparedPlan() =
        runPlayerViewModelTest {
            try {
                val segments =
                    listOf(
                        MediaSegment(MediaSegmentType.Intro, startTicks = 10_000_000L, endTicks = 30_000_000L),
                    )
                val chapters = listOf(Chapter(name = "Opening", startTicks = 0L))
                val trickplay =
                    TrickplayInfo(
                        resolutionKey = "320",
                        width = 320,
                        height = 180,
                        tileWidth = 10,
                        tileHeight = 10,
                        thumbnailWidth = 320,
                        thumbnailHeight = 180,
                        thumbnailCount = 120,
                        intervalMs = 10_000L,
                    )
                val fixture =
                    playerFixture(
                        mediaSegments = segments,
                        chapters = chapters,
                        trickplay = trickplay,
                    )
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(chapters, content.chapters)
                assertEquals(segments, content.mediaSegments)
                assertEquals(trickplay, content.trickplay)
                assertEquals(
                    listOf(
                        "https://jellyfin.example/Videos/item-1/Trickplay/320/0.jpg",
                        "https://jellyfin.example/Videos/item-1/Trickplay/320/1.jpg",
                    ),
                    content.trickplayTileUrls,
                )
                assertEquals(chapters, fixture.controller.preparedPlan?.chapters)
                assertEquals(segments, fixture.controller.preparedPlan?.mediaSegments)
                assertEquals(trickplay, fixture.controller.preparedPlan?.trickplay)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()

                assertEquals(
                    MediaSegmentType.Intro,
                    fixture.viewModel.playbackState.value.currentSegment
                        ?.type,
                )
                assertEquals(
                    MediaSegmentType.Intro,
                    (fixture.viewModel.state.value as PlayerUiState.Content).currentSegment?.type,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playbackSpeedAndSubtitleStyleUpdateControllerAndState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()
                val style = SubtitleStyle(fontScale = 1.25f, edgeStyle = SubtitleEdgeStyle.Outline)

                fixture.viewModel.setPlaybackSpeed(9f)
                fixture.viewModel.setSubtitleStyle(style)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(MAX_PLAYBACK_SPEED, fixture.controller.appliedPlaybackSpeed)
                assertEquals(MAX_PLAYBACK_SPEED, content.playbackSpeed)
                assertEquals(MAX_PLAYBACK_SPEED, fixture.viewModel.playbackState.value.playbackSpeed)
                assertEquals(style, fixture.controller.appliedSubtitleStyle)
                assertEquals(style, content.subtitleStyle)
                assertEquals(style, fixture.viewModel.playbackState.value.subtitleStyle)
            } finally {
            }
        }

    @Test
    fun cycleResizeModePreservesAllSupportedStateTransitions() =
        runPlayerViewModelTest {
            val cases =
                listOf(
                    false to listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill, PlayerResizeMode.Zoom, PlayerResizeMode.Fit),
                    true to listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill, PlayerResizeMode.Fit),
                )
            cases.forEach { (twoState, expectedModes) ->
                var fixtureRef: PlayerFixture? = null
                try {
                    val fixture = playerFixture().also { fixtureRef = it }
                    runCurrent()
                    expectedModes.drop(1).forEach { expectedMode ->
                        fixture.viewModel.cycleResizeMode(twoState = twoState)
                        assertEquals(
                            expectedMode,
                            (fixture.viewModel.state.value as PlayerUiState.Content).resizeMode,
                        )
                    }
                } finally {
                    fixtureRef?.viewModel?.dispose()
                    runCurrent()
                }
            }
        }

    @Test
    fun upNextUsesOutroWindowAndQueueMetadata() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second", primaryTag = "primary-2"),
                            ),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Outro,
                                    startTicks = 20_000_000L,
                                    endTicks = 55_000_000L,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_900L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "item-2",
                        title = "Second",
                        imageUrl = "https://jellyfin.example/Items/item-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                        index = 1,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun upNextUsesThresholdWindowAndClearsOutsideOrWithoutNextItem() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            var noNextFixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 29_999L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()
                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "item-2",
                        title = "Second",
                        imageUrl = null,
                        index = 1,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L)
                runCurrent()
                assertEquals<UpNextInfo?>(null, (fixture.viewModel.state.value as PlayerUiState.Content).upNext)

                val noNextFixture = playerFixture().also { noNextFixtureRef = it }
                runCurrent()
                noNextFixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(null, (noNextFixture.viewModel.state.value as PlayerUiState.Content).upNext)
            } finally {
                fixtureRef?.viewModel?.dispose()
                noNextFixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun singletonEpisodePlaybackDerivesUpNextQueueFromChronologicalEpisodes() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val currentEpisode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val nextEpisode =
                    mediaItem(
                        id = "episode-2",
                        name = "Episode 2",
                        primaryTag = "primary-2",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 2,
                    )
                val seasonTwoEpisode =
                    mediaItem(
                        id = "episode-3",
                        name = "Episode 3",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 2,
                        episodeNumber = 1,
                    )
                val fixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems =
                            mapOf(
                                "episode-1" to currentEpisode,
                                "episode-2" to nextEpisode,
                                "episode-3" to seasonTwoEpisode,
                            ),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                                mediaItem("season-2", "Season 2", kind = MediaKind.Other, episodeNumber = 2),
                            ),
                        episodesBySeasonId =
                            mapOf(
                                "season-1" to listOf(currentEpisode, nextEpisode),
                                "season-2" to listOf(seasonTwoEpisode),
                            ),
                    ).also { fixtureRef = it }
                advanceUntilIdle()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(listOf("series-1"), fixture.repository.seasonSeriesIds)
                assertEquals(
                    listOf(EpisodeRequest("series-1", "season-1", 1), EpisodeRequest("series-1", "season-2", 2)),
                    fixture.repository.episodeRequests,
                )
                assertEquals(
                    listOf("episode-1", "episode-2", "episode-3"),
                    content.playlist?.items?.map { item -> item.id },
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 30_000L)
                runCurrent()

                assertEquals<UpNextInfo?>(
                    UpNextInfo(
                        itemId = "episode-2",
                        title = "Episode 2",
                        imageUrl = "https://jellyfin.example/Items/episode-2/Images/Primary?tag=primary-2&maxWidth=300&quality=90",
                        index = 1,
                        // Carried through from the queue item so the Up Next card
                        // and queue rows can show the episode number.
                        seasonNumber = 1,
                        episodeNumber = 2,
                    ),
                    (fixture.viewModel.state.value as PlayerUiState.Content).upNext,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 60_000L)
                advanceUntilIdle()

                assertEquals(
                    "episode-1",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.playNext(auto = true)
                advanceUntilIdle()

                assertEquals(
                    "episode-2",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun explicitQueueIsNotOverriddenByChronologicalEpisodeDerivation() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val currentEpisode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val derivedEpisode =
                    mediaItem(
                        id = "episode-derived",
                        name = "Derived Episode",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 2,
                    )
                val fixture =
                    playerFixture(
                        itemId = "episode-1",
                        queue = listOf("episode-1", "episode-explicit"),
                        queueItems =
                            listOf(
                                mediaItem("episode-1", "Episode 1"),
                                mediaItem("episode-explicit", "Explicit Episode"),
                            ),
                        detailItems = mapOf("episode-1" to currentEpisode),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                            ),
                        episodesBySeasonId = mapOf("season-1" to listOf(currentEpisode, derivedEpisode)),
                    ).also { fixtureRef = it }
                advanceUntilIdle()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(emptyList<String>(), fixture.repository.seasonSeriesIds)
                assertEquals(emptyList<EpisodeRequest>(), fixture.repository.episodeRequests)
                assertEquals(listOf("episode-1", "episode-explicit"), content.playlist?.items?.map { item -> item.id })
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun emptyOrFailingChronologicalEpisodeDerivationLeavesSingletonEpisodeWithoutPlaylist() =
        runPlayerViewModelTest {
            var emptyFixtureRef: PlayerFixture? = null
            var lastEpisodeFixtureRef: PlayerFixture? = null
            var failingFixtureRef: PlayerFixture? = null
            try {
                val episode =
                    mediaItem(
                        id = "episode-1",
                        name = "Episode 1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    )
                val emptyFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                    ).also { emptyFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (emptyFixture.viewModel.state.value as PlayerUiState.Content).playlist)

                val lastEpisodeFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                        seasons =
                            listOf(
                                mediaItem("season-1", "Season 1", kind = MediaKind.Other, episodeNumber = 1),
                            ),
                        episodesBySeasonId = mapOf("season-1" to listOf(episode)),
                    ).also { lastEpisodeFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (lastEpisodeFixture.viewModel.state.value as PlayerUiState.Content).playlist)

                val failingFixture =
                    playerFixture(
                        itemId = "episode-1",
                        detailItems = mapOf("episode-1" to episode),
                        seasonsFailure = true,
                    ).also { failingFixtureRef = it }
                advanceUntilIdle()

                assertEquals(null, (failingFixture.viewModel.state.value as PlayerUiState.Content).playlist)
            } finally {
                emptyFixtureRef?.viewModel?.dispose()
                lastEpisodeFixtureRef?.viewModel?.dispose()
                failingFixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun skipCurrentSegmentSeeksToSegmentEnd() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()

                fixture.viewModel.skipCurrentSegment()
                runCurrent()

                assertEquals(3_000L, fixture.controller.seekPositions.single())
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun autoSkipPolicyFiresOncePerSegmentWhilePlayingAndNeverWhilePaused() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                // Paused inside the segment: no auto-skip.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 1_500L)
                runCurrent()
                assertTrue(fixture.controller.seekPositions.isEmpty())

                // Playing inside (resume-into-segment): fires exactly once.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                assertEquals(listOf(3_000L), fixture.controller.seekPositions)

                // Still inside on the next tick: no repeat.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_600L)
                runCurrent()
                // Seeking back into the already-skipped segment: no re-fire.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()
                assertEquals(1, fixture.controller.seekPositions.size)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun skipPromptSegmentFollowsPerTypePolicy() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val outroSegment =
                    MediaSegment(
                        type = MediaSegmentType.Outro,
                        startTicks = 50_000_000L,
                        endTicks = 70_000_000L,
                    )
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                                outroSegment,
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.Ignore),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                // Ignored type: the segment is current but never prompts.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                val ignored = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(MediaSegmentType.Intro, ignored.currentSegment?.type)
                assertNull(ignored.skipPromptSegment)
                assertTrue(fixture.controller.seekPositions.isEmpty())

                // Default Ask type keeps prompting as today.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_500L)
                runCurrent()
                val asked = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(outroSegment, asked.skipPromptSegment)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun autoSkipSessionMemoryResetsOnQueueSwitch() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 10_000_000L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()
                assertEquals(listOf(3_000L), fixture.controller.seekPositions)

                fixture.viewModel.playQueueItem(1)
                runCurrent()

                // The same-shaped segment on the next item auto-skips again:
                // the once-per-session memory cleared on the new playback start.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_450L)
                runCurrent()
                assertEquals(listOf(3_000L, 3_000L), fixture.controller.seekPositions)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun interimControllerEmissionsDuringQueueSwitchCannotRepublishTheOutgoingIdentity() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            // A separate scheduler keeps the switch's planning suspended while
            // the main dispatcher processes interim controller emissions (an
            // explicit scheduler: dispatchers created after setMain would
            // otherwise inherit Main's scheduler and run eagerly).
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            // Planning hops between the two dispatchers several times, so a
            // single alternation leaves it mid-flight.
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3"),
                        workDispatcher = workDispatcher,
                    ).also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    "item-1",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )

                fixture.viewModel.playQueueItem(2)
                runCurrent()
                // The stop() analog: an interim Idle emission arrives while
                // planning is suspended. Identity must stay null.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Idle)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId)

                drainPlayerViewModelSchedulers(workDispatcher)
                assertEquals(
                    "item-3",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun queueSwitchCancelsAnInFlightReplanSoItCannotInstallAStalePlan() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        workDispatcher = workDispatcher,
                    ).also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L)
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount

                // The quality replan suspends on the work scheduler...
                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()
                // ...and the queue switch begins before it completes. The
                // stale replan targets the OUTGOING item and must be
                // cancelled, not left to install its plan mid-switch.
                fixture.viewModel.playQueueItem(1)
                drainPlayerViewModelSchedulers(workDispatcher)

                assertEquals(prepareCountBefore + 1, fixture.controller.prepareCount)
                assertEquals(
                    "item-2",
                    (fixture.viewModel.state.value as PlayerUiState.Content).playbackItemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun returnedMediaSourceOwnsTheBoundedTimelineInsteadOfTheRequestedSourceOrItemFallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val detailItem =
                    MediaItem(
                        id = "item-1",
                        name = "Item",
                        kind = MediaKind.Movie,
                        runtime = 120.seconds,
                        versions =
                            listOf(
                                MediaVersion(
                                    id = "source-1",
                                    name = "Requested",
                                    mediaStreams = playbackStreams,
                                    runtime = 60.seconds,
                                ),
                                MediaVersion(
                                    id = "source-2",
                                    name = "Resolved",
                                    mediaStreams = playbackStreams,
                                    runtime = 90.seconds,
                                ),
                            ),
                    )
                val resolvedPlaybackInfo =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            listOf(
                                directPlayPlaybackInfo.mediaSources.single().copy(id = "source-2"),
                            ),
                    )
                val fixture =
                    playerFixture(
                        playbackInfo = resolvedPlaybackInfo,
                        detailItems = mapOf("item-1" to detailItem),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                val timeline = assertIs<PlaybackContentTimeline.BoundedVod>(fixture.controller.preparedPlan?.contentTimeline)
                assertEquals(90.seconds.inWholeMilliseconds, timeline.durationMs)
                assertEquals(PlaybackContentTimelineSource.SelectedMediaSource, timeline.source)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sameItemRetryCancelsAReplanStartedUnderTheSupersededGeneration() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(workDispatcher = workDispatcher)
                        .also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                val prepareCountBeforeRetry = fixture.controller.prepareCount

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()
                fixture.viewModel.retry()
                runCurrent()
                drainPlayerViewModelSchedulers(workDispatcher)

                assertEquals(1, fixture.controller.retryCount)
                assertEquals(prepareCountBeforeRetry, fixture.controller.prepareCount)
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun autoSkipAtSessionStartReportsStartBeforeTheSkipTimeUpdate() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Intro,
                                    startTicks = 0L,
                                    endTicks = 30_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(introSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()

                // The very first reportable state is Playing inside the auto
                // segment: Start must be established (at the true pre-skip
                // position) before the skip's TimeUpdate serializes.
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 500L)
                runCurrent()

                assertEquals(listOf(3_000L), fixture.controller.seekPositions)
                val start =
                    fixture.reporter.reports
                        .filterIsInstance<Report.Start>()
                        .first()
                assertEquals(500L, start.positionMs)
                val startIndex = fixture.reporter.reports.indexOf(start)
                val skipUpdateIndex =
                    fixture.reporter.reports.indexOfFirst { report ->
                        report is Report.Progress && report.positionMs == 3_000L
                    }
                assertTrue(
                    skipUpdateIndex > startIndex,
                    "skip TimeUpdate must serialize after Start: ${fixture.reporter.reports}",
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun autoSkippedOutroSeeksInItemWithoutAdvancingTheQueue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2"),
                        mediaSegments =
                            listOf(
                                MediaSegment(
                                    type = MediaSegmentType.Outro,
                                    startTicks = 50_000_000L,
                                    endTicks = 70_000_000L,
                                ),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(outroSkip = SegmentSkipPolicy.AutoSkip),
                            ),
                        workDispatcher = dispatcher,
                    ).also { fixtureRef = it }
                runCurrent()
                val requestsBefore = fixture.repository.requests.size
                val playsBefore = fixture.controller.playCount

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 5_500L)
                runCurrent()

                // Auto-skip is a plain in-item seek: queue advancement stays
                // owned by the normal Completed path and the Still Watching gate.
                assertEquals(listOf(7_000L), fixture.controller.seekPositions)
                assertEquals(requestsBefore, fixture.repository.requests.size)
                assertEquals(playsBefore, fixture.controller.playCount)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stillWatchingPromptBlocksThirdAutoAdvanceAndConfirmContinues() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()

                val promptContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(true, promptContent.stillWatchingPrompt)
                assertEquals(
                    "item-3",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.confirmStillWatching()
                runCurrent()

                val confirmedContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(false, confirmedContent.stillWatchingPrompt)
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun stillWatchingPromptNeverInterruptsWhenThePreferenceIsOff() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(stillWatchingPrompt = false),
                            ),
                    ).also { fixtureRef = it }
                runCurrent()

                // Three consecutive automatic advances: the same count that trips the
                // gate when the preference is on must sail straight through here.
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()
                fixture.viewModel.playNext(auto = true)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(false, content.stillWatchingPrompt)
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun subtitleStyleIsNotOfferedWhenTheBackendCannotApplyIt() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture = playerFixture(appliesSubtitleStyle = false).also { fixtureRef = it }
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                // Whatever the domain decides about the track, a backend that cannot
                // apply appearance must never be offered the control.
                assertEquals(false, content.subtitleStyleable)
                assertEquals(false, content.debugInfo?.subtitleStyleable)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun naturalCompletionKeepsPlayerOpenForStillWatchingAndEndsAfterConfirmation() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(
                        queue = listOf("item-1", "item-2", "item-3", "item-4"),
                        queueItems =
                            listOf(
                                mediaItem("item-1", "First"),
                                mediaItem("item-2", "Second"),
                                mediaItem("item-3", "Third"),
                                mediaItem("item-4", "Fourth"),
                            ),
                    ).also { fixtureRef = it }
                val playbackEndedEvents = mutableListOf<Unit>()
                backgroundScope.launch {
                    fixture.viewModel.playbackEnded.collect {
                        playbackEndedEvents += Unit
                    }
                }
                runCurrent()

                repeat(3) { index ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = index * 1_000L)
                    runCurrent()
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Completed, positionMs = (index + 1) * 1_000L)
                    runCurrent()
                    fixture.viewModel.playNext(auto = true)
                    runCurrent()
                }

                val promptContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(true, promptContent.stillWatchingPrompt)
                assertEquals(PlaybackStatus.Completed, promptContent.playbackState.status)
                assertEquals(0, playbackEndedEvents.size)
                assertEquals(
                    "item-3",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.viewModel.confirmStillWatching()
                runCurrent()
                assertEquals(
                    "item-4",
                    fixture.repository.requests
                        .last()
                        .itemId,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Completed, positionMs = 5_000L)
                runCurrent()

                assertEquals(1, playbackEndedEvents.size)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun localSubtitleUsesLocalAssetAndNeverStartsEncodeFallback() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val asset = localSubtitleAsset()
                val fixture =
                    playerFixture(
                        initialSubtitleSelection = SubtitleSelectionIntent.LocalAsset(asset.id),
                        localSubtitleAsset = asset,
                    ).also { fixtureRef = it }
                runCurrent()

                assertEquals(
                    -1,
                    fixture.repository.requests
                        .single()
                        .subtitleStreamIndex,
                )
                assertIs<SubtitleAsset.LocalFile>(fixture.controller.preparedSubtitleAsset)
                assertIs<PlannedSubtitle.LocalAsset>(fixture.controller.preparedPlan?.plannedSubtitle)
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(asset.id, content.selectedSubtitleAssetId)

                val target = requireNotNull(fixture.controller.preparedPlan?.subtitleActivationTarget)
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                assertEquals(1, fixture.repository.requests.size)
                assertEquals(asset.id, (fixture.viewModel.state.value as PlayerUiState.Content).selectedSubtitleAssetId)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun selectingAndDisablingInstalledLocalSubtitleReplansWithoutCancellingItself() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val asset = localSubtitleAsset()
                val fixture = playerFixture(localSubtitleAsset = asset).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.selectLocalSubtitle(asset.id)
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertIs<SubtitleAsset.LocalFile>(fixture.controller.preparedSubtitleAsset)

                fixture.viewModel.selectSubtitle(null)
                runCurrent()

                assertEquals(3, fixture.repository.requests.size)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(null, fixture.controller.preparedSubtitleAsset)
                assertEquals(null, fixture.controller.embeddedTextOrdinals.last())
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }
}

private fun localSubtitleAsset() =
    LocalSubtitleAsset(
        id = "asset-1",
        serverId = session.serverId,
        userId = session.userId,
        itemId = "item-1",
        mediaSourceId = "source-1",
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle-1",
        providerFileId = "file-1",
        language = "en",
        label = "English download",
        releaseName = "Movie.1080p",
        originalFormat = "srt",
        mimeType = "text/vtt",
        fileId = "asset-1.vtt",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        createdAtEpochMs = 1L,
        lastUsedAtEpochMs = 1L,
        syncState = LocalSubtitleSyncState.Pending,
    )

private class FakeLogCollectionPreferenceStore(
    playbackInfoAtStart: Boolean,
) : LogCollectionPreferenceStore {
    override val enabled = MutableStateFlow(false)
    override val verboseLogcatEnabled = MutableStateFlow(false)
    override val playbackInfoAtStartEnabled = MutableStateFlow(playbackInfoAtStart)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        playbackInfoAtStartEnabled.value = enabled
    }
}

private fun offlineRecord(
    downloadId: DownloadId,
    artifactKind: DownloadArtifactKind = DownloadArtifactKind.OriginalFile,
): DownloadRecord {
    val quality =
        if (artifactKind == DownloadArtifactKind.LocalHlsPackage) {
            DownloadQuality.Fixed(maxBitrateBps = 2_000_000L)
        } else {
            DownloadQuality.Original
        }
    val request =
        DownloadRequest(
            downloadId = downloadId,
            businessKey =
                DownloadBusinessKey(
                    session.accountIdentity(),
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                ),
            quality = quality,
            artifactKind = artifactKind,
            selectedAudioStreamIndex = null,
            subtitleSelection = com.jellyscope.core.domain.model.DownloadSubtitleSelection.Off,
            admissionEstimateBytes = 100L,
            initialReservationBytes = 100L,
            expectedSourceBytes = 100L.takeIf { artifactKind == DownloadArtifactKind.OriginalFile },
            artifactKey = DownloadArtifactKey("offline-artifact"),
            snapshot =
                OfflineMediaSnapshot(
                    title = "Offline item",
                    itemKind = MediaKind.Movie,
                    durationMs = 60_000L,
                    sourcePresentation = "Original",
                    backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                ),
            createdAtEpochMs = 0L,
        )
    return DownloadRecord(
        request = request,
        fifoSequence = 1L,
        state = DownloadState.Completed,
        reservationBytes = 100L,
        physicalBytes = 100L,
        checkpointBytes = 100L,
        attemptGeneration = 1L,
        localResumePositionMs = 1_000L,
        updatedAtEpochMs = 1L,
    )
}

private fun appleOfflineProfileProvider(
    supportedBackends: Set<PlayerBackend> = setOf(PlayerBackend.AVPlayer, PlayerBackend.VlcKit),
): DeviceProfileProvider =
    object : DeviceProfileProvider {
        override val backendPolicy = applePlayerBackendPolicy()
        override val availableBackends: Set<PlayerBackend> = supportedBackends

        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
            )
    }

private class OfflinePlaybackDownloadRepository(
    private val record: DownloadRecord,
) : DownloadRepository {
    var getDownloadCalls = 0
    var hasCompletedArtifactCalls = 0

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = flowOf(listOf(record))

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? {
        getDownloadCalls += 1
        return record.takeIf {
            it.downloadId == downloadId && it.businessKey.accountIdentity == accountIdentity
        }
    }

    override suspend fun hasCompletedArtifact(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): Boolean {
        hasCompletedArtifactCalls += 1
        return record.businessKey.accountIdentity == accountIdentity &&
            record.downloadId == downloadId &&
            record.attemptGeneration == attemptGeneration
    }

    override suspend fun isArtifactLeased(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): Boolean = false

    override suspend fun getDownloadSettings(): DownloadSettings =
        DownloadSettings(quotaBytes = null, nextFifoSequence = 2L, membershipRevision = 0L)

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage =
        DownloadUsage(
            physicalBytes = record.physicalBytes,
            currentAccountPhysicalBytes = record.physicalBytes,
            otherAccountsPhysicalBytes = 0L,
            outstandingReservationBytes = 0L,
            projectedCommittedBytes = record.physicalBytes,
            quotaBytes = null,
            remainingQuotaBytes = null,
            deviceAvailableBytes = Long.MAX_VALUE,
            safetyReserveBytes = 0L,
            maximumConfigurableQuotaBytes = Long.MAX_VALUE,
            overAllocation = false,
        )

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings = getDownloadSettings()

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
    ): Boolean = true
}

private fun runPlayerViewModelTest(block: suspend TestScope.() -> Unit) {
    runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private fun TestScope.drainPlayerViewModelSchedulers(workDispatcher: TestDispatcher) {
    repeat(4) {
        runCurrent()
        workDispatcher.scheduler.advanceUntilIdle()
    }
    runCurrent()
}

private fun playerFixture(
    itemId: String = "item-1",
    playbackInfo: PlaybackInfo = directPlayPlaybackInfo,
    playbackInfoResults: ArrayDeque<Result<PlaybackInfo>> = ArrayDeque(),
    mediaStreams: List<PlaybackMediaStream> = playbackStreams,
    initialAudioStreamIndex: Int? = null,
    initialSubtitleStreamIndex: Int? = null,
    initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.fromWireIndex(initialSubtitleStreamIndex),
    localSubtitleAsset: LocalSubtitleAsset? = null,
    memory: PlaybackSelectionMemory = PlaybackSelectionMemory(),
    selectionStore: FakePlaybackSelectionStore? = null,
    queue: List<String> = emptyList(),
    queueItems: List<MediaItem> = emptyList(),
    seasons: List<MediaItem> = emptyList(),
    episodesBySeasonId: Map<String, List<MediaItem>> = emptyMap(),
    seasonsFailure: Boolean = false,
    episodeFailures: Set<String> = emptySet(),
    detailItems: Map<String, MediaItem> = emptyMap(),
    detailFailures: Set<String> = emptySet(),
    mediaSegments: List<MediaSegment> = emptyList(),
    chapters: List<Chapter> = emptyList(),
    trickplay: TrickplayInfo? = null,
    playbackPreferencesStore: PlaybackPreferencesStore? = null,
    playerDeviceSettingsStore: PlayerDeviceSettingsStore = FakePlayerDeviceSettingsStore(),
    confirmInitialAudio: Boolean = true,
    // Default to the installed Main (a StandardTestDispatcher via setMain) so
    // off-main work (withContext/async(workDispatcher)) is driven by runCurrent().
    workDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
    fallbackRequestGate: CompletableDeferred<Unit>? = null,
    hasReliableBufferingTransitions: Boolean = false,
    appliesSubtitleStyle: Boolean = true,
    hasDroppedFrameMeasurements: Boolean = false,
    hasReliableFirstVideoOutput: Boolean = false,
    playbackInfoAtStartEnabled: Boolean = false,
    videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.Unsupported,
    guidancePolicy: TestGuidancePolicy = TestGuidancePolicy.Actionable,
    activeBackend: PlayerBackend = PlayerBackend.Auto,
    playerControllerFactory: ((PlayerBackend) -> PlayerController)? = null,
    offlineDownloadId: DownloadId? = null,
    getOfflinePlaybackPlanUseCase: GetOfflinePlaybackPlanUseCase? = null,
    monotonicTimeMs: (() -> Long)? = null,
    publishPrepareEpoch: Boolean = false,
    volumeController: FakeVolumePlayerController? = null,
    deviceProfileProvider: DeviceProfileProvider? = null,
    playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
): PlayerFixture {
    val monotonicOrigin =
        kotlin.time.TimeSource.Monotonic
            .markNow()
    val coordinatorClock =
        monotonicTimeMs ?: {
            monotonicOrigin.elapsedNow().inWholeMilliseconds
        }
    val controller = volumeController ?: FakePlayerController(confirmInitialAudio, publishPrepareEpoch)
    controller.appliesSubtitleStyle = appliesSubtitleStyle
    controller.videoOutputMeasurementCapabilities = videoOutputMeasurementCapabilities
    controller.playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = hasReliableBufferingTransitions,
            hasDroppedFrameMeasurements = hasDroppedFrameMeasurements,
            hasReliableFirstVideoOutput = hasReliableFirstVideoOutput,
        )
    controller.activeBackend = activeBackend
    val reporter = FakePlaybackProgressReporter()
    val repository =
        FakeMediaRepository(
            playbackInfo = playbackInfo,
            playbackInfoResults = playbackInfoResults,
            queueItems = queueItems,
            seasons = seasons,
            episodesBySeasonId = episodesBySeasonId,
            seasonsFailure = seasonsFailure,
            episodeFailures = episodeFailures,
            detailItems = detailItems,
            detailFailures = detailFailures,
            mediaSegments = mediaSegments,
            chapters = chapters,
            trickplay = trickplay,
            mediaStreams = mediaStreams,
            fallbackRequestGate = fallbackRequestGate,
        )
    val viewModel =
        PlayerViewModel(
            session = session,
            itemId = itemId,
            startPositionTicks = 10_000_000L,
            mediaSourceId = "source-1",
            initialAudioStreamIndex = initialAudioStreamIndex,
            initialSubtitleSelection = initialSubtitleSelection,
            queue = queue,
            playerController = controller,
            playbackInfoPlanner =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider = deviceProfileProvider,
                ),
            getPlaybackInfoAtStartStateUseCase =
                GetPlaybackInfoAtStartStateUseCase(FakeLogCollectionPreferenceStore(playbackInfoAtStartEnabled)),
            progressReporter = reporter,
            playbackStopSettlementRegistry = PlaybackStopSettlementRegistry(),
            getItemDetailUseCase = GetItemDetailUseCase(repository),
            getMediaSegmentsUseCase = GetMediaSegmentsUseCase(repository),
            getItemsByIdsUseCase = GetItemsByIdsUseCase(repository),
            getChronologicalEpisodeQueueUseCase =
                GetChronologicalEpisodeQueueUseCase(
                    getSeriesSeasonsUseCase = GetSeriesSeasonsUseCase(repository),
                    getSeasonEpisodesUseCase = GetSeasonEpisodesUseCase(repository),
                ),
            imageUrlBuilder = JellyfinImageUrlBuilder(),
            deviceInfoProvider = FakeDeviceInfoProvider(),
            playbackSelectionMemory = memory,
            getPlaybackLaunchContextUseCase =
                GetPlaybackLaunchContextUseCase(
                    getPlaybackPreferences = playbackPreferencesStore?.let { GetPlaybackPreferencesUseCase(it) },
                    getPlaybackSelection = selectionStore?.let { GetPlaybackSelectionUseCase(it) },
                ),
            savePlaybackSelectionAction =
                selectionStore?.let { store ->
                    SavePlaybackSelectionAction(store, CoroutineScope(SupervisorJob() + workDispatcher), workDispatcher)
                },
            observePlayerDeviceSettingsUseCase = ObservePlayerDeviceSettingsUseCase(playerDeviceSettingsStore),
            playerControllerFactory = playerControllerFactory ?: { controller },
            deviceProfileProvider = deviceProfileProvider,
            playbackDiagnosticsContext = playbackDiagnosticsContext,
            getLocalSubtitleAssetUseCase =
                localSubtitleAsset?.let { asset ->
                    GetLocalSubtitleAssetUseCase(
                        assetStore = FakeLocalSubtitleAssetStore(asset),
                        fileStore = FakeLocalSubtitleFileStore(asset.fileId),
                    )
                },
            workDispatcher = workDispatcher,
            monotonicTimeMs = coordinatorClock,
            playbackHealthGuidancePolicy = guidancePolicy.toPlaybackHealthGuidancePolicy(),
            offlineDownloadId = offlineDownloadId,
            getOfflinePlaybackPlanUseCase = getOfflinePlaybackPlanUseCase,
        )

    return PlayerFixture(
        viewModel = viewModel,
        controller = controller,
        reporter = reporter,
        repository = repository,
    )
}

private data class PlayerFixture(
    val viewModel: PlayerViewModel,
    val controller: FakePlayerController,
    val reporter: FakePlaybackProgressReporter,
    val repository: FakeMediaRepository,
)

private class RecordingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var running = false
        private set
    var dispatchCount = 0
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatchCount += 1
        delegate.dispatch(
            context,
            Runnable {
                val wasRunning = running
                running = true
                try {
                    block.run()
                } finally {
                    running = wasRunning
                }
            },
        )
    }
}

private class FakePlaybackSelectionStore : PlaybackSelectionStore {
    val values = mutableMapOf<PlaybackSelectionKey, PlaybackSelection>()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = values[key]

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        values[key] = selection
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        values.remove(key)
    }

    override suspend fun clearServerScoped() = values.clear()

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearAccount(accountIdentity.serverId, accountIdentity.userId)
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        values.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }
}

private class FakePlayerDeviceSettingsStore(
    initialSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
) : PlayerDeviceSettingsStore {
    private val settingsFlow = MutableStateFlow(initialSettings)

    override val settings: StateFlow<PlayerDeviceSettings> = settingsFlow

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        settingsFlow.value = settings
    }
}

private class FakeLocalSubtitleAssetStore(
    initial: LocalSubtitleAsset,
) : LocalSubtitleAssetStore {
    private var asset: LocalSubtitleAsset? = initial

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        flowOf(
            listOfNotNull(
                asset?.takeIf {
                    it.serverId == context.serverId &&
                        it.userId == context.userId &&
                        it.itemId == context.itemId &&
                        it.mediaSourceId == context.mediaSourceId
                },
            ),
        )

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = flowOf(emptyList())

    override suspend fun get(assetId: String): LocalSubtitleAsset? = asset?.takeIf { it.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? = asset?.takeIf { it.provider == provider && it.providerFileId == providerFileId }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        this.asset = asset
    }

    override suspend fun delete(assetId: String) {
        if (asset?.id == assetId) asset = null
    }

    override suspend fun all(): List<LocalSubtitleAsset> = listOfNotNull(asset)

    override suspend fun clearAll() {
        asset = null
    }
}

private class FakeLocalSubtitleFileStore(
    private val fileId: String,
) : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = "WEBVTT\n\n".encodeToByteArray().takeIf { fileId == this.fileId }

    override suspend fun exists(fileId: String): Boolean = fileId == this.fileId

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = setOf(fileId)

    override fun resolvePath(fileId: String): String? = "/tmp/$fileId".takeIf { fileId == this.fileId }
}

/**
 * Emits one dropped-frame measurement exactly as a controller would. Going
 * through the production factory keeps fixtures from expressing a rate that a
 * real backend could never report.
 */
private fun FakePlayerController.emitMeasurement(
    droppedFrames: Long,
    intervalMs: Long,
) {
    val measurement =
        DroppedFrameMeasurement.create(droppedFrames = droppedFrames, intervalMs = intervalMs)
            ?: error("invalid measurement fixture: $droppedFrames frames over $intervalMs ms")
    droppedFrameMeasurementsChannel.trySend(measurement)
}

private open class FakePlayerController(
    private val confirmInitialAudio: Boolean,
    private val publishPrepareEpoch: Boolean = false,
) : PlayerController {
    val playbackStateFlow = MutableStateFlow(playbackState(PlaybackStatus.Idle))
    val runtimeDiagnosticsFlow = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    val seekPositions = mutableListOf<Long>()
    val embeddedAudioOrdinals = mutableListOf<Int>()
    val embeddedTextOrdinals = mutableListOf<Int?>()
    var retryCount = 0
    var offlinePrepareCount = 0
    val offlinePreparePlans = mutableListOf<PlaybackPlan>()
    var preparedPlan: PlaybackPlan? = null
    var prepareCount = 0
    var playCount = 0
    var pauseCount = 0
    var stopCount = 0
    var releaseCount = 0
    var preparedSubtitleAsset: com.jellyscope.core.domain.playback.SubtitleAsset? = null
    val preparedExternalSubtitle: com.jellyscope.core.domain.playback.SubtitleAsset.JellyfinRemote?
        get() = preparedSubtitleAsset as? com.jellyscope.core.domain.playback.SubtitleAsset.JellyfinRemote
    var appliedPlaybackSpeed: Float = 1f
    var appliedSubtitleStyle: SubtitleStyle = SubtitleStyle()
    val recordedLaunchDurations = mutableListOf<Pair<Long, Long>>()

    // Mirrors the production primitive: a buffered channel, so a measurement
    // emitted before the ViewModel's collector starts is still delivered.
    val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)

    override val playbackState: StateFlow<PlaybackState> = playbackStateFlow
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = runtimeDiagnosticsFlow
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> = droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> = videoOutputObservationsChannel.receiveAsFlow()
    override val platformPlayer: Any? = null
    override var activeBackend: PlayerBackend = PlayerBackend.Auto
    override var transcodeSeekRestartsStream: Boolean = false
    override var appliesSubtitleStyle: Boolean = true
    override var playbackHealthMeasurementCapabilities: PlaybackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities.None
    override var videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities =
        VideoOutputMeasurementCapabilities.Unsupported

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: com.jellyscope.core.domain.playback.SubtitleAsset?,
    ) {
        preparedPlan = plan
        preparedSubtitleAsset = subtitleAsset
        prepareCount += 1
        if (publishPrepareEpoch) {
            runtimeDiagnosticsFlow.value =
                PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = prepareCount.toLong())
        }
        val target = plan.subtitleActivationTarget
        val audioTarget = plan.audioActivationTarget
        playbackStateFlow.value =
            playbackStateFlow.value.copy(
                status =
                    if (playbackStateFlow.value.status == PlaybackStatus.Failed) {
                        PlaybackStatus.Loading
                    } else {
                        playbackStateFlow.value.status
                    },
                audioActivation =
                    when {
                        audioTarget == null -> AudioActivationState.None
                        prepareCount == 1 && confirmInitialAudio -> AudioActivationState.Active(audioTarget)
                        else -> playbackStateFlow.value.audioActivation
                    },
                subtitleActivation =
                    if (target?.kind == LocalSubtitleKind.ExternalText && subtitleAsset != null) {
                        SubtitleActivationState.Active(target)
                    } else {
                        SubtitleActivationState.None
                    },
            )
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): com.jellyscope.core.domain.playback.OfflinePrepareResult {
        offlinePrepareCount += 1
        offlinePreparePlans += plan
        preparedPlan = plan
        playbackStateFlow.value = playbackStateFlow.value.copy(status = PlaybackStatus.Loading, error = null)
        return com.jellyscope.core.domain.playback.OfflinePrepareResult.Started
    }

    override fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) {
        recordedLaunchDurations += prepareEpoch to durationMs
    }

    override fun selectEmbeddedAudio(selection: com.jellyscope.core.domain.playback.EmbeddedAudioSelection) {
        embeddedAudioOrdinals += selection.descriptor.filteredContainerOrdinal
        if (selection.target != preparedPlan?.audioActivationTarget) {
            playbackStateFlow.value =
                playbackStateFlow.value.copy(audioActivation = AudioActivationState.Active(selection.target))
        }
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        embeddedTextOrdinals += selection?.descriptor?.filteredContainerOrdinal
        playbackStateFlow.value =
            playbackStateFlow.value.copy(
                subtitleActivation =
                    selection?.target?.let(SubtitleActivationState::Active)
                        ?: SubtitleActivationState.None,
            )
    }

    override fun setPlaybackSpeed(speed: Float) {
        appliedPlaybackSpeed = speed
        playbackStateFlow.value = playbackStateFlow.value.copy(playbackSpeed = speed)
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        appliedSubtitleStyle = style
        playbackStateFlow.value = playbackStateFlow.value.copy(subtitleStyle = style)
    }

    override fun play() {
        playCount += 1
    }

    override fun pause() {
        pauseCount += 1
        playbackStateFlow.value = playbackStateFlow.value.copy(status = PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        seekPositions += positionMs
    }

    override fun stop() {
        stopCount += 1
    }

    override fun retry() {
        retryCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}

private class FakeVolumePlayerController(
    confirmInitialAudio: Boolean,
) : FakePlayerController(confirmInitialAudio),
    PlayerVolumeController {
    val volumeStateFlow = MutableStateFlow(PlayerVolumeState())

    override val volumeState: StateFlow<PlayerVolumeState> = volumeStateFlow

    override fun setVolume(percent: Int) {
        volumeStateFlow.value = volumeStateFlow.value.copy(volumePercent = percent.coerceIn(0, 100))
    }

    override fun setMuted(muted: Boolean) {
        volumeStateFlow.value = volumeStateFlow.value.copy(muted = muted)
    }
}

private class FakePlaybackProgressReporter : PlaybackProgressReporter {
    val reports = mutableListOf<Report>()

    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reports += Report.Start(positionMs, playSessionId, plan.streamMode.playMethod)
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        reports += Report.Progress(positionMs, isPaused, eventName, playSessionId, plan.streamMode.playMethod)
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reports += Report.Stopped(positionMs, playSessionId, plan.streamMode.playMethod)
    }
}

private sealed interface Report {
    data class Start(
        val positionMs: Long,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report

    data class Progress(
        val positionMs: Long,
        val isPaused: Boolean,
        val eventName: PlaybackProgressEvent,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report

    data class Stopped(
        val positionMs: Long,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report
}

private val StreamMode.playMethod: String
    get() =
        when (this) {
            StreamMode.DirectPlay -> "DirectPlay"
            StreamMode.DirectStream -> "DirectStream"
            StreamMode.Transcode -> "Transcode"
            StreamMode.Offline -> "DirectPlay"
        }

private class FakeMediaRepository(
    private val playbackInfo: PlaybackInfo,
    private val playbackInfoResults: ArrayDeque<Result<PlaybackInfo>> = ArrayDeque(),
    private val queueItems: List<MediaItem> = emptyList(),
    private val seasons: List<MediaItem> = emptyList(),
    private val episodesBySeasonId: Map<String, List<MediaItem>> = emptyMap(),
    private val seasonsFailure: Boolean = false,
    private val episodeFailures: Set<String> = emptySet(),
    private val detailItems: Map<String, MediaItem> = emptyMap(),
    private val detailFailures: Set<String> = emptySet(),
    private val mediaSegments: List<MediaSegment> = emptyList(),
    private val chapters: List<Chapter> = emptyList(),
    private val trickplay: TrickplayInfo? = null,
    private val mediaStreams: List<PlaybackMediaStream> = playbackStreams,
    private val fallbackRequestGate: CompletableDeferred<Unit>? = null,
) : MediaRepository {
    var honoursRequestPolicy: Boolean = true

    val requests = mutableListOf<PlaybackInfoRequest>()
    val itemIdRequests = mutableListOf<List<String>>()
    val seasonSeriesIds = mutableListOf<String>()
    val episodeRequests = mutableListOf<EpisodeRequest>()
    var detailCallCount = 0
    var mediaSegmentsCallCount = 0

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> =
        if (itemId in detailFailures) {
            detailCallCount += 1
            Result.failure(IllegalStateException("Item detail unavailable."))
        } else {
            detailCallCount += 1
            val item =
                detailItems[itemId]
                    ?: MediaItem(
                        id = itemId,
                        name = "Item",
                        kind = MediaKind.Movie,
                    )
            Result.success(
                MediaItemDetail(
                    item = item,
                    versions =
                        item.versions.takeIf { versions -> versions.isNotEmpty() }
                            ?: listOf(
                                MediaVersion(
                                    id = "source-1",
                                    name = "1080p",
                                    mediaStreams = mediaStreams,
                                ),
                            ),
                    chapters = chapters,
                    trickplay = trickplay,
                ),
            )
        }

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> {
        seasonSeriesIds += seriesId
        return if (seasonsFailure) {
            Result.failure(IllegalStateException("Seasons unavailable."))
        } else {
            Result.success(seasons)
        }
    }

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> {
        episodeRequests += EpisodeRequest(seriesId, seasonId, seasonIndex)
        return if (seasonId in episodeFailures) {
            Result.failure(IllegalStateException("Episodes unavailable."))
        } else {
            Result.success(episodesBySeasonId[seasonId].orEmpty())
        }
    }

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemsByIds(ids: List<String>): Result<List<MediaItem>> {
        itemIdRequests += ids
        return Result.success(queueItems)
    }

    override suspend fun getMediaSegments(itemId: String): Result<List<MediaSegment>> {
        mediaSegmentsCallCount += 1
        return Result.success(mediaSegments)
    }

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> =
        getPlaybackInfo(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startTimeTicks = startTimeTicks,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            maxStreamingBitrate = maxStreamingBitrate,
            requestPolicy = PlaybackInfoRequestPolicy(),
        )

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        requests +=
            PlaybackInfoRequest(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                startTimeTicks = startTimeTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxStreamingBitrate = maxStreamingBitrate,
                requestPolicy = requestPolicy,
            )
        if (requestPolicy.forceEncodeSubtitle != null) {
            fallbackRequestGate?.await()
        }
        if (playbackInfoResults.isNotEmpty()) {
            return playbackInfoResults.removeFirst()
        }
        // Model a compliant server: EnableDirectPlay=false makes a real server
        // answer with a transcode rather than a DirectPlay-only source. The
        // planner now enforces the policy locally (defense against servers that
        // ignore the flags), so a fake that returns DirectPlay against a
        // DirectPlay-disabled request would fail every fallback replan with
        // NoSupportedStream — a response shape no real server produces.
        // `honoursRequestPolicy = false` keeps the non-compliant shape available
        // for the tests that exist to prove those defenses hold.
        if (honoursRequestPolicy && !requestPolicy.enableDirectPlay) {
            return Result.success(
                playbackInfo.copy(
                    mediaSources =
                        playbackInfo.mediaSources.map { source ->
                            source.copy(
                                supportsDirectPlay = false,
                                supportsTranscoding = true,
                                transcodingUrl = source.transcodingUrl ?: "/Videos/item-1/master.m3u8?VideoCodec=h264",
                            )
                        },
                ),
            )
        }
        return Result.success(playbackInfo)
    }
}

private data class PlaybackInfoRequest(
    val itemId: String,
    val mediaSourceId: String?,
    val startTimeTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val maxStreamingBitrate: Long?,
    val requestPolicy: PlaybackInfoRequestPolicy,
)

private data class EpisodeRequest(
    val seriesId: String,
    val seasonId: String,
    val seasonIndex: Int?,
)

private class FakeDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = "Test"

    override fun newDeviceId(): String = "play-session-1"
}

private fun warningsEnabledPreferencesStore(): PlaybackPreferencesStore =
    FakePlaybackPreferencesStore(PlaybackPreferences(playbackWarningsEnabled = true))

private class FakePlaybackPreferencesStore(
    private val preferences: PlaybackPreferences,
) : PlaybackPreferencesStore {
    override suspend fun get(serverId: String): PlaybackPreferences = preferences

    override suspend fun save(
        serverId: String,
        preferences: PlaybackPreferences,
    ) = Unit

    override suspend fun clear(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private fun playbackState(
    status: PlaybackStatus,
    positionMs: Long = 0L,
    error: PlaybackError? = null,
    audioUnavailable: Boolean = false,
) = PlaybackState(
    status = status,
    positionMs = positionMs,
    durationMs = 60_000L,
    bufferedPositionMs = positionMs,
    error = error,
    audioUnavailable = audioUnavailable,
)

private fun mediaItem(
    id: String,
    name: String,
    primaryTag: String? = null,
    kind: MediaKind = MediaKind.Movie,
    seriesId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): MediaItem =
    MediaItem(
        id = id,
        name = name,
        kind = kind,
        seriesId = seriesId,
        seasonId = seasonNumber?.let { season -> "season-$season" },
        parentIndexNumber = seasonNumber,
        indexNumber = episodeNumber,
        imageRefs = ImageRefs(primaryTag = primaryTag),
    )

private val playbackStreams =
    listOf(
        PlaybackMediaStream(
            index = 0,
            type = "Video",
            displayTitle = "1080p",
            title = null,
            language = null,
            codec = "h264",
            channelLayout = null,
            bitRate = 17_100_000L,
            height = 1080,
            isDefault = null,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
            // Real Jellyfin streams always carry both dimensions; without the
            // width the preflight treats the source as unprovable and replans
            // fail closed, which is not what these tests exercise.
            width = 1920,
        ),
        PlaybackMediaStream(
            index = 1,
            type = "Audio",
            displayTitle = "English",
            title = null,
            language = "eng",
            codec = "aac",
            channelLayout = "stereo",
            bitRate = 192_000L,
            height = null,
            isDefault = true,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
        PlaybackMediaStream(
            index = 3,
            type = "Audio",
            displayTitle = "Spanish",
            title = null,
            language = "spa",
            codec = "aac",
            channelLayout = "stereo",
            bitRate = 192_000L,
            height = null,
            isDefault = false,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
        PlaybackMediaStream(
            index = 4,
            type = "Subtitle",
            displayTitle = "English PGS",
            title = null,
            language = "eng",
            codec = "pgssub",
            channelLayout = null,
            bitRate = null,
            height = null,
            isDefault = null,
            isExternal = false,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
    )

private fun explicitlyExcludedVideoStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.type.equals("Video", ignoreCase = true)) {
            stream.copy(
                codec = "H265",
                videoRangeType = "dovi-with-hdr10-plus",
            )
        } else {
            stream
        }
    }

private fun explicitlyExcludedVideoProfileProvider(): DeviceProfileProvider =
    object : DeviceProfileProvider {
        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("hevc"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = true,
                unsupportedVideoRangeTypesByCodec =
                    mapOf(
                        "hevc" to
                            setOf(
                                "DOVIWithHDR10Plus",
                                "DOVIWithELHDR10Plus",
                            ),
                    ),
            )
    }

private val directPlayPlaybackInfo =
    playbackInfoWithStreams(playbackStreams)

private fun playbackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    PlaybackInfo(
        playSessionId = null,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = streams.withResponseSubtitleDelivery("Embed"),
                ),
            ),
    )

private fun defaultSubtitlePlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.index == 4) {
            stream.copy(
                displayTitle = "English SRT",
                codec = "srt",
                isDefault = true,
            )
        } else {
            stream
        }
    }

private fun externalSubtitlePlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.index == 4) {
            stream.copy(
                displayTitle = "English External",
                codec = "srt",
                isExternal = true,
                deliveryMethod = "External",
                deliveryUrl = "/Videos/item-1/subtitles/4.srt",
            )
        } else {
            stream
        }
    }

private fun englishTextAndFrenchPgsPlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams
        .map { stream ->
            if (stream.index == 4) {
                stream.copy(
                    displayTitle = "English SRT",
                    language = "eng",
                    codec = "srt",
                    isDefault = true,
                )
            } else {
                stream
            }
        }.plus(
            playbackStreams
                .first { stream -> stream.index == 4 }
                .copy(
                    index = 5,
                    displayTitle = "French PGS",
                    language = "fra",
                    codec = "pgssub",
                    isDefault = false,
                ),
        )

private fun englishAndFrenchAssPlaybackStreams(): List<PlaybackMediaStream> =
    englishTextAndFrenchPgsPlaybackStreams().map { stream ->
        when (stream.index) {
            4 -> stream.copy(displayTitle = "English ASS", codec = "ass")
            5 -> stream.copy(displayTitle = "French ASS", codec = "ass")
            else -> stream
        }
    }

private val transcodePlaybackInfo =
    PlaybackInfo(
        playSessionId = "server-play-session-1",
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8",
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams.withResponseSubtitleDelivery("Encode"),
                ),
            ),
    )

private val subtitleTranscodePlaybackInfo =
    PlaybackInfo(
        playSessionId = "server-subtitle-session-1",
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8",
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams.withResponseSubtitleDelivery("Encode"),
                    transcodeReasons = listOf("SubtitleCodecNotSupported"),
                ),
            ),
    )

private fun subtitleTranscodePlaybackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    subtitleTranscodePlaybackInfo.copy(
        mediaSources =
            subtitleTranscodePlaybackInfo.mediaSources.map { source ->
                source.copy(mediaStreams = streams.withResponseSubtitleDelivery("Encode"))
            },
    )

private fun transcodePlaybackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    transcodePlaybackInfo.copy(
        mediaSources =
            transcodePlaybackInfo.mediaSources.map { source ->
                source.copy(mediaStreams = streams.withResponseSubtitleDelivery("Encode"))
            },
    )

private fun List<PlaybackMediaStream>.withResponseSubtitleDelivery(method: String): List<PlaybackMediaStream> =
    map { stream ->
        if (stream.type.equals("Subtitle", ignoreCase = true) && stream.deliveryMethod == null) {
            stream.copy(deliveryMethod = method)
        } else {
            stream
        }
    }

private val noSupportedPlaybackInfo =
    PlaybackInfo(
        playSessionId = null,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams,
                ),
            ),
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
