// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InactivitySignalTest {
    @Test
    fun repeatedActivityRestartsQuietPeriodWithoutStartingAnotherWaiter() =
        runTest {
            val signal = InactivitySignal()
            var inactive = false
            val waiter =
                launch {
                    signal.awaitInactivity(timeoutMillis = 1_000L)
                    inactive = true
                }

            signal.signal()
            runCurrent()
            advanceTimeBy(900L)
            signal.signal()
            runCurrent()
            advanceTimeBy(999L)
            runCurrent()
            assertFalse(inactive)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(inactive)
            waiter.join()
        }

    @Test
    fun cancellationPreventsTimeoutCompletion() =
        runTest {
            val signal = InactivitySignal()
            var inactive = false
            val waiter =
                launch {
                    signal.awaitInactivity(timeoutMillis = 1_000L)
                    inactive = true
                }

            signal.signal()
            runCurrent()
            waiter.cancel()
            advanceTimeBy(1_000L)
            runCurrent()

            assertFalse(inactive)
        }
}
