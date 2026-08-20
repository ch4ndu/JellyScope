// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlcKitPlaybackTransitionTest {
    @Test
    fun playingVideoSeekRequiresArrivalLaterClockAdvanceAndTwoPictureAdvances() {
        val transition = VlcKitPlaybackTransition()
        val initial =
            transition.begin(
                generation = 7L,
                kind = VlcKitPlaybackTransitionKind.Seek,
                targetPositionMs = 10_000L,
                playIntent = true,
                videoExpected = true,
                requiredPictureAdvances = 2,
                arrivalToleranceMs = 100L,
                transitionSequence = 3L,
                initialDisplayedPictures = 20L,
            )

        assertEquals(VlcKitPlaybackTransitionState.Pending, initial.state)
        assertEquals(10_000L, initial.publishedPositionMs)
        assertTrue(initial.forceBuffering)
        assertTrue(transition.observePosition(7L, 10_050L)?.arrivedNow == true)
        assertFalse(transition.observeDisplayedPictures(7L, 21L)?.becameReady == true)
        assertFalse(transition.observePosition(7L, 10_100L)?.becameReady == true)

        val ready = transition.observeDisplayedPictures(7L, 22L)

        assertEquals(VlcKitPlaybackTransitionState.Ready, ready?.state)
        assertTrue(ready?.becameReady == true)
        assertFalse(ready?.forceBuffering == true)
    }

    @Test
    fun pausedSeekSettlesAtTargetWithoutClockOrPictureProgress() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 2L,
            kind = VlcKitPlaybackTransitionKind.Seek,
            targetPositionMs = 4_000L,
            playIntent = false,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 50L,
            transitionSequence = null,
            initialDisplayedPictures = 8L,
        )

        val ready = transition.observePosition(2L, 4_020L)

        assertEquals(VlcKitPlaybackTransitionState.Ready, ready?.state)
        assertTrue(ready?.becameReady == true)
        assertFalse(ready?.forceBuffering == true)
    }

    @Test
    fun replacementAndCounterResetCannotSettleTheWrongTransition() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 4L,
            kind = VlcKitPlaybackTransitionKind.Seek,
            targetPositionMs = 1_000L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 0L,
            transitionSequence = 1L,
            initialDisplayedPictures = 10L,
        )
        transition.begin(
            generation = 4L,
            kind = VlcKitPlaybackTransitionKind.Seek,
            targetPositionMs = 2_000L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 0L,
            transitionSequence = 2L,
            initialDisplayedPictures = 10L,
        )

        assertNull(transition.timeout(4L, 1L))
        assertNull(transition.observePosition(3L, 2_000L))
        transition.observePosition(4L, 2_000L)
        transition.observePosition(4L, 2_001L)
        transition.observeDisplayedPictures(4L, 11L)
        transition.observeDisplayedPictures(4L, 1L)
        transition.observeDisplayedPictures(4L, 2L)
        assertEquals(VlcKitPlaybackTransitionState.Pending, transition.currentDecision(4L)?.state)

        val ready = transition.observeDisplayedPictures(4L, 3L)

        assertEquals(VlcKitPlaybackTransitionState.Ready, ready?.state)
    }

    @Test
    fun audioOnlyPlaybackNeedsClockProgressButNoPicturesOrVideoTimeout() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 9L,
            kind = VlcKitPlaybackTransitionKind.Prepare,
            targetPositionMs = 0L,
            playIntent = true,
            videoExpected = false,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 0L,
            transitionSequence = null,
            initialDisplayedPictures = null,
        )

        transition.observePosition(9L, 0L)
        val ready = transition.observePosition(9L, 1L)

        assertEquals(VlcKitPlaybackTransitionState.Ready, ready?.state)
        assertNull(transition.timeout(9L, 1L))
    }

    @Test
    fun firstPictureSampleCanProveFramesAlreadyDisplayedForNewMedia() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 11L,
            kind = VlcKitPlaybackTransitionKind.Prepare,
            targetPositionMs = 0L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 0L,
            transitionSequence = 4L,
            initialDisplayedPictures = null,
        )

        transition.observePosition(11L, 0L)
        transition.observePosition(11L, 40L)
        val ready = transition.observeDisplayedPictures(11L, 3L)

        assertEquals(VlcKitPlaybackTransitionState.Ready, ready?.state)
        assertTrue(ready?.becameReady == true)
        assertFalse(ready?.forceBuffering == true)
    }

    @Test
    fun resumeAcceptsFirstClockSamplePastToleranceWithoutWeakeningSeekArrival() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 12L,
            kind = VlcKitPlaybackTransitionKind.Resume,
            targetPositionMs = 10_000L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 2_000L,
            transitionSequence = 5L,
            initialDisplayedPictures = 4L,
        )

        assertTrue(transition.observePosition(12L, 12_500L)?.arrivedNow == true)
        transition.observeDisplayedPictures(12L, 6L)
        val resumed = transition.observePosition(12L, 12_501L)
        assertEquals(VlcKitPlaybackTransitionState.Ready, resumed?.state)

        transition.begin(
            generation = 12L,
            kind = VlcKitPlaybackTransitionKind.Seek,
            targetPositionMs = 10_000L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 2_000L,
            transitionSequence = 6L,
            initialDisplayedPictures = 6L,
        )

        assertFalse(transition.observePosition(12L, 12_500L)?.arrivedNow == true)
        assertEquals(VlcKitPlaybackTransitionState.Pending, transition.currentDecision(12L)?.state)
    }

    @Test
    fun timedOutTransitionReleasesUiHoldAndFollowsLaterNativeClock() {
        val transition = VlcKitPlaybackTransition()
        transition.begin(
            generation = 13L,
            kind = VlcKitPlaybackTransitionKind.Resume,
            targetPositionMs = 27_000L,
            playIntent = true,
            videoExpected = true,
            requiredPictureAdvances = 2,
            arrivalToleranceMs = 2_000L,
            transitionSequence = 7L,
            initialDisplayedPictures = 0L,
        )
        transition.observePosition(13L, 27_000L)

        val timedOut = transition.timeout(13L, 7L)

        assertEquals(VlcKitPlaybackTransitionState.TimedOut, timedOut?.state)
        assertFalse(timedOut?.forceBuffering == true)
        assertEquals(27_000L, timedOut?.publishedPositionMs)

        val laterClock = transition.observePosition(13L, 29_500L)

        assertEquals(VlcKitPlaybackTransitionState.TimedOut, laterClock?.state)
        assertFalse(laterClock?.forceBuffering == true)
        assertEquals(29_500L, laterClock?.publishedPositionMs)
    }

    @Test
    fun counterZeroTimeoutAdmitsPiPOnlyForAnActiveNativeVideoOutput() {
        assertTrue(
            shouldLatchVlcKitPictureInPictureContent(
                transitionState = VlcKitPlaybackTransitionState.TimedOut,
                hasVideoOut = true,
                nativePlaying = true,
            ),
        )
        assertFalse(
            shouldLatchVlcKitPictureInPictureContent(
                transitionState = VlcKitPlaybackTransitionState.TimedOut,
                hasVideoOut = false,
                nativePlaying = true,
            ),
        )
        assertFalse(
            shouldLatchVlcKitPictureInPictureContent(
                transitionState = VlcKitPlaybackTransitionState.TimedOut,
                hasVideoOut = true,
                nativePlaying = false,
            ),
        )
        assertTrue(
            shouldLatchVlcKitPictureInPictureContent(
                transitionState = VlcKitPlaybackTransitionState.Ready,
                hasVideoOut = false,
                nativePlaying = false,
            ),
        )
    }
}
