// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The six player controllers all render one decision table. These tests are that
 * table's only automated guard: `LibVlcPlayerController` in particular has no host
 * test of its own, so the guarded rendering is covered here rather than in a
 * controller test.
 */
class InitialAudioActivationTest {
    @Test
    fun directPlayWithATargetAwaitsNativeMapping() {
        val decision = initialAudioActivationFor(plan(StreamMode.DirectPlay, target))

        assertEquals(InitialAudioActivation.AwaitNativeMapping(target), decision)
        assertEquals(AudioActivationState.Pending(target), decision.toAudioActivationState())
    }

    @Test
    fun everyNonDirectPlayModeWithATargetIsAlreadyActive() {
        // Offline is included deliberately: it has no server in the path, but the
        // audio choice is still fixed before the player sees it, and every existing
        // controller copy branched only on DirectPlay.
        val nonDirectPlayModes = listOf(StreamMode.DirectStream, StreamMode.Transcode, StreamMode.Offline)

        nonDirectPlayModes.forEach { streamMode ->
            val decision = initialAudioActivationFor(plan(streamMode, target))

            assertEquals(InitialAudioActivation.AlreadyActive(target), decision, "streamMode=$streamMode")
            assertEquals(
                AudioActivationState.Active(target),
                decision.toAudioActivationState(),
                "streamMode=$streamMode",
            )
        }
    }

    @Test
    fun anAbsentTargetIsNoneRegardlessOfStreamMode() {
        StreamMode.entries.forEach { streamMode ->
            val decision = initialAudioActivationFor(plan(streamMode, audioActivationTarget = null))

            assertEquals(InitialAudioActivation.None, decision, "streamMode=$streamMode")
            assertEquals(AudioActivationState.None, decision.toAudioActivationState(), "streamMode=$streamMode")
        }
    }

    @Test
    fun applyInitialClearsForNone() {
        val recorder = ConfirmationRecorder()

        recorder.confirmation.applyInitial(InitialAudioActivation.None)

        assertEquals(listOf<AudioActivationState>(AudioActivationState.None), recorder.published)
    }

    @Test
    fun applyInitialPublishesOnlyPendingWhileAwaitingNativeMapping() {
        val recorder = ConfirmationRecorder()

        recorder.confirmation.applyInitial(InitialAudioActivation.AwaitNativeMapping(target))

        assertEquals(listOf<AudioActivationState>(AudioActivationState.Pending(target)), recorder.published)
    }

    @Test
    fun applyInitialPublishesPendingBeforeActiveForAlreadyActive() {
        val recorder = ConfirmationRecorder()

        recorder.confirmation.applyInitial(InitialAudioActivation.AlreadyActive(target))

        // Order matters: confirm() only takes effect against a matching Pending
        // state, so a reversed or collapsed sequence would silently publish nothing.
        assertEquals(
            listOf<AudioActivationState>(
                AudioActivationState.Pending(target),
                AudioActivationState.Active(target),
            ),
            recorder.published,
        )
    }

    @Test
    fun applyInitialWithoutClearingPublishesNothingForNone() {
        val recorder = ConfirmationRecorder()

        recorder.confirmation.applyInitialWithoutClearing(InitialAudioActivation.None)

        // This is the Android LibVLC contract: that controller already published
        // None from its state projection, and clearing here would also cancel a
        // timeout it expects to keep.
        assertTrue(recorder.published.isEmpty(), "expected no publication, got ${recorder.published}")
    }

    @Test
    fun applyInitialWithoutClearingMatchesApplyInitialForBothTargetedCases() {
        val awaiting = ConfirmationRecorder()
        val active = ConfirmationRecorder()

        awaiting.confirmation.applyInitialWithoutClearing(InitialAudioActivation.AwaitNativeMapping(target))
        active.confirmation.applyInitialWithoutClearing(InitialAudioActivation.AlreadyActive(target))

        assertEquals(listOf<AudioActivationState>(AudioActivationState.Pending(target)), awaiting.published)
        assertEquals(
            listOf<AudioActivationState>(
                AudioActivationState.Pending(target),
                AudioActivationState.Active(target),
            ),
            active.published,
        )
    }

    private class ConfirmationRecorder {
        val published = mutableListOf<AudioActivationState>()
        private var state: AudioActivationState = AudioActivationState.None

        val confirmation =
            AudioActivationConfirmation(
                scope = TestScope(),
                platform = PlaybackDiagnosticPlatform.Shared,
                currentState = { state },
                publish = { next ->
                    state = next
                    published += next
                },
            )
    }

    private companion object {
        val target = AudioActivationTarget(requestId = 7L, itemId = "item-1", streamIndex = 2)

        fun plan(
            streamMode: StreamMode,
            audioActivationTarget: AudioActivationTarget?,
        ): PlaybackPlan =
            PlaybackPlan(
                itemId = "item-1",
                mediaSourceId = "source-1",
                startPositionMs = 0L,
                streamMode = streamMode,
                streamUrl = "https://jellyfin.example/Videos/item-1/stream",
                progressReportingPolicy = ProgressReportingPolicy(10_000L),
                audioActivationTarget = audioActivationTarget,
            )
    }
}
