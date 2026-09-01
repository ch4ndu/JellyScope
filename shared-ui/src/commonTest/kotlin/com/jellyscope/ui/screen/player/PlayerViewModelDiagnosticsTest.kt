// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlannedVideoPresentation
import com.jellyscope.core.domain.playback.PlaybackCapabilityResult
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerViewModelDiagnosticsTest {
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
}
