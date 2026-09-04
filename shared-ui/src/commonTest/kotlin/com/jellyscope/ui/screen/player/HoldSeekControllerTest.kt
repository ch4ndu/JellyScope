// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldSeekControllerTest {
    private class Harness(
        scope: CoroutineScope,
    ) {
        var itemId: String? = "item-1"
        val commits = mutableListOf<Long>()
        val finalCommits = mutableListOf<Long>()
        val controller =
            HoldSeekController(
                scope = scope,
                itemId = { itemId },
                positionMs = { 100_000L },
                durationMs = { 3_600_000L },
                commit = commits::add,
                onFinalCommit = finalCommits::add,
            )
    }

    @Test
    fun keyUpCommitsWhileTheSessionBoundIdentityIsStable() =
        runTest {
            val harness = Harness(backgroundScope)

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            harness.controller.onSeekKeyUp()

            assertEquals(listOf(110_000L), harness.commits)
            assertEquals(listOf(110_000L), harness.finalCommits)
        }

    @Test
    fun noSessionStartsWhileIdentityIsUnstable() =
        runTest {
            val harness = Harness(backgroundScope)
            harness.itemId = null

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)

            assertNull(harness.controller.pendingTargetMs)
            harness.controller.onSeekKeyUp()
            assertTrue(harness.commits.isEmpty())
            assertTrue(harness.finalCommits.isEmpty())
        }

    @Test
    fun keyUpAfterIdentityChangeDropsTheCommit() =
        runTest {
            val harness = Harness(backgroundScope)

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            // The queue switch publishes a null identity synchronously; the
            // key-up races it and must not seek the replacement item.
            harness.itemId = null
            harness.controller.onSeekKeyUp()

            assertTrue(harness.commits.isEmpty())
            assertTrue(harness.finalCommits.isEmpty())
            assertNull(harness.controller.pendingTargetMs)
        }

    @Test
    fun watchdogExpiryAfterIdentityChangeDropsTheCommit() =
        runTest {
            val harness = Harness(backgroundScope)

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            harness.itemId = "item-2"
            testScheduler.advanceTimeBy(HOLD_SEEK_FIRST_REPEAT_GRACE_MS)
            runCurrent()

            assertTrue(harness.commits.isEmpty())
            assertTrue(harness.finalCommits.isEmpty())
            assertNull(harness.controller.pendingTargetMs)
        }

    @Test
    fun cadenceTickAfterIdentityChangeCancelsTheSessionWithoutCommitting() =
        runTest {
            val harness = Harness(backgroundScope)

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            repeat(7) {
                testScheduler.advanceTimeBy(250)
                runCurrent()
                harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            }
            harness.itemId = null
            testScheduler.advanceTimeBy(250)
            runCurrent()

            assertTrue(harness.commits.isEmpty())
            assertNull(harness.controller.pendingTargetMs)
        }

    @Test
    fun cadenceTickCommitsWhileIdentityIsStable() =
        runTest {
            val harness = Harness(backgroundScope)

            harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            repeat(8) {
                testScheduler.advanceTimeBy(250)
                runCurrent()
                harness.controller.onSeekKeyDown(HoldSeekDirection.Forward)
            }

            assertEquals(1, harness.commits.size)
            assertTrue(harness.finalCommits.isEmpty(), "cadence commits are not final commits")
            assertTrue(harness.controller.sessionActive, "cadence commits keep the hold session alive")
        }
}
