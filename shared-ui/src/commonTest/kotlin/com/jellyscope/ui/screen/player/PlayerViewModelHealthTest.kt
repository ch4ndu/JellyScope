// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.PLAYBACK_HEALTH_EXCLUSION_MS
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlayerViewModelHealthTest {
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
}
