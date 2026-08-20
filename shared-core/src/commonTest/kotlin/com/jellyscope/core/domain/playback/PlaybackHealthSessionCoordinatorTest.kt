// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHealthSessionCoordinatorTest {
    @Test
    fun slowStartupUsesTheStartupSlotAndKeepsAdvisoryNonActionable() =
        runTest {
            val guidance = mutableListOf<PlaybackHealthGuidance?>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames },
                    onGuidanceChanged = guidance::add,
                )

            coordinator.start(
                generation = 1L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 11L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Advisory,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Loading))
            advanceTimeBy(PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS)
            runCurrent()

            val emitted = guidance.last()
            assertEquals(PlaybackHealthGuidanceReason.SlowStartup, emitted?.reason)
            assertFalse(emitted?.canReduceQuality ?: true)
            assertFalse(emitted?.canOpenPlaybackSettings ?: true)

            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            assertEquals(PlaybackHealthGuidanceReason.SlowStartup, guidance.last()?.reason)
            coordinator.dismissGuidance()
            assertNull(guidance.last())
        }

    @Test
    fun staleTimerCannotPublishIntoTheReplacementGeneration() =
        runTest {
            val guidance = mutableListOf<PlaybackHealthGuidance?>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onGuidanceChanged = guidance::add,
                )
            val context =
                PlaybackHealthSessionContext(
                    sessionToken = 1L,
                    streamMode = StreamMode.DirectPlay,
                    guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                    nextLowerQualityRungBps = 8_000_000L,
                )

            coordinator.start(generation = 1L, context = context)
            coordinator.observePlaybackState(state(PlaybackStatus.Loading))
            coordinator.start(generation = 2L, context = context.copy(sessionToken = 2L))
            advanceTimeBy(PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS + 1L)
            runCurrent()

            assertNull(coordinator.guidance)
            assertTrue(guidance.none { it?.sessionToken == 1L })
        }

    @Test
    fun disabledPolicyLogsSignalsWithoutPublishingGuidance() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onSignal = signals::add,
                )
            coordinator.start(
                generation = 1L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 1L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Disabled,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Loading))
            advanceTimeBy(PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS)
            runCurrent()

            assertEquals(PlaybackHealthSignalKind.SlowStartup, signals.single().kind)
            assertNull(coordinator.guidance)

            coordinator.start(
                generation = 2L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 2L,
                        streamMode = StreamMode.Offline,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        nextLowerQualityRungBps = 8_000_000L,
                        isOffline = true,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            coordinator.observePlaybackState(state(PlaybackStatus.Buffering))
            advanceTimeBy(PLAYBACK_HEALTH_LONG_BUFFERING_THRESHOLD_MS)
            runCurrent()

            assertEquals(
                listOf(
                    PlaybackHealthSignalKind.SlowStartup,
                    PlaybackHealthSignalKind.LongBuffering,
                ),
                signals.map(PlaybackHealthSignal::kind),
            )
            assertNull(coordinator.guidance)
        }

    @Test
    fun offlineSessionLogsSlowStartupButSuppressesStreamingGuidance() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onSignal = signals::add,
                )

            coordinator.start(
                generation = 1L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 1L,
                        streamMode = StreamMode.Offline,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        nextLowerQualityRungBps = 8_000_000L,
                        isOffline = true,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Loading))
            advanceTimeBy(PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS)
            runCurrent()

            assertEquals(PlaybackHealthSignalKind.SlowStartup, signals.single().kind)
            assertNull(coordinator.guidance)
        }

    @Test
    fun exclusionDelaysPostStartGuidanceWithoutDroppingTheSession() =
        runTest {
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                )
            coordinator.start(
                generation = 1L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 1L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        nextLowerQualityRungBps = 8_000_000L,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            advanceTimeBy(1_000L)
            coordinator.markExclusion(PlaybackHealthExclusionReason.Seek)
            coordinator.observePlaybackState(state(PlaybackStatus.Buffering))

            advanceTimeBy(6_999L)
            runCurrent()
            assertNull(coordinator.guidance)

            advanceTimeBy(1L)
            runCurrent()
            val guidance = assertNotNull(coordinator.guidance)
            assertEquals(PlaybackHealthGuidanceReason.LongBuffering, guidance.reason)
            assertTrue(guidance.canReduceQuality)
            assertTrue(guidance.canOpenPlaybackSettings)
        }

    @Test
    fun videoExpectedSessionReportsNoVideoOutputWithSupportedEvidence() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = {
                        PlaybackHealthMeasurementCapabilities(
                            hasReliableBufferingTransitions = true,
                            hasDroppedFrameMeasurements = false,
                            hasReliableFirstVideoOutput = true,
                        )
                    },
                    onSignal = signals::add,
                )
            coordinator.start(
                generation = 3L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 3L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        videoExpected = true,
                    ),
            )
            coordinator.expectVideoOutput(generation = 31L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            runCurrent()

            val signal = assertIs<PlaybackHealthSignal.NoVideoOutput>(signals.single())
            assertEquals(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS, signal.thresholdMs)
            assertEquals(PlaybackHealthGuidanceReason.NoVideoOutput, coordinator.guidance?.reason)
        }

    @Test
    fun firstVideoOutputCapabilityLossCancelsAnAlreadyArmedSignal() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            var capabilities =
                PlaybackHealthMeasurementCapabilities(
                    hasReliableBufferingTransitions = true,
                    hasDroppedFrameMeasurements = false,
                    hasReliableFirstVideoOutput = true,
                )
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { capabilities },
                    onSignal = signals::add,
                )
            coordinator.start(generation = 4L, context = videoContext(sessionToken = 4L))
            coordinator.expectVideoOutput(generation = 41L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS - 1L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))

            capabilities = PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames
            advanceTimeBy(1L)
            runCurrent()

            assertTrue(signals.none { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })
            assertNull(coordinator.guidance)
        }

    @Test
    fun noVideoOutputRequiresAnAdvancingPlaybackClock() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(generation = 9L, context = videoContext(sessionToken = 9L))
            coordinator.expectVideoOutput(generation = 91L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))

            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS)
            runCurrent()
            assertTrue(signals.none { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })

            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            runCurrent()
            assertTrue(signals.none { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })

            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            runCurrent()
            assertEquals(PlaybackHealthSignalKind.NoVideoOutput, signals.single().kind)
        }

    @Test
    fun vlcFamilyUsesTheTwentySecondNoVideoOutputThreshold() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(
                generation = 10L,
                context = videoContext(sessionToken = 10L).copy(backend = PlayerBackend.LibVlc),
            )
            coordinator.expectVideoOutput(generation = 101L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))

            advanceTimeBy(PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS - 1L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            runCurrent()
            assertTrue(signals.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            val signal = assertIs<PlaybackHealthSignal.NoVideoOutput>(signals.single())
            assertEquals(PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS, signal.thresholdMs)
        }

    @Test
    fun audioOnlySessionDoesNotStartVideoOutputTimer() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = {
                        PlaybackHealthMeasurementCapabilities(
                            hasReliableBufferingTransitions = true,
                            hasDroppedFrameMeasurements = false,
                            hasReliableFirstVideoOutput = true,
                        )
                    },
                    onSignal = signals::add,
                )
            coordinator.start(
                generation = 4L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 4L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        videoExpected = false,
                    ),
            )
            coordinator.expectVideoOutput(generation = 41L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS + 1L)
            runCurrent()

            assertTrue(signals.isEmpty())
        }

    @Test
    fun noVideoOutputTimeoutStartsAfterPlayingRatherThanAtLaunch() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(
                generation = 5L,
                context = videoContext(sessionToken = 5L),
            )
            coordinator.expectVideoOutput(generation = 51L)
            coordinator.observePlaybackState(state(PlaybackStatus.Loading))
            advanceTimeBy(PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS)
            runCurrent()

            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS - 1L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            runCurrent()
            assertTrue(signals.none { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(PlaybackHealthSignalKind.NoVideoOutput, signals.last().kind)
        }

    @Test
    fun repeatedPlayingUpdatesDoNotRestartNoVideoOutputTimeout() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(generation = 6L, context = videoContext(sessionToken = 6L))
            coordinator.expectVideoOutput(generation = 61L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            advanceTimeBy(2_000L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS - 2_000L)
            runCurrent()

            assertEquals(PlaybackHealthSignalKind.NoVideoOutput, signals.single().kind)
        }

    @Test
    fun stalePrepareOutputCannotSatisfyCurrentVideoOutputWait() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(generation = 7L, context = videoContext(sessionToken = 7L))
            coordinator.expectVideoOutput(generation = 72L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 100L))
            coordinator.observeVideoOutput(
                VideoOutputObservation(generation = 71L, presented = true, observedAtMs = 1L),
            )
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing, positionMs = 101L))
            runCurrent()

            assertEquals(PlaybackHealthSignalKind.NoVideoOutput, signals.single().kind)
        }

    @Test
    fun currentPrepareOutputCancelsNoVideoOutputTimeout() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(generation = 8L, context = videoContext(sessionToken = 8L))
            coordinator.expectVideoOutput(generation = 82L)
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            advanceTimeBy(2_000L)
            coordinator.observeVideoOutput(
                VideoOutputObservation(generation = 82L, presented = true, observedAtMs = 2_000L),
            )
            advanceTimeBy(PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS)
            runCurrent()

            assertTrue(signals.isEmpty())
        }

    @Test
    fun presentedStartupDoesNotMaskALaterSeekTimeoutOrRecoveryPrepare() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(generation = 12L, context = videoContext(sessionToken = 12L))
            coordinator.expectVideoOutput(generation = 120L)
            coordinator.observePlaybackTransition(transition(120L, 1L, PlaybackTransitionOutcome.Started))
            coordinator.observePlaybackTransition(transition(120L, 1L, PlaybackTransitionOutcome.Presented))
            coordinator.observePlaybackTransition(transition(120L, 2L, PlaybackTransitionOutcome.Started))
            coordinator.observePlaybackTransition(transition(120L, 2L, PlaybackTransitionOutcome.TimedOut))
            coordinator.observePlaybackTransition(transition(120L, 2L, PlaybackTransitionOutcome.TimedOut))

            assertEquals(1, signals.count { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })

            coordinator.expectVideoOutput(generation = 121L)
            coordinator.observePlaybackTransition(transition(121L, 1L, PlaybackTransitionOutcome.Started))
            coordinator.observePlaybackTransition(transition(121L, 1L, PlaybackTransitionOutcome.TimedOut))

            assertEquals(2, signals.count { signal -> signal.kind == PlaybackHealthSignalKind.NoVideoOutput })
        }

    @Test
    fun audioOnlySessionRejectsTypedVideoTransitionTimeout() =
        runTest {
            val signals = mutableListOf<PlaybackHealthSignal>()
            val coordinator = videoOutputCoordinator(signals)
            coordinator.start(
                generation = 13L,
                context = videoContext(sessionToken = 13L).copy(videoExpected = false),
            )
            coordinator.expectVideoOutput(generation = 130L)
            coordinator.observePlaybackTransition(transition(130L, 1L, PlaybackTransitionOutcome.Started))
            coordinator.observePlaybackTransition(transition(130L, 1L, PlaybackTransitionOutcome.TimedOut))

            assertTrue(signals.isEmpty())
        }

    @Test
    fun evidenceRestartClearsCurrentWindowBufferingAndSummaryReportsGuidanceGates() =
        runTest {
            var summary: PlaybackHealthSummary? = null
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onSessionEnded = { summary = it },
                )
            coordinator.start(
                generation = 11L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 11L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            coordinator.observePlaybackState(state(PlaybackStatus.Buffering))
            coordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
            coordinator.end()

            val ended = assertNotNull(summary)
            // Restarting DURING a buffering episode reopens the interval at the
            // boundary, so the new window legitimately contains buffering. Clearing
            // it outright would silently stop measuring the wait the user is still
            // experiencing — see PlaybackHealthEvaluatorTest for that contract.
            assertTrue(ended.bufferingIntervalOpenedSinceEvidenceRestart)
            assertEquals(PlaybackHealthGuidancePolicy.Actionable, ended.guidancePolicy)
            assertTrue(ended.guidancePublishable)
        }

    @Test
    fun evidenceRestartWhilePlayingReportsNoBufferingInTheNewWindow() =
        runTest {
            var summary: PlaybackHealthSummary? = null
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onSessionEnded = { summary = it },
                )
            coordinator.start(
                generation = 13L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 13L,
                        streamMode = StreamMode.DirectPlay,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                    ),
            )
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            coordinator.observePlaybackState(state(PlaybackStatus.Buffering))
            coordinator.observePlaybackState(state(PlaybackStatus.Playing))
            coordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
            coordinator.end()

            val ended = assertNotNull(summary)
            assertFalse(ended.bufferingIntervalOpenedSinceEvidenceRestart)
        }

    @Test
    fun offlineSummaryKeepsActionablePolicyButMarksGuidanceUnpublishable() =
        runTest {
            var summary: PlaybackHealthSummary? = null
            val coordinator =
                PlaybackHealthSessionCoordinator(
                    scope = this,
                    monotonicTimeMs = { testScheduler.currentTime },
                    measurementCapabilities = { PlaybackHealthMeasurementCapabilities.BufferingOnly },
                    onSessionEnded = { summary = it },
                )
            coordinator.start(
                generation = 12L,
                context =
                    PlaybackHealthSessionContext(
                        sessionToken = 12L,
                        streamMode = StreamMode.Offline,
                        guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
                        isOffline = true,
                    ),
            )
            coordinator.end()

            val ended = assertNotNull(summary)
            assertEquals(PlaybackHealthGuidancePolicy.Actionable, ended.guidancePolicy)
            assertFalse(ended.guidancePublishable)
        }

    private fun kotlinx.coroutines.test.TestScope.videoOutputCoordinator(
        signals: MutableList<PlaybackHealthSignal>,
    ): PlaybackHealthSessionCoordinator =
        PlaybackHealthSessionCoordinator(
            scope = this,
            monotonicTimeMs = { testScheduler.currentTime },
            measurementCapabilities = {
                PlaybackHealthMeasurementCapabilities(
                    hasReliableBufferingTransitions = true,
                    hasDroppedFrameMeasurements = false,
                    hasReliableFirstVideoOutput = true,
                )
            },
            onSignal = signals::add,
        )

    private fun videoContext(sessionToken: Long): PlaybackHealthSessionContext =
        PlaybackHealthSessionContext(
            sessionToken = sessionToken,
            streamMode = StreamMode.DirectPlay,
            guidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
            videoExpected = true,
        )

    private fun state(
        status: PlaybackStatus,
        positionMs: Long = 0L,
    ): PlaybackState =
        PlaybackState(
            status = status,
            positionMs = positionMs,
            durationMs = null,
            bufferedPositionMs = 0L,
        )
}

private fun transition(
    prepareEpoch: Long,
    sequence: Long,
    outcome: PlaybackTransitionOutcome,
) = PlaybackTransitionObservation(
    prepareEpoch = prepareEpoch,
    transitionSequence = sequence,
    outcome = outcome,
    observedAtMs = 1L,
)
