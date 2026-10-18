// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** VLCKit reports terminal conditions as sticky states, not one-shot events. */
class VlcTerminalEventLatchTest {
    @Test
    fun freshSessionIdlingInStoppedIsNotAnEnding() {
        val latch = VlcTerminalEventLatch()
        latch.onSessionReset()

        // A newly created VLCMediaPlayer sits in Stopped before anything opens.
        // Treating that as terminal published Buffering under play intent, which
        // rendered as an indefinite spinner after every re-plan.
        assertNull(latch.observe(VlcTerminalEvent.Stopped))
        assertNull(latch.observe(VlcTerminalEvent.Stopped))
    }

    @Test
    fun heldEndedDecidesExactlyOnce() {
        val latch = VlcTerminalEventLatch()
        latch.onSessionReset()
        latch.observe(null)

        assertEquals(VlcTerminalEvent.EndReached, latch.observe(VlcTerminalEvent.EndReached))
        // The generic latch still suppresses a repeated level if an older caller
        // supplies EndReached directly.
        assertNull(latch.observe(VlcTerminalEvent.EndReached))
        assertNull(latch.observe(VlcTerminalEvent.EndReached))
    }

    @Test
    fun trailingStoppedAfterEndedIsASingleEdge() {
        val latch = VlcTerminalEventLatch()
        latch.onSessionReset()
        latch.observe(null)
        latch.observe(VlcTerminalEvent.EndReached)

        // Exactly one Stopped edge may consume the trailing-Stopped one-shot.
        assertEquals(VlcTerminalEvent.Stopped, latch.observe(VlcTerminalEvent.Stopped))
        assertNull(latch.observe(VlcTerminalEvent.Stopped))
    }

    @Test
    fun aLaterUnrelatedStoppedIsStillAnEdge() {
        val latch = VlcTerminalEventLatch()
        latch.onSessionReset()
        latch.observe(null)
        latch.observe(VlcTerminalEvent.Stopped)

        // Leaving the terminal level re-arms it, so a genuine later stop
        // (network drop) is still decided.
        assertNull(latch.observe(null))
        assertEquals(VlcTerminalEvent.Stopped, latch.observe(VlcTerminalEvent.Stopped))
    }

    @Test
    fun vlcKit4StoppedOnlySequenceDecidesExactlyOnce() {
        val latch = VlcTerminalEventLatch()
        latch.onSessionReset()

        // Opening/playing leaves the initial idle Stopped level, arming the one
        // later Stopped transition that VLCKit 4 uses for every terminal cause.
        assertNull(latch.observe(null))
        assertEquals(VlcTerminalEvent.Stopped, latch.observe(VlcTerminalEvent.Stopped))
        assertNull(latch.observe(VlcTerminalEvent.Stopped))
    }

    @Test
    fun aNewSessionAfterCompletionDoesNotReplayTheOldEnding() {
        val latch = VlcTerminalEventLatch()
        latch.observe(null)
        latch.observe(VlcTerminalEvent.EndReached)

        latch.onSessionReset()

        assertNull(latch.observe(VlcTerminalEvent.Stopped))
    }
}
