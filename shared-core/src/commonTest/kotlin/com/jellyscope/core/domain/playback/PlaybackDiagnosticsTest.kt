// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.LogScrubber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticsTest {
    @Test
    fun repositoryOperationsMapToStableDiagnosticOperationWires() {
        assertEquals(
            DiagnosticOperation.GetContinueWatching,
            RepositoryOperation.GetContinueWatching.diagnosticOperation,
        )
        assertEquals("getContinueWatching", RepositoryOperation.GetContinueWatching.diagnosticValue)
        assertEquals(
            DiagnosticOperation.GetRelatedGroups,
            RepositoryOperation.GetRelatedGroups.diagnosticOperation,
        )
        assertEquals("getRelatedGroups", RepositoryOperation.GetRelatedGroups.diagnosticValue)
        assertEquals(
            DiagnosticOperation.GetPlaybackInfo,
            RepositoryOperation.GetPlaybackInfo.diagnosticOperation,
        )
        assertEquals("getPlaybackInfo", RepositoryOperation.GetPlaybackInfo.diagnosticValue)
    }

    @Test
    fun formatterPreservesLineWithoutReasonOrOperationByteForByte() {
        assertEquals(
            "stage=release event=rejected",
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Release,
                    event = PlaybackDiagnosticEvent.Rejected,
                ),
            ),
        )
    }

    @Test
    fun formatterPreservesTypedReasonAndOperationWireValues() {
        assertEquals(
            "stage=mapping event=failed reason=mpv-sub-add-rejected operation=selectEmbeddedSubtitle",
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Failed,
                    reason = SubtitleActivationFailureReason.MpvSubAddRejected,
                    operation = PlayerOperation.SelectEmbeddedSubtitle,
                ),
            ),
        )
        assertEquals(
            "stage=repository event=failed operation=getItemDetail",
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Repository,
                    event = PlaybackDiagnosticEvent.Failed,
                    operation = RepositoryOperation.GetItemDetail,
                ),
            ),
        )
        assertEquals(
            "stage=release event=rejected operation=renderFrameIfNeeded",
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Release,
                    event = PlaybackDiagnosticEvent.Rejected,
                    operation = PlayerOperation.RenderFrameIfNeeded,
                ),
            ),
        )
    }

    @Test
    fun noVideoDiagnosticsCarryTheSelectedBackendThreshold() {
        val vlcSignal =
            PlaybackHealthSignal.NoVideoOutput(
                observedAtMs = 20_000L,
                thresholdMs = PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS,
            )
        val defaultSignal =
            PlaybackHealthSignal.NoVideoOutput(
                observedAtMs = 5_000L,
                thresholdMs = PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS,
            )
        assertEquals(
            PlaybackHealthThresholdClass.NoVideoOutput20Seconds,
            vlcSignal.thresholdClass(),
        )
        assertEquals(
            PlaybackHealthThresholdClass.NoVideoOutput5Seconds,
            defaultSignal.thresholdClass(),
        )
    }

    @Test
    fun noVideoOutputThresholdUsesTheVlcFamilyBound() {
        assertEquals(
            PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS,
            playbackHealthNoVideoOutputThresholdMs(PlayerBackend.LibVlc),
        )
        assertEquals(
            PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS,
            playbackHealthNoVideoOutputThresholdMs(PlayerBackend.VlcKit),
        )
        assertEquals(
            PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS,
            playbackHealthNoVideoOutputThresholdMs(PlayerBackend.ExoPlayer),
        )
    }

    @Test
    fun scrubberAllowedFieldsCoverEveryFormatterField() {
        val missingFields = playbackDiagnosticFieldNames - LogScrubber.allowedFields

        assertTrue(missingFields.isEmpty(), "LogScrubber is missing formatter fields: $missingFields")
    }

    @Test
    fun formatterRendersStructuredRuntimeReportingAndIdentityFreeTrackFacts() {
        val output =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Render,
                    event = PlaybackDiagnosticEvent.TrackState,
                    platform = PlaybackDiagnosticPlatform.Android,
                    sessionSequence = 4L,
                    trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                    trackRequestedState = PlaybackDiagnosticTrackState.Selected,
                    trackConfirmedState = PlaybackDiagnosticTrackState.Off,
                    trackMatchesRequest = false,
                    trackActivation = PlaybackDiagnosticTrackActivation.Pending,
                    subtitleRenderMode = SubtitleRenderMode.LocalExternalText,
                    subtitleRenderStatus = SubtitleRenderStatus.Pending,
                    subtitleStyleable = null,
                    allocatedBufferBytes = 8_192L,
                    bufferedAheadMs = 3_000L,
                    libVlcCachePercent = 42.5f,
                    reportingOperation = PlaybackReportingOperation.Progress,
                    reportingResult = PlaybackReportingResult.Failed,
                    terminalOutcome = PlaybackTerminalOutcome.RetryScheduled,
                ),
            )

        assertTrue(output.contains("event=track-state"))
        assertTrue(output.contains("trackKind=subtitle"))
        assertTrue(output.contains("trackRequestedState=Selected"))
        assertTrue(output.contains("trackConfirmedState=Off"))
        assertTrue(output.contains("trackMatchesRequest=false"))
        assertTrue(output.contains("trackActivation=Pending"))
        assertTrue(output.contains("subtitleRenderMode=LocalExternalText"))
        assertTrue(output.contains("subtitleRenderStatus=Pending"))
        assertFalse(output.contains("subtitleStyleable="))
        assertTrue(output.contains("allocatedBufferBytes=8192"))
        assertTrue(output.contains("bufferedAheadMs=3000"))
        assertTrue(output.contains("libVlcCachePercent=42.5"))
        assertTrue(output.contains("reportingOperation=progress"))
        assertTrue(output.contains("reportingResult=Failed"))
        assertTrue(output.contains("terminalOutcome=RetryScheduled"))
        assertFalse(output.contains("itemId"))
        assertFalse(output.contains("streamIndex"))
        assertFalse(output.contains("language"))
        assertFalse(output.contains("target"))
    }

    @Test
    fun formatterBoundsDisplayFactsAndKeepsAbsentValuesAbsent() {
        val output =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.TvDisplay,
                    event = PlaybackDiagnosticEvent.TvDisplay,
                    platform = PlaybackDiagnosticPlatform.Android,
                    tvDisplayResult = PlaybackDiagnosticDisplayResult.Unrecognized,
                    tvDisplayTier = PlaybackDiagnosticDisplayTier.Unrecognized,
                    tvDisplayActiveWidthPx = 3_840,
                    tvDisplayActiveHeightPx = 2_160,
                    tvDisplayActiveRefreshMilliHz = 60_000,
                    tvDisplayRequestedWidthPx = Int.MAX_VALUE,
                    tvDisplayRequestedHeightPx = 0,
                    tvDisplayRequestedRefreshMilliHz = -1,
                    tvDisplaySwitchDurationMs = Long.MAX_VALUE,
                    allocatedBufferBytes = null,
                    bufferedAheadMs = null,
                    libVlcCachePercent = Float.POSITIVE_INFINITY,
                ),
            )

        assertTrue(output.contains("event=tv-display"))
        assertTrue(output.contains("tvDisplayResult=unrecognized"))
        assertTrue(output.contains("tvDisplayTier=unrecognized"))
        assertTrue(output.contains("tvDisplayActiveWidthPx=3840"))
        assertTrue(output.contains("tvDisplayActiveHeightPx=2160"))
        assertTrue(output.contains("tvDisplayActiveRefreshMilliHz=60000"))
        assertFalse(output.contains("tvDisplayRequestedWidthPx="))
        assertFalse(output.contains("tvDisplayRequestedHeightPx="))
        assertFalse(output.contains("tvDisplayRequestedRefreshMilliHz="))
        assertTrue(output.contains("tvDisplaySwitchDurationMs=60000"))
        assertFalse(output.contains("allocatedBufferBytes="))
        assertFalse(output.contains("bufferedAheadMs="))
        assertFalse(output.contains("libVlcCachePercent="))
        assertFalse(output.contains("modeId"))
        assertFalse(output.contains("displayId"))
    }

    @Test
    fun formatterOmitsNegativeRuntimeAndDisplayValuesButKeepsGenuineZero() {
        val negativeOutput =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSummary,
                    allocatedBufferBytes = -1L,
                    bufferedAheadMs = -1L,
                    libVlcCachePercent = -1f,
                    tvDisplaySwitchDurationMs = -1L,
                ),
            )

        assertFalse(negativeOutput.contains("allocatedBufferBytes="))
        assertFalse(negativeOutput.contains("bufferedAheadMs="))
        assertFalse(negativeOutput.contains("libVlcCachePercent="))
        assertFalse(negativeOutput.contains("tvDisplaySwitchDurationMs="))

        val zeroOutput =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSummary,
                    allocatedBufferBytes = 0L,
                    bufferedAheadMs = 0L,
                    libVlcCachePercent = 0f,
                    tvDisplaySwitchDurationMs = 0L,
                ),
            )

        assertTrue(zeroOutput.contains("allocatedBufferBytes=0"))
        assertTrue(zeroOutput.contains("bufferedAheadMs=0"))
        assertTrue(zeroOutput.contains("libVlcCachePercent=0.0"))
        assertTrue(zeroOutput.contains("tvDisplaySwitchDurationMs=0"))
    }

    @Test
    fun formatterEmitsOnlyAllowlistedFields() {
        val secret = "https://server.example/video?api_key=token title=/Users/me/movie.mkv Authorization=secret"
        val output =
            requireNotNull(
                LogScrubber.capture(
                    tag = "PlaybackInfoPlanner",
                    message =
                        formatPlaybackDiagnostic(
                            PlaybackDiagnostic(
                                stage = PlaybackDiagnosticStage.Mapping,
                                event = PlaybackDiagnosticEvent.Failed,
                                platform = PlaybackDiagnosticPlatform.Desktop,
                                backend = PlayerBackend.LibVlc,
                                requestedBackend = PlayerBackend.ExoPlayer,
                                backendFallbackResult = PlaybackBackendFallbackResult.Applied,
                                decoderResourcePolicy = PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads,
                                decoderFrameThreads = 1,
                                exceptionType = secret,
                                errorCategory = PlaybackError.Unknown,
                                terminalOutcome = PlaybackTerminalOutcome.Failed,
                                prepareSequence = 2_000_000L,
                                sessionSequence = 3L,
                                retryAttempted = true,
                                degradationAttempted = false,
                                nativeCode = -12,
                                clientTrigger = PlaybackClientTrigger.SubtitleActivationFallback,
                                qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                                qualityPolicyOrigin = PlaybackQualityPolicyOrigin.SessionAutoRecovery,
                                capabilityResult = PlaybackCapabilityResult.SourceCopyRejected,
                                transcodeReasons =
                                    listOf(
                                        "DirectPlayError",
                                        "Authorization=/Users/me/movie.mkv",
                                    ),
                                startPositionMs = 74_000L,
                                trackKind = PlaybackDiagnosticTrackKind.Audio,
                                candidateCount = 2,
                                mappingResult = NativeTrackMappingResult.Ambiguous,
                                reason = PlaybackPreflightReason.VideoResolutionNotSupported,
                                operation = RepositoryOperation.GetItemDetail,
                                sourceWidth = 7_680,
                                sourceHeight = 4_320,
                                frameRate = 60.0,
                                sourceBitDepth = 10,
                                sourceVideoRangeType = "DOVIWithHDR10Plus",
                                sourceBitrateBps = 120_000_000L,
                                cappedWidth = 2_792,
                                cappedHeight = 1_568,
                                maxStreamingBitrate = 8_000_000L,
                                requestCapBitrateBps = 8_000_000L,
                                effectiveTranscodeCapBitrateBps = 6_000_000L,
                                codec = "h264",
                                level = 52,
                                container = "mkv",
                                videoDecoderName = "c2.android.av1.decoder",
                                runtimeVideoWidth = 7_680,
                                runtimeVideoHeight = 4_320,
                                runtimeVideoFrameRate = 59.94,
                                bandwidthEstimateBps = 180_000_000L,
                                recentVideoRenderP95Ms = 19.5,
                                recentPresentedFrameRate = 58.2,
                                presentationGapCount = 3L,
                                bufferPolicy = PlaybackBufferPolicy.LowRam16MiB,
                                lowRamDevice = true,
                                targetBufferBytes = 16_777_216L,
                                peakAllocatedBufferBytes = 20_000_000L,
                                minBufferedAheadMs = 800L,
                                maxBufferedAheadMs = 8_200L,
                                nativePrepareToFirstFrameMs = 950L,
                                launchToFirstFrameMs = 1_500L,
                                rebufferCount = 2,
                                totalRebufferMs = 700L,
                                maxRebufferMs = 500L,
                                audioUnderrunCount = 1,
                                maxAudioFeedGapMs = 40L,
                                backendDroppedVideoFrames = 42L,
                                backendDroppedVideoFramesPerSecond = 2.5,
                                decoderDroppedVideoFrames = 17L,
                                outputDroppedVideoFrames = 25L,
                                healthPostStartGuidanceShown = true,
                                healthNoVideoOutputGuidanceShown = false,
                                healthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                                healthGuidancePublishable = true,
                                healthBufferingIntervalOpenedSinceEvidenceRestart = false,
                                autoRecoveryTrigger = AutoPlaybackRecoveryTrigger.NoVideoOutput,
                                recoveryDecision = PlaybackRecoveryDecision.CompatibilityReplan,
                                recoveryPromptReason = AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
                                recoveryCompatibilityAttempted = true,
                                recoveryQualityAttempted = false,
                                recoveryBudgetExhausted = false,
                                recoveryFromQualityBudgetBps = 8_000_000L,
                                recoveryToQualityBudgetBps = 6_000_000L,
                                firstVideoOutputAvailable = true,
                                firstVideoOutputObserved = false,
                                firstVideoOutputEvidence = VideoOutputEvidence.NativeFirstOutput,
                                persistenceTarget = PlaybackPersistenceTarget.PlaybackSelection,
                                persistenceResult = PlaybackPersistenceResult.Cancelled,
                            ),
                        ),
                ),
            )

        assertTrue(output.contains("stage=mapping"))
        assertTrue(output.contains("backend=libvlc"))
        assertTrue(output.contains("requestedBackend=exoplayer"))
        assertTrue(output.contains("backendFallbackResult=applied"))
        assertTrue(output.contains("decoderResourcePolicy=BoundedDav1dFrameThreads"))
        assertTrue(output.contains("decoderFrameThreads=1"))
        assertTrue(output.contains("errorCategory=unknown"))
        assertTrue(output.contains("terminalOutcome=Failed"))
        assertTrue(output.contains("prepareSequence=1000000"))
        assertTrue(output.contains("sessionSequence=3"))
        assertTrue(output.contains("retryAttempted=true"))
        assertTrue(output.contains("degradationAttempted=false"))
        assertTrue(output.contains("nativeCode=-12"))
        assertTrue(output.contains("clientTrigger=SubtitleActivationFallback"))
        assertTrue(
            output.contains(
                "qualityCapOrigin=SettingsDefault",
            ),
        )
        assertTrue(output.contains("qualityPolicyOrigin=SessionAutoRecovery"))
        assertTrue(output.contains("capabilityResult=SourceCopyRejected"))
        assertTrue(output.contains("transcodeReasons=DirectPlayError,unrecognized"))
        assertTrue(output.contains("startPositionMs=74000"))
        assertTrue(output.contains("mappingResult=ambiguous"))
        assertTrue(output.contains("reason=VideoResolutionNotSupported"))
        assertTrue(output.contains("operation=getItemDetail"))
        assertTrue(output.contains("sourceWidth=7680"))
        assertTrue(output.contains("sourceHeight=4320"))
        assertTrue(output.contains("frameRate=60.0"))
        assertTrue(output.contains("sourceBitDepth=10"))
        assertTrue(output.contains("sourceVideoRangeType=DOVIWithHDR10Plus"))
        assertTrue(output.contains("sourceBitrateBps=120000000"))
        assertTrue(output.contains("cappedWidth=2792"))
        assertTrue(output.contains("cappedHeight=1568"))
        assertTrue(output.contains("maxStreamingBitrate=8000000"))
        assertTrue(output.contains("requestCapBitrateBps=8000000"))
        assertTrue(output.contains("effectiveTranscodeCapBitrateBps=6000000"))
        assertTrue(output.contains("codec=h264"))
        assertTrue(output.contains("level=52"))
        assertTrue(output.contains("container=mkv"))
        assertTrue(output.contains("videoDecoderName=android-codec"))
        assertTrue(output.contains("runtimeVideoWidth=7680"))
        assertTrue(output.contains("runtimeVideoHeight=4320"))
        assertTrue(output.contains("runtimeVideoFrameRate=59.94"))
        assertTrue(output.contains("bandwidthEstimateBps=180000000"))
        assertTrue(output.contains("recentVideoRenderP95Ms=19.5"))
        assertTrue(output.contains("recentPresentedFrameRate=58.2"))
        assertTrue(output.contains("presentationGapCount=3"))
        assertTrue(output.contains("bufferPolicy=LowRam16MiB"))
        assertTrue(output.contains("lowRamDevice=true"))
        assertTrue(output.contains("targetBufferBytes=16777216"))
        assertTrue(output.contains("peakAllocatedBufferBytes=20000000"))
        assertTrue(output.contains("minBufferedAheadMs=800"))
        assertTrue(output.contains("maxBufferedAheadMs=8200"))
        assertTrue(output.contains("nativePrepareToFirstFrameMs=950"))
        assertTrue(output.contains("launchToFirstFrameMs=1500"))
        assertTrue(output.contains("rebufferCount=2"))
        assertTrue(output.contains("totalRebufferMs=700"))
        assertTrue(output.contains("maxRebufferMs=500"))
        assertTrue(output.contains("audioUnderrunCount=1"))
        assertTrue(output.contains("maxAudioFeedGapMs=40"))
        assertTrue(output.contains("backendDroppedVideoFrames=42"))
        assertTrue(output.contains("backendDroppedVideoFramesPerSecond=2.5"))
        assertTrue(output.contains("decoderDroppedVideoFrames=17"))
        assertTrue(output.contains("outputDroppedVideoFrames=25"))
        assertTrue(output.contains("healthPostStartGuidanceShown=true"))
        assertTrue(output.contains("healthNoVideoOutputGuidanceShown=false"))
        assertTrue(output.contains("healthGuidancePolicy=Actionable"))
        assertTrue(output.contains("healthGuidancePublishable=true"))
        assertTrue(output.contains("healthBufferingIntervalOpenedSinceEvidenceRestart=false"))
        assertTrue(output.contains("autoRecoveryTrigger=NoVideoOutput"))
        assertTrue(output.contains("recoveryDecision=CompatibilityReplan"))
        assertTrue(output.contains("recoveryPromptReason=NoLowerQualityAvailable"))
        assertTrue(output.contains("recoveryCompatibilityAttempted=true"))
        assertTrue(output.contains("recoveryQualityAttempted=false"))
        assertTrue(output.contains("recoveryBudgetExhausted=false"))
        assertTrue(output.contains("recoveryFromQualityBudgetBps=8000000"))
        assertTrue(output.contains("recoveryToQualityBudgetBps=6000000"))
        assertTrue(output.contains("firstVideoOutputAvailable=true"))
        assertTrue(output.contains("firstVideoOutputObserved=false"))
        assertTrue(output.contains("firstVideoOutputEvidence=NativeFirstOutput"))
        assertTrue(output.contains("persistenceTarget=PlaybackSelection"))
        assertTrue(output.contains("persistenceResult=Cancelled"))
        assertFalse(output.contains("https://"))
        assertFalse(output.contains("token"))
        assertFalse(output.contains("/Users/"))
        assertFalse(output.contains("Authorization"))
    }

    @Test
    fun offlinePlayerErrorDiagnosticOmitsItsRequiredBackend() {
        val expected =
            "stage=nativeplayer event=terminal-error errorCategory=offline-player-unavailable"

        listOf(PlayerBackend.VlcKit, PlayerBackend.LibVlc).forEach { requiredBackend ->
            assertEquals(
                expected,
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.TerminalError,
                        errorCategory = PlaybackError.OfflinePlayerUnavailable(requiredBackend),
                    ),
                ),
            )
        }
    }

    @Test
    fun convertedDecisionDiagnosticsSurviveCapture() {
        val convertedLines =
            listOf(
                "preflight quality cap" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Planner,
                        event = PlaybackDiagnosticEvent.QualityCap,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.LibVlc,
                        reason = PlaybackPreflightReason.VideoResolutionNotSupported,
                        maxStreamingBitrate = 8_000_000L,
                    ),
                "response quality cap" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Planner,
                        event = PlaybackDiagnosticEvent.QualityCap,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.LibVlc,
                        maxStreamingBitrate = 8_000_000L,
                    ),
                "resolution cap" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Planner,
                        event = PlaybackDiagnosticEvent.ResolutionCap,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        sourceWidth = 7_680,
                        sourceHeight = 4_320,
                        frameRate = 60.0,
                        cappedWidth = 2_792,
                        cappedHeight = 1_568,
                    ),
                "Android profile probe" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        platform = PlaybackDiagnosticPlatform.Android,
                        candidateCount = 4,
                    ),
                "Apple profile probe" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        candidateCount = 3,
                    ),
                "desktop profile probe" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        platform = PlaybackDiagnosticPlatform.Desktop,
                        candidateCount = 4,
                    ),
                "codec dropped" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.CodecDropped,
                        platform = PlaybackDiagnosticPlatform.Android,
                        codec = "h264",
                        reason = UnusableVideoCodecReason.BelowMinimumSize,
                    ),
                "performance summary" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.PerformanceSummary,
                        platform = PlaybackDiagnosticPlatform.Android,
                        bufferPolicy = PlaybackBufferPolicy.Regular16MiB,
                        lowRamDevice = false,
                        targetBufferBytes = 16_777_216L,
                        rebufferCount = 0,
                    ),
                "terminal Media3 error" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.TerminalError,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.ExoPlayer,
                        exceptionType = "PlaybackException",
                        errorCategory = PlaybackError.Decoder,
                        terminalOutcome = PlaybackTerminalOutcome.Failed,
                        prepareSequence = 4L,
                        sessionSequence = 2L,
                        retryAttempted = false,
                        degradationAttempted = false,
                    ),
                "backend fallback result" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.BackendSelection,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.ExoPlayer,
                        requestedBackend = PlayerBackend.LibVlc,
                        backendFallbackResult = PlaybackBackendFallbackResult.Applied,
                    ),
                "backend construction failure" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.BackendConstruction,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.Mpv,
                        requestedBackend = PlayerBackend.Mpv,
                        backendAvailability = PlaybackBackendAvailability.Bundled,
                        backendAbi = PlaybackBackendAbi.ArmeabiV7a,
                        backendConstructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                        backendConstructionResult = PlaybackBackendConstructionResult.Failed,
                        backendConfigurationKey = "scripts",
                        nativeCode = -5,
                    ),
                "auto recovery decision" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.RecoveryDecision,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        autoRecoveryTrigger = AutoPlaybackRecoveryTrigger.DroppedFrames,
                        recoveryDecision = PlaybackRecoveryDecision.LowerQuality,
                        recoveryCompatibilityAttempted = true,
                        recoveryQualityAttempted = true,
                        recoveryBudgetExhausted = false,
                        recoveryFromQualityBudgetBps = 8_000_000L,
                        recoveryToQualityBudgetBps = 6_000_000L,
                    ),
                "first video output" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.VideoOutput,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        firstVideoOutputAvailable = true,
                        firstVideoOutputObserved = true,
                        firstVideoOutputEvidence = VideoOutputEvidence.NativeFirstOutput,
                    ),
                "prepare dispatched" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.PrepareDispatched,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.ExoPlayer,
                        prepareSequence = 4L,
                        sessionSequence = 2L,
                    ),
                "prepare requested" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.PrepareRequested,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.ExoPlayer,
                        sessionSequence = 2L,
                    ),
                "backend readiness" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.BackendReadiness,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.LibVlc,
                        decoderResourcePolicy = PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads,
                        decoderFrameThreads = 1,
                        prepareSequence = 4L,
                        sessionSequence = 2L,
                    ),
                "persistence failure" to
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Persistence,
                        event = PlaybackDiagnosticEvent.Write,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        persistenceTarget = PlaybackPersistenceTarget.PlaybackSelection,
                        persistenceResult = PlaybackPersistenceResult.Failed,
                        exceptionType = "IllegalStateException",
                    ),
            )

        convertedLines.forEach { (description, diagnostic) ->
            val tag =
                if (diagnostic.stage == PlaybackDiagnosticStage.Profile) {
                    "DeviceProfile"
                } else {
                    "PlaybackInfoPlanner"
                }
            val formatted = formatPlaybackDiagnostic(diagnostic)
            val expectedEvent =
                when (diagnostic.event) {
                    PlaybackDiagnosticEvent.QualityCap -> "quality-cap"
                    PlaybackDiagnosticEvent.ResolutionCap -> "resolution-cap"
                    PlaybackDiagnosticEvent.CodecDropped -> "codec-dropped"
                    PlaybackDiagnosticEvent.PerformanceSummary -> "performance-summary"
                    PlaybackDiagnosticEvent.TerminalError -> "terminal-error"
                    PlaybackDiagnosticEvent.BackendSelection -> "backend-selection"
                    PlaybackDiagnosticEvent.BackendReadiness -> "backend-readiness"
                    PlaybackDiagnosticEvent.RecoveryDecision -> "recovery-decision"
                    PlaybackDiagnosticEvent.VideoOutput -> "video-output"
                    PlaybackDiagnosticEvent.PrepareRequested -> "prepare-requested"
                    PlaybackDiagnosticEvent.PrepareDispatched -> "prepare-dispatched"
                    PlaybackDiagnosticEvent.BackendConstruction -> "backend-construction"
                    PlaybackDiagnosticEvent.NativeLifecycle -> "native-lifecycle"
                    else -> diagnostic.event.name.lowercase()
                }

            assertTrue(formatted.contains("event=$expectedEvent"), "$description rendered the wrong event: $formatted")
            assertTrue(
                LogScrubber.capture(tag, formatted) != null,
                "$description was dropped: $formatted",
            )
        }
    }

    @Test
    fun backendReadinessDiagnosticSurvivesLibVlcCapture() {
        val output =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.BackendReadiness,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = PlayerBackend.LibVlc,
                    prepareSequence = 7L,
                    sessionSequence = 3L,
                    decoderResourcePolicy = PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads,
                    decoderFrameThreads = 2,
                ),
            )

        assertTrue(output.contains("event=backend-readiness"))
        assertTrue(output.contains("decoderResourcePolicy=BoundedDav1dFrameThreads"))
        assertTrue(output.contains("decoderFrameThreads=2"))
        assertTrue(LogScrubber.capture("LibVlcPlayerController", output) != null)
    }

    @Test
    fun backendReadinessDiagnosticOmitsUnsupportedFrameThreadCounts() {
        listOf(-1, 0, 3).forEach { unsupportedCount ->
            val output =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.BackendReadiness,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.LibVlc,
                        decoderResourcePolicy = PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads,
                        decoderFrameThreads = unsupportedCount,
                    ),
                )

            assertFalse(output.contains("decoderFrameThreads="))
        }
    }

    @Test
    fun formatterReplacesUnrecognizedFreeformValues() {
        val output =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Profile,
                    event = PlaybackDiagnosticEvent.Probe,
                    backendConfigurationKey = "personal-profile",
                    transcodeReasons = listOf("UserRequestedMovie"),
                    codec = "Jugnu",
                    container = "A Movie Title",
                    sourceVideoRangeType = "private-range",
                    videoDecoderName = "private-decoder",
                ),
            )

        assertTrue(output.contains("backendConfigurationKey=unrecognized"))
        assertTrue(output.contains("transcodeReasons=unrecognized"))
        assertTrue(output.contains("codec=unrecognized"))
        assertTrue(output.contains("container=unrecognized"))
        assertTrue(output.contains("sourceVideoRangeType=unrecognized"))
        assertTrue(output.contains("videoDecoderName=unrecognized"))
        assertFalse(output.contains("personal-profile"))
        assertFalse(output.contains("UserRequestedMovie"))
        assertFalse(output.contains("Jugnu"))
        assertFalse(output.contains("Movie"))
        assertFalse(output.contains("private-range"))
        assertFalse(output.contains("private-decoder"))
    }

    @Test
    fun formatterCanonicalizesEveryProducerSupportedDolbyVisionEnhancementRange() {
        mapOf(
            "DOVIWithEL" to "DOVIWithEL",
            "DOVIWithELHDR10Plus" to "DOVIWithELHDR10Plus",
            "dovi-with-el" to "DOVIWithEL",
            "dovi_with_el_hdr10_plus" to "DOVIWithELHDR10Plus",
        ).forEach { (producerValue, expected) ->
            val output =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        sourceVideoRangeType = producerValue,
                    ),
                )

            assertTrue(output.contains("sourceVideoRangeType=$expected"))
            assertFalse(output.contains("sourceVideoRangeType=unrecognized"))
        }
    }

    @Test
    fun formatterRendersEveryMappingReasonAndDistinguishesApplePlatforms() {
        NativeTrackMappingReason.entries.forEach { reason ->
            val output =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Mapping,
                        event = PlaybackDiagnosticEvent.Resolved,
                        platform = PlaybackDiagnosticPlatform.Ios,
                        mappingReason = reason,
                    ),
                )
            assertTrue(output.contains("platform=ios"))
            assertTrue(output.contains("mappingReason=${reason.name.lowercase()}"))
        }

        val tvOs =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Rejected,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    exceptionType = "title=/private/movie.mkv",
                ),
            )
        assertTrue(tvOs.contains("platform=tvos"))
        assertTrue(tvOs.contains("exceptionType=Unknown"))
        assertFalse(tvOs.contains("movie.mkv"))
    }

    @Test
    fun formatterBoundsSeekEvidenceAndKeepsItIdentityFree() {
        val output =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.SeekCompleted,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = PlayerBackend.Mpv,
                    prepareSequence = 4L,
                    operation = PlayerOperation.SeekTo,
                    seekOriginPositionMs = -1L,
                    seekTargetPositionMs = 20_000L,
                    seekObservedPositionMs = Long.MAX_VALUE,
                ),
            )

        assertTrue(output.contains("event=seekcompleted"))
        assertTrue(output.contains("seekOriginPositionMs=0"))
        assertTrue(output.contains("seekTargetPositionMs=20000"))
        assertTrue(output.contains("seekObservedPositionMs=604800000"))
        assertFalse(output.contains("item"))
        assertFalse(output.contains("http"))
    }
}
