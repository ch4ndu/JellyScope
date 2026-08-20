// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldSeekTimersTest {
    @Test
    fun watchdogFiresAfterFirstRepeatGraceWhenNoRepeatArrives() =
        runTest {
            var watchdogCount = 0
            var cadenceCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = { cadenceCount += 1 },
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(HOLD_SEEK_FIRST_REPEAT_GRACE_MS - 1)
            runCurrent()
            assertEquals(0, watchdogCount)

            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(1, watchdogCount)

            testScheduler.advanceTimeBy(HOLD_SEEK_CADENCE_COMMIT_MS * 5)
            runCurrent()
            assertEquals(0, cadenceCount, "watchdog expiry must cancel cadence before it ever ticks")
        }

    @Test
    fun observedRepeatReArmsWatchdogAtRepeatSilenceWindow() =
        runTest {
            var watchdogCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = {},
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(400)
            runCurrent()
            timers.onRepeatEvent()

            testScheduler.advanceTimeBy(HOLD_SEEK_WATCHDOG_MS - 1)
            runCurrent()
            assertEquals(0, watchdogCount, "grace deadline must be superseded by the repeat re-arm")

            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(1, watchdogCount)
        }

    @Test
    fun everyRepeatResetsTheSilenceWindow() =
        runTest {
            var watchdogCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = {},
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(400)
            runCurrent()
            repeat(5) {
                timers.onRepeatEvent()
                testScheduler.advanceTimeBy(HOLD_SEEK_WATCHDOG_MS - 50)
                runCurrent()
            }
            assertEquals(0, watchdogCount)

            testScheduler.advanceTimeBy(50)
            runCurrent()
            assertEquals(1, watchdogCount)
        }

    @Test
    fun cadenceTicksEveryIntervalWhileRepeatsKeepTheHoldAlive() =
        runTest {
            var cadenceCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = {},
                    onCadenceTick = { cadenceCount += 1 },
                )

            timers.onSessionStarted()
            repeat(18) {
                testScheduler.advanceTimeBy(250)
                runCurrent()
                timers.onRepeatEvent()
            }

            assertEquals(2, cadenceCount, "4.5s of held repeats must produce cadence ticks at 2s and 4s")
        }

    @Test
    fun sessionEndStopsBothTimers() =
        runTest {
            var watchdogCount = 0
            var cadenceCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = { cadenceCount += 1 },
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(400)
            runCurrent()
            timers.onRepeatEvent()
            timers.onSessionEnded()

            testScheduler.advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, watchdogCount)
            assertEquals(0, cadenceCount)
        }

    @Test
    fun watchdogExpiryTerminatesSessionSoNoLaterCadenceTickFires() =
        runTest {
            var watchdogCount = 0
            var cadenceCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = { cadenceCount += 1 },
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(400)
            runCurrent()
            timers.onRepeatEvent()
            testScheduler.advanceTimeBy(HOLD_SEEK_WATCHDOG_MS)
            runCurrent()
            assertEquals(1, watchdogCount)

            testScheduler.advanceTimeBy(HOLD_SEEK_CADENCE_COMMIT_MS * 3)
            runCurrent()
            assertEquals(0, cadenceCount)

            timers.onRepeatEvent()
            testScheduler.advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, watchdogCount, "repeat after expiry must not resurrect a dead session")
        }

    @Test
    fun newSessionAfterExpiryRestartsTheScheduleCleanly() =
        runTest {
            var watchdogCount = 0
            var cadenceCount = 0
            val timers =
                HoldSeekTimers(
                    scope = backgroundScope,
                    onWatchdogExpired = { watchdogCount += 1 },
                    onCadenceTick = { cadenceCount += 1 },
                )

            timers.onSessionStarted()
            testScheduler.advanceTimeBy(HOLD_SEEK_FIRST_REPEAT_GRACE_MS)
            runCurrent()
            assertEquals(1, watchdogCount)

            timers.onSessionStarted()
            repeat(10) {
                testScheduler.advanceTimeBy(250)
                runCurrent()
                timers.onRepeatEvent()
            }
            assertEquals(1, cadenceCount, "fresh session must tick cadence on its own schedule")
            assertEquals(1, watchdogCount)
        }
}
