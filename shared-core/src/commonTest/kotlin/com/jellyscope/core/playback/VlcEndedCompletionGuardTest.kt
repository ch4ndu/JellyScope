// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlcEndedCompletionGuardTest {
    @Test
    fun acceptsCompletionNearTheKnownEnd() {
        assertTrue(isVlcEndedNearCompletion(lastKnownPositionMs = 59_500L, durationMs = 60_000L))
    }

    @Test
    fun acceptsCompletionExactlyAtTolerance() {
        assertTrue(
            isVlcEndedNearCompletion(
                lastKnownPositionMs = 60_000L - VLC_ENDED_NEAR_END_TOLERANCE_MS,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun rejectsMidStreamEnded() {
        assertFalse(isVlcEndedNearCompletion(lastKnownPositionMs = 30_000L, durationMs = 60_000L))
    }

    @Test
    fun rejectsJustOutsideTolerance() {
        assertFalse(
            isVlcEndedNearCompletion(
                lastKnownPositionMs = 60_000L - VLC_ENDED_NEAR_END_TOLERANCE_MS - 1L,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun stoppedClockZeroReadIsCoveredByLastPublishedPosition() {
        // At the Ended callback libvlc's clock can read 0; the controller passes
        // max(liveRead, lastPublished). A genuine natural end therefore still
        // passes the guard through the previously published position.
        val lastPublishedPositionMs = 59_900L
        val liveReadMs = 0L
        assertTrue(
            isVlcEndedNearCompletion(
                lastKnownPositionMs = maxOf(liveReadMs, lastPublishedPositionMs),
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun unknownDurationCannotBeGuarded() {
        assertTrue(isVlcEndedNearCompletion(lastKnownPositionMs = 0L, durationMs = null))
        assertTrue(isVlcEndedNearCompletion(lastKnownPositionMs = 10_000L, durationMs = 0L))
    }
}

class VlcPlaybackProgressTest {
    @Test
    fun progressRequiresAdvancePastTheBaseline() {
        assertTrue(
            hasVlcPlaybackProgressed(
                positionMs = 5_000L + VLC_PROGRESS_ADVANCE_MS + 1L,
                baselineMs = 5_000L,
                playing = true,
                seekInFlight = false,
            ),
        )
    }

    @Test
    fun sittingAtTheBaselineIsNotProgress() {
        assertFalse(
            hasVlcPlaybackProgressed(
                positionMs = 5_000L,
                baselineMs = 5_000L,
                playing = true,
                seekInFlight = false,
            ),
        )
    }

    @Test
    fun jitterWithinTheThresholdIsNotProgress() {
        assertFalse(
            hasVlcPlaybackProgressed(
                positionMs = 5_000L + VLC_PROGRESS_ADVANCE_MS,
                baselineMs = 5_000L,
                playing = true,
                seekInFlight = false,
            ),
        )
    }

    @Test
    fun seekOvershootIsNotProgress() {
        // The resume seek accepts landing past its target, so a position far
        // beyond the baseline proves nothing while the seek is still in flight.
        assertFalse(
            hasVlcPlaybackProgressed(
                positionMs = 600_000L,
                baselineMs = 5_000L,
                playing = true,
                seekInFlight = true,
            ),
        )
    }

    @Test
    fun advanceWhileNotPlayingIsNotProgress() {
        assertFalse(
            hasVlcPlaybackProgressed(
                positionMs = 600_000L,
                baselineMs = 5_000L,
                playing = false,
                seekInFlight = false,
            ),
        )
    }

    @Test
    fun startFromZeroProgressesOnceThePlayheadMoves() {
        assertTrue(
            hasVlcPlaybackProgressed(
                positionMs = 1_000L,
                baselineMs = 0L,
                playing = true,
                seekInFlight = false,
            ),
        )
    }
}

class VlcEndOfStreamEvidenceTest {
    @Test
    fun prepareSeedsTheBaselineFromTheStartPosition() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 590_000L)
        assertEquals(590_000L, evidence.baselineMs)
        assertFalse(evidence.playbackEverProgressed)
    }

    @Test
    fun negativeStartPositionClampsToZero() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = -1L)
        assertEquals(0L, evidence.baselineMs)
    }

    @Test
    fun resumeSeekLandingDoesNotProveProgress() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 590_000L)
        // The start seek lands (and may overshoot) while still in flight.
        evidence.onPositionSample(positionMs = 592_000L, playing = true, seekInFlight = true)
        assertFalse(evidence.playbackEverProgressed)
    }

    @Test
    fun baselineSurvivesTheSeekCompletingSoLaterSamplesAreStillJudged() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 590_000L)
        evidence.onPositionSample(positionMs = 590_100L, playing = true, seekInFlight = true)
        // Seek finished; the controller's pendingStartPositionMs is now null, but the
        // baseline must still be 590_000 or this sample would look like progress.
        evidence.onPositionSample(positionMs = 590_100L, playing = true, seekInFlight = false)
        assertFalse(evidence.playbackEverProgressed)
        assertEquals(590_000L, evidence.baselineMs)

        evidence.onPositionSample(positionMs = 591_000L, playing = true, seekInFlight = false)
        assertTrue(evidence.playbackEverProgressed)
    }

    @Test
    fun progressLatchIsMonotonicWithinAPrepare() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        assertTrue(evidence.playbackEverProgressed)
        // A pause, or a seek back before the baseline, cannot un-prove progress.
        evidence.onPositionSample(positionMs = 0L, playing = false, seekInFlight = false)
        assertTrue(evidence.playbackEverProgressed)
    }

    @Test
    fun prepareClearsEveryFlagFromThePreviousItem() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        evidence.onEndRejected()
        assertTrue(evidence.shouldLogRejection())

        evidence.onPrepare(startPositionMs = 0L)
        assertFalse(evidence.playbackEverProgressed)
        assertFalse(evidence.endRejectedAwaitingStopped)
        assertTrue(evidence.shouldLogRejection())
    }

    @Test
    fun stopClearsProgressAndPendingRejection() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        evidence.onEndRejected()

        evidence.onStop()
        assertFalse(evidence.playbackEverProgressed)
        assertFalse(evidence.endRejectedAwaitingStopped)
    }

    @Test
    fun rejectionLogsOnlyOncePerPrepare() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        assertTrue(evidence.shouldLogRejection())
        assertFalse(evidence.shouldLogRejection())
    }

    @Test
    fun ignoredSampleCannotArmProgress() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 590_000L)

        // A seek resolving far past the baseline (overshoot) must not count.
        evidence.ignoreNextSample()
        evidence.onPositionSample(positionMs = 600_000L, playing = true, seekInFlight = false)
        assertFalse(evidence.playbackEverProgressed)
    }

    @Test
    fun onlyOneSampleIsIgnored() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 590_000L)

        evidence.ignoreNextSample()
        evidence.onPositionSample(positionMs = 600_000L, playing = true, seekInFlight = false)
        assertFalse(evidence.playbackEverProgressed)

        // The following, playback-driven sample is judged normally.
        evidence.onPositionSample(positionMs = 600_500L, playing = true, seekInFlight = false)
        assertTrue(evidence.playbackEverProgressed)
    }

    @Test
    fun ignoreFlagDoesNotSurviveANewPrepareOrStop() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.ignoreNextSample()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        assertTrue(evidence.playbackEverProgressed)

        val afterStop = VlcEndOfStreamEvidence()
        afterStop.onPrepare(startPositionMs = 0L)
        afterStop.ignoreNextSample()
        afterStop.onStop()
        afterStop.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        assertTrue(afterStop.playbackEverProgressed)
    }

    @Test
    fun consumingTheRejectionClearsOnlyTheOneShot() {
        val evidence = VlcEndOfStreamEvidence()
        evidence.onPrepare(startPositionMs = 0L)
        evidence.onPositionSample(positionMs = 30_000L, playing = true, seekInFlight = false)
        evidence.onEndRejected()
        assertTrue(evidence.endRejectedAwaitingStopped)

        evidence.consumeEndRejected()
        assertFalse(evidence.endRejectedAwaitingStopped)
        assertTrue(evidence.playbackEverProgressed)
    }
}

