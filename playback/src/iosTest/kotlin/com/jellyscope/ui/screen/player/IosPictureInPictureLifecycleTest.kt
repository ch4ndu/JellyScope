// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.IosPictureInPictureStartAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPictureInPictureLifecycleTest {
    @Test
    fun backgroundStartClosesOnlyWhenItsConfirmationDeadlineExpires() {
        val lifecycle = IosPictureInPictureLifecycle()

        assertTrue(
            lifecycle.beginStart(
                IosPictureInPictureStartAdmission.ExplicitStartRequested(SOURCE),
                requestedAtMs = 1_000L,
            ),
        )
        assertEquals(
            IosPictureInPictureBackgroundResult.AwaitConfirmation(3_000L),
            lifecycle.onDidEnterBackground(),
        )
        assertEquals(IosPictureInPictureDeadlineResult.None, lifecycle.onConfirmationDeadline(SOURCE, 2_999L))
        assertEquals(IosPictureInPictureDeadlineResult.Closed, lifecycle.onConfirmationDeadline(SOURCE, 3_000L))
        assertNull(lifecycle.sourceIdentity)
    }

    @Test
    fun currentStartBecomesActiveAndKeepsBackgroundPlaybackOpen() {
        val lifecycle = IosPictureInPictureLifecycle()
        lifecycle.beginStart(
            IosPictureInPictureStartAdmission.ExplicitStartRequested(SOURCE),
            requestedAtMs = 1_000L,
        )

        assertEquals(
            IosPictureInPictureStartResult.Started,
            lifecycle.onPictureInPictureStarted(SOURCE, observedAtMs = 1_500L),
        )
        assertEquals(IosPictureInPictureBackgroundResult.KeepOpen, lifecycle.onDidEnterBackground())
    }

    @Test
    fun foregroundReturnMakesLaterVlcStopARestoreInsteadOfABackgroundClose() {
        val lifecycle = IosPictureInPictureLifecycle()
        lifecycle.beginStart(
            IosPictureInPictureStartAdmission.ExplicitStartRequested(SOURCE),
            requestedAtMs = 1_000L,
        )
        lifecycle.onPictureInPictureStarted(SOURCE, observedAtMs = 1_500L)
        lifecycle.onDidEnterBackground()

        assertEquals(IosPictureInPictureForegroundResult.None, lifecycle.onDidBecomeActive(observedAtMs = 2_000L))
        val stopped = lifecycle.onPictureInPictureStopped(SOURCE, observedAtMs = 2_100L)
        assertEquals(IosPictureInPictureStopResult.Pending(deadlineAtMs = 4_100L, backgrounded = false), stopped)
        assertEquals(IosPictureInPictureDeadlineResult.Restored, lifecycle.onConfirmationDeadline(SOURCE, 4_100L))
    }

    @Test
    fun transientResignStopsAConfirmedStartWithoutClosingPlayback() {
        val lifecycle = IosPictureInPictureLifecycle()
        lifecycle.beginStart(
            IosPictureInPictureStartAdmission.AutomaticStartPending(SOURCE),
            requestedAtMs = 1_000L,
        )
        lifecycle.onPictureInPictureStarted(SOURCE, observedAtMs = 1_100L)

        assertEquals(
            IosPictureInPictureForegroundResult.RequestStopAndRestore(SOURCE, deadlineAtMs = 3_200L),
            lifecycle.onDidBecomeActive(observedAtMs = 1_200L),
        )
        assertEquals(
            IosPictureInPictureStopResult.Restored,
            lifecycle.onPictureInPictureStopped(SOURCE, observedAtMs = 1_300L),
        )
    }

    @Test
    fun delayedStartAfterDeadlineIsRejectedAndRequestedToStop() {
        val lifecycle = IosPictureInPictureLifecycle()
        lifecycle.beginStart(
            IosPictureInPictureStartAdmission.AutomaticStartPending(SOURCE),
            requestedAtMs = 1_000L,
        )
        lifecycle.onDidEnterBackground()

        assertEquals(
            IosPictureInPictureStartResult.RequestStop,
            lifecycle.onPictureInPictureStarted(SOURCE, observedAtMs = 3_001L),
        )
    }

    @Test
    fun staleSourceCallbacksCannotSettleTheCurrentSource() {
        val lifecycle = IosPictureInPictureLifecycle()
        lifecycle.beginStart(
            IosPictureInPictureStartAdmission.ExplicitStartRequested(SOURCE),
            requestedAtMs = 1_000L,
        )

        assertEquals(
            IosPictureInPictureStartResult.Ignored,
            lifecycle.onPictureInPictureStarted(STALE_SOURCE, observedAtMs = 1_100L),
        )
        assertEquals(
            IosPictureInPictureSourceInvalidationResult.Ignored,
            lifecycle.onSourceInvalidated(STALE_SOURCE, isAppBackgrounded = true),
        )
        assertEquals(SOURCE, lifecycle.sourceIdentity)
        assertFalse(lifecycle.canAdmitStart())
    }

    private companion object {
        const val SOURCE = 7L
        const val STALE_SOURCE = 6L
    }
}
