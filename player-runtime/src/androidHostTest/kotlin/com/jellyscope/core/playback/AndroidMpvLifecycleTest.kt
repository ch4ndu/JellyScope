// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidMpvLifecycleTest {
    @Test
    fun currentLoadedEofCompletesBeforeKeepOpenPauseCanDemoteIt() {
        val kernel = AndroidMpvLifecycleKernel()
        kernel.prepare(1L)

        assertEquals(AndroidMpvLifecycleDecision.Ready, kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(1L)).decision)
        assertEquals(
            AndroidMpvLifecycleDecision.Completed,
            kernel.transition(AndroidMpvLifecycleEvent.EofReached(1L, true)).decision,
        )
        assertEquals(AndroidMpvLifecycleOutcome.Completed, kernel.state.outcome)
        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.EndFile(1L)).decision,
        )
    }

    @Test
    fun eofBeforeNewFileLoadedCannotCompleteReusedContext() {
        val kernel = AndroidMpvLifecycleKernel()
        kernel.prepare(1L)
        kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(1L))
        kernel.prepare(2L)

        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.EofReached(2L, true)).decision,
        )
        assertEquals(
            AndroidMpvLifecycleDecision.Ready,
            kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(2L)).decision,
        )
        assertEquals(AndroidMpvLifecycleOutcome.Active, kernel.state.outcome)
    }

    @Test
    fun oldEndAndLateCallbacksAreIgnoredAfterReplaceStopAndRelease() {
        val kernel = AndroidMpvLifecycleKernel()
        kernel.prepare(1L)
        kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(1L))
        kernel.prepare(2L)

        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.EndFile(1L)).decision,
        )
        kernel.transition(AndroidMpvLifecycleEvent.Stop(2L))
        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.NativeFailure(2L, PlaybackError.Decoder)).decision,
        )
        kernel.prepare(3L)
        kernel.transition(AndroidMpvLifecycleEvent.Release(3L))
        assertTrue(kernel.state.released)
        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(3L)).decision,
        )
    }

    @Test
    fun timeoutFailsOnlyItsOwnUnloadedGeneration() {
        val kernel = AndroidMpvLifecycleKernel()
        kernel.prepare(1L)
        kernel.prepare(2L)

        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.StartupTimeout(1L, PlaybackError.Unknown)).decision,
        )
        assertEquals(
            AndroidMpvLifecycleDecision.Failed(PlaybackError.Decoder),
            kernel.transition(AndroidMpvLifecycleEvent.StartupTimeout(2L, PlaybackError.Decoder)).decision,
        )
    }

    @Test
    fun currentEndWithoutEofIsAtypedFailureAndExplicitStopStaysNonTerminal() {
        val kernel = AndroidMpvLifecycleKernel()
        kernel.prepare(1L)
        kernel.transition(AndroidMpvLifecycleEvent.FileLoaded(1L))

        assertEquals(
            AndroidMpvLifecycleDecision.Failed(PlaybackError.Unknown),
            kernel.transition(AndroidMpvLifecycleEvent.EndFile(1L)).decision,
        )

        kernel.prepare(2L)
        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.Stop(2L)).decision,
        )
        assertEquals(AndroidMpvLifecycleOutcome.Stopped, kernel.state.outcome)
        assertEquals(
            AndroidMpvLifecycleDecision.Ignored,
            kernel.transition(AndroidMpvLifecycleEvent.EndFile(2L)).decision,
        )
    }
}
