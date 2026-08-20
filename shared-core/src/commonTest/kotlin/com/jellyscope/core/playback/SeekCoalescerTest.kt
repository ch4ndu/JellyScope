// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeekCoalescerTest {
    @Test
    fun singleRequestCommitsAfterTheWindow() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            coalescer.request(30_000L)
            assertEquals(emptyList(), committed)

            scope.advanceUntilIdle()
            assertEquals(listOf(30_000L), committed)
            assertNull(coalescer.pendingTargetMs)
        }

    @Test
    fun burstOfRequestsCommitsOnlyTheNewestTarget() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            // Repeated D-pad taps: each superseded target must never reach the
            // native player, because every setTime() flushes libVLC's decoder.
            coalescer.request(10_000L)
            scope.advanceTimeBy(100L)
            coalescer.request(20_000L)
            scope.advanceTimeBy(100L)
            coalescer.request(30_000L)
            scope.advanceUntilIdle()

            assertEquals(listOf(30_000L), committed)
        }

    @Test
    fun requestsSeparatedByMoreThanTheWindowCommitIndependently() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            coalescer.request(10_000L)
            scope.advanceUntilIdle()
            coalescer.request(60_000L)
            scope.advanceUntilIdle()

            assertEquals(listOf(10_000L, 60_000L), committed)
        }

    @Test
    fun flushCommitsImmediatelyAndOnlyOnce() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            coalescer.request(45_000L)
            coalescer.flush()
            assertEquals(listOf(45_000L), committed)

            // The cancelled timer must not fire a duplicate.
            scope.advanceUntilIdle()
            assertEquals(listOf(45_000L), committed)
        }

    @Test
    fun flushWithoutAPendingRequestDoesNothing() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            coalescer.flush()
            scope.advanceUntilIdle()
            assertEquals(emptyList(), committed)
        }

    @Test
    fun cancelDropsThePendingTargetWithoutCommitting() =
        runTest {
            val committed = mutableListOf<Long>()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val coalescer = SeekCoalescer(scope = scope, commit = { target -> committed += target })

            coalescer.request(45_000L)
            coalescer.cancel()
            scope.advanceUntilIdle()

            assertEquals(emptyList(), committed)
            assertNull(coalescer.pendingTargetMs)
        }
}