class VlcTerminalStatusTest {
    private fun endReached(
        currentStatus: PlaybackStatus = PlaybackStatus.Playing,
        playbackEverProgressed: Boolean = true,
        nativePositionMs: Long = 0L,
        lastPublishedPositionMs: Long = 59_900L,
        durationMs: Long? = 60_000L,
    ) = resolveVlcTerminalStatus(
        event = VlcTerminalEvent.EndReached,
        currentStatus = currentStatus,
        playIntent = true,
        playbackEverProgressed = playbackEverProgressed,
        endRejectedAwaitingStopped = false,
        nativePositionMs = nativePositionMs,
        lastPublishedPositionMs = lastPublishedPositionMs,
        durationMs = durationMs,
    )

    private fun stopped(
        currentStatus: PlaybackStatus,
        playIntent: Boolean = true,
        endRejectedAwaitingStopped: Boolean = false,
        nativePositionMs: Long = 0L,
        lastPublishedPositionMs: Long = 60_000L,
        durationMs: Long? = 60_000L,
    ) = resolveVlcTerminalStatus(
        event = VlcTerminalEvent.Stopped,
        currentStatus = currentStatus,
        playIntent = playIntent,
        playbackEverProgressed = true,
        endRejectedAwaitingStopped = endRejectedAwaitingStopped,
        nativePositionMs = nativePositionMs,
        lastPublishedPositionMs = lastPublishedPositionMs,
        durationMs = durationMs,
    )

