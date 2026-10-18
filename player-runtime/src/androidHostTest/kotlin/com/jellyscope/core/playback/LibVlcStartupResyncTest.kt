// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibVlcStartupResyncTest {
    @Test
    fun resumeRelockPredicatesKeepEveryStartupBoundary() {
        data class RelockCase(
            val state: LibVlcResumeOutputRelockState,
            val currentGeneration: Long,
            val playIntent: Boolean,
            val shouldRelock: Boolean,
            val pending: Boolean,
        )
        listOf(
            RelockCase(resumeArrived(), 7L, true, shouldRelock = true, pending = true),
            RelockCase(
                resumeArrived().copy(awaitingFirstOutput = false, relockConsumed = true),
                7L,
                true,
                shouldRelock = false,
                pending = false,
            ),
            RelockCase(LibVlcResumeOutputRelockState(), 7L, true, shouldRelock = false, pending = false),
            RelockCase(resumeArrived(), 8L, true, shouldRelock = false, pending = false),
            RelockCase(resumeArrived(generation = 8L), 8L, true, shouldRelock = true, pending = true),
            RelockCase(resumeArrived(), 7L, false, shouldRelock = false, pending = true),
            RelockCase(resumeArrived().copy(relockConsumed = true), 7L, true, shouldRelock = false, pending = false),
        ).forEach { case ->
            assertEquals(
                case.shouldRelock,
                shouldRelockLibVlcResumeOutput(case.state, case.currentGeneration, case.playIntent),
            )
            assertEquals(case.pending, isLibVlcResumeOutputPending(case.state, case.currentGeneration))
        }
    }

    @Test
    fun currentNativePrepareCompletionApplies() {
        assertTrue(
            shouldApplyLibVlcNativePrepareResult(
                requestGeneration = 9L,
                currentGeneration = 9L,
                released = false,
            ),
        )
    }

    @Test
    fun staleNativePrepareCompletionDoesNotApply() {
        assertFalse(
            shouldApplyLibVlcNativePrepareResult(
                requestGeneration = 9L,
                currentGeneration = 10L,
                released = false,
            ),
        )
    }

    @Test
    fun releasedNativePrepareCompletionDoesNotApply() {
        assertFalse(
            shouldApplyLibVlcNativePrepareResult(
                requestGeneration = 9L,
                currentGeneration = 9L,
                released = true,
            ),
        )
    }

    @Test
    fun resumeOutputRelockRewindsToForceNativeFlush() {
        assertEquals(34_750L, libVlcResumeOutputRelockSeekTarget(35_000L))
        assertEquals(0L, libVlcResumeOutputRelockSeekTarget(100L))
    }

    private fun resumeArrived(generation: Long = 7L): LibVlcResumeOutputRelockState =
        LibVlcResumeOutputRelockState(
            generation = generation,
            awaitingFirstOutput = true,
        )
}