    @Test
    fun genuineEndCompletesAtTheDuration() {
        val decision = endReached()
        assertEquals(PlaybackStatus.Completed, decision.publishedStatus)
        // Not the post-end native zero: a stop report at zero would clear the
        // item's watched state on the server.
        assertEquals(60_000L, decision.positionMs)
        assertFalse(decision.endRejected)
    }

    @Test
    fun endWithoutObservedProgressIsRejected() {
        // A failed open: unknown duration would otherwise pass the near-end guard.
        val decision =
            endReached(
                playbackEverProgressed = false,
                lastPublishedPositionMs = 0L,
                durationMs = null,
            )
        assertEquals(PlaybackStatus.Paused, decision.publishedStatus)
        assertTrue(decision.endRejected)
    }

    @Test
    fun resumeNearTheEndThatNeverPlayedIsRejected() {
        // The resume seek alone put the playhead near the end, so the near-end
        // guard passes; only the progress latch rejects this.
        val decision =
            endReached(
                playbackEverProgressed = false,
                lastPublishedPositionMs = 59_800L,
            )
        assertEquals(PlaybackStatus.Paused, decision.publishedStatus)
        assertEquals(59_800L, decision.positionMs)
        assertTrue(decision.endRejected)
    }

    @Test
    fun midStreamEndIsRejectedAtTheLastKnownPosition() {
        val decision = endReached(lastPublishedPositionMs = 30_000L)
        assertEquals(PlaybackStatus.Paused, decision.publishedStatus)
        assertEquals(30_000L, decision.positionMs)
        assertTrue(decision.endRejected)
    }

    @Test
    fun stoppedTrailingACompletionPreservesIt() {
        // libVLC emits Stopped right after EndReached. Mapping it to Buffering
        // was what cancelled the Up Next countdown and stalled the queue.
        val decision = stopped(currentStatus = PlaybackStatus.Completed)
        assertNull(decision.publishedStatus)
        assertFalse(decision.consumeEndRejected)
    }

    @Test
    fun stoppedTrailingARejectedEndPreservesThePause() {
        val decision =
            stopped(
                currentStatus = PlaybackStatus.Paused,
                endRejectedAwaitingStopped = true,
            )
        assertNull(decision.publishedStatus)
        assertTrue(decision.consumeEndRejected)
    }

    @Test
    fun laterUnrelatedStoppedStillBuffers() {
        // Once the trailing Stopped consumed the one-shot, a subsequent Stopped
        // (network drop, error) must map normally again.
        val decision =
            stopped(
                currentStatus = PlaybackStatus.Playing,
                endRejectedAwaitingStopped = false,
            )
        assertEquals(PlaybackStatus.Buffering, decision.publishedStatus)
    }

    @Test
    fun stoppedWithoutPlayIntentChangesNothing() {
        val decision =
            stopped(
                currentStatus = PlaybackStatus.Paused,
                playIntent = false,
            )
        assertNull(decision.publishedStatus)
    }

    @Test
    fun deadNativeClockNeverDemotesTheKnownPosition() {
        // Native reads -1/0 after the stream ends; the last published position wins.
        val decision =
            endReached(
                nativePositionMs = 0L,
                lastPublishedPositionMs = 59_950L,
                durationMs = null,
            )
        assertEquals(PlaybackStatus.Completed, decision.publishedStatus)
        assertEquals(59_950L, decision.positionMs)
    }
}

class VlcKit4StoppedTransitionTest {
    @Test
    fun knownNearEndStopWithProgressClassifiesAsCompletion() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = true,
                playbackEverProgressed = true,
                nativePositionMs = 0L,
                lastPublishedPositionMs = 59_900L,
                durationMs = 60_000L,
            )

        assertEquals(VlcTerminalEvent.EndReached, event)
    }

    @Test
    fun nativeNearEndPositionCanSupplyCompletionEvidence() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = true,
                playbackEverProgressed = true,
                nativePositionMs = 59_950L,
                lastPublishedPositionMs = 0L,
                durationMs = 60_000L,
            )

        assertEquals(VlcTerminalEvent.EndReached, event)
    }

    @Test
    fun midStreamStopDoesNotComplete() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = true,
                playbackEverProgressed = true,
                nativePositionMs = 30_000L,
                lastPublishedPositionMs = 30_100L,
                durationMs = 60_000L,
            )

        assertEquals(VlcTerminalEvent.Stopped, event)
    }

    @Test
    fun unknownDurationNeverCompletes() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = true,
                playbackEverProgressed = true,
                nativePositionMs = 59_950L,
                lastPublishedPositionMs = 59_900L,
                durationMs = null,
            )

        assertEquals(VlcTerminalEvent.Stopped, event)
    }

    @Test
    fun resumeNearEndWithoutPostSeekProgressDoesNotComplete() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = true,
                playbackEverProgressed = false,
                nativePositionMs = 59_950L,
                lastPublishedPositionMs = 59_950L,
                durationMs = 60_000L,
            )

        assertEquals(VlcTerminalEvent.Stopped, event)
    }

    @Test
    fun explicitStopDoesNotComplete() {
        val event =
            classifyVlcKit4StoppedTransition(
                playIntent = false,
                playbackEverProgressed = true,
                nativePositionMs = 59_950L,
                lastPublishedPositionMs = 59_950L,
                durationMs = 60_000L,
            )

        assertEquals(VlcTerminalEvent.Stopped, event)
    }

    @Test
    fun ambiguousStopWithPlayIntentPausesAtHonestPosition() {
        val decision =
            resolveVlcKit4StoppedTransition(
                currentStatus = PlaybackStatus.Playing,
                playIntent = true,
                nativePositionMs = 0L,
                lastPublishedPositionMs = 31_000L,
            )

        assertEquals(PlaybackStatus.Paused, decision.publishedStatus)
        assertEquals(31_000L, decision.positionMs)
    }

    @Test
    fun explicitStopPublishesNothing() {
        val decision =
            resolveVlcKit4StoppedTransition(
                currentStatus = PlaybackStatus.Playing,
                playIntent = false,
                nativePositionMs = 30_000L,
                lastPublishedPositionMs = 31_000L,
            )

        assertNull(decision.publishedStatus)
        assertEquals(0L, decision.positionMs)
    }

    @Test
    fun trailingStopCannotDemoteCompletion() {
        val decision =
            resolveVlcKit4StoppedTransition(
                currentStatus = PlaybackStatus.Completed,
                playIntent = true,
                nativePositionMs = 0L,
                lastPublishedPositionMs = 60_000L,
            )

        assertNull(decision.publishedStatus)
        assertEquals(0L, decision.positionMs)
    }
}

class VlcSingleFlightCompletionGateTest {
    @Test
    fun twoRapidRequestsCompleteExactlyOnceAndOnlyFirstIsAdmitted() {
        val gate = VlcSingleFlightCompletionGate()
        var firstCompletions = 0
        var secondCompletions = 0
        val completionOrder = mutableListOf<String>()

        val firstToken =
            gate.admit(generation = 7L) {
                firstCompletions++
                completionOrder += "completion"
            }
        val secondToken = gate.admit(generation = 7L) { secondCompletions++ }

        assertNotNull(firstToken)
        assertNull(secondToken)
        assertEquals(0, firstCompletions)
        assertEquals(1, secondCompletions)
        assertTrue(
            gate.complete(firstToken, generation = 7L) {
                completionOrder += "before"
            },
        )
        assertFalse(gate.complete(firstToken, generation = 7L))
        assertEquals(1, firstCompletions)
        assertEquals(1, secondCompletions)
        assertEquals(listOf("before", "completion"), completionOrder)
    }

    @Test
    fun staleCompletionCannotConsumeNewerRequest() {
        val gate = VlcSingleFlightCompletionGate()
        var completions = 0
        val oldToken = gate.admit(generation = 3L) { completions++ }
        assertNotNull(oldToken)
        gate.cancel()
        val newToken = gate.admit(generation = 4L) { completions++ }
        assertNotNull(newToken)

        assertFalse(gate.complete(oldToken, generation = 3L))
        assertEquals(1, completions)
        assertTrue(gate.complete(newToken, generation = 4L))
        assertEquals(2, completions)
    }
}
