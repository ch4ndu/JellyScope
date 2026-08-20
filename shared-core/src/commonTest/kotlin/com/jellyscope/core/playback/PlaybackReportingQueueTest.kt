// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackReportingQueueTest {
    @Test
    fun delayedStartKeepsProgressAndStopOrdered() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reporter = RecordingReporter(startGate = CompletableDeferred())
            val queue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry())
            val reportingSession = reportingSession(generation = 1L)

            val start = queue.enqueueStart(reportingSession, positionMs = 1_000L)
            queue.enqueueProgress(
                reportingSession = reportingSession,
                positionMs = 2_000L,
                isPaused = true,
                eventName = PlaybackProgressEvent.Pause,
            )
            val stop = queue.enqueueStop(reportingSession, positionMs = 3_000L)
            runCurrent()

            assertEquals(listOf("start-enter:1"), reporter.events)
            assertFalse(start.isCompleted)
            assertFalse(stop.isCompleted)

            reporter.startGate?.complete(Unit)
            runCurrent()

            assertTrue(start.await())
            assertTrue(stop.await())
            assertEquals(
                listOf("start-enter:1", "start-exit:1", "progress:1:Pause", "stop:1"),
                reporter.events,
            )
            queue.closeAfterDrain()
            runCurrent()
        }

    @Test
    fun failedStartDoesNotAuthorizeProgressOrStopAndCanRetry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reporter = RecordingReporter(failedStartAttempts = 1)
            val queue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry())
            val reportingSession = reportingSession(generation = 1L)

            val failedStart = queue.enqueueStart(reportingSession, positionMs = 1_000L)
            queue.enqueueProgress(
                reportingSession = reportingSession,
                positionMs = 2_000L,
                isPaused = false,
                eventName = PlaybackProgressEvent.TimeUpdate,
            )
            val skippedStop = queue.enqueueStop(reportingSession, positionMs = 3_000L)
            runCurrent()

            assertFalse(failedStart.await())
            assertFalse(skippedStop.await())
            assertEquals(listOf("start-enter:1", "start-failed:1"), reporter.events)

            val retriedStart = queue.enqueueStart(reportingSession, positionMs = 4_000L)
            queue.enqueueProgress(
                reportingSession = reportingSession,
                positionMs = 5_000L,
                isPaused = false,
                eventName = PlaybackProgressEvent.TimeUpdate,
            )
            val successfulStop = queue.enqueueStop(reportingSession, positionMs = 6_000L)
            runCurrent()

            assertTrue(retriedStart.await())
            assertTrue(successfulStop.await())
            assertEquals(
                listOf(
                    "start-enter:1",
                    "start-failed:1",
                    "start-enter:1",
                    "start-exit:1",
                    "progress:1:TimeUpdate",
                    "stop:1",
                ),
                reporter.events,
            )
            queue.closeAfterDrain()
            runCurrent()
        }

    @Test
    fun oldSessionStopCompletesBeforeNewSessionStart() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reporter = RecordingReporter()
            val queue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry())
            val oldSession = reportingSession(generation = 1L)
            val newSession = reportingSession(generation = 2L)

            queue.enqueueStart(oldSession, positionMs = 1_000L)
            queue.enqueueStop(oldSession, positionMs = 2_000L)
            queue.enqueueStart(newSession, positionMs = 0L)
            runCurrent()

            assertEquals(
                listOf("start-enter:1", "start-exit:1", "stop:1", "start-enter:2", "start-exit:2"),
                reporter.events,
            )
            queue.closeAfterDrain()
            runCurrent()
        }

    @Test
    fun progressAndStopFailuresAreContainedAfterAStartedSession() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reporter = RecordingReporter(failedProgressAttempts = 1, failedStopAttempts = 1)
            val queue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry())
            val reportingSession = reportingSession(generation = 3L)

            val start = queue.enqueueStart(reportingSession, positionMs = 1_000L)
            queue.enqueueProgress(
                reportingSession = reportingSession,
                positionMs = 2_000L,
                isPaused = false,
                eventName = PlaybackProgressEvent.TimeUpdate,
            )
            val stop = queue.enqueueStop(reportingSession, positionMs = 3_000L)
            runCurrent()

            assertTrue(start.await())
            assertFalse(stop.await())
            assertEquals(
                listOf("start-enter:3", "start-exit:3", "progress-enter:3", "progress-failed:3", "stop-enter:3", "stop-failed:3"),
                reporter.events,
            )
            queue.closeAfterDrain()
            runCurrent()
        }

    @Test
    fun queueEmitsAdmittedSuccessAndFailureDiagnosticsWithCorrelation() =
        runTest {
            val captured = mutableListOf<Triple<Severity, String, Throwable?>>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.PlaybackReportingQueue.wireValue) {
                            captured += Triple(severity, message, throwable)
                        }
                    }
                }

            try {
                Logger.setLogWriters(listOf(writer))
                val dispatcher = StandardTestDispatcher(testScheduler)
                val explicitQueue =
                    PlaybackReportingQueue(
                        RecordingReporter(),
                        dispatcher,
                        PlaybackStopSettlementRegistry(),
                    )
                val explicitSession = reportingSession(generation = 11L, diagnosticSessionSequence = 700L)
                val explicitStart = explicitQueue.enqueueStart(explicitSession, positionMs = 1_000L)
                val explicitStop = explicitQueue.enqueueStop(explicitSession, positionMs = 2_000L)
                runCurrent()
                assertTrue(explicitStart.await())
                assertTrue(explicitStop.await())
                explicitQueue.closeAfterDrain()
                runCurrent()

                val fallbackQueue =
                    PlaybackReportingQueue(
                        RecordingReporter(
                            failedStartAttempts = 1,
                            failedProgressAttempts = 1,
                            failedStopAttempts = 1,
                        ),
                        dispatcher,
                        PlaybackStopSettlementRegistry(),
                    )
                val fallbackSession = reportingSession(generation = 12L)
                val failedStart = fallbackQueue.enqueueStart(fallbackSession, positionMs = 1_000L)
                runCurrent()
                assertFalse(failedStart.await())
                val retriedStart = fallbackQueue.enqueueStart(fallbackSession, positionMs = 2_000L)
                fallbackQueue.enqueueProgress(
                    reportingSession = fallbackSession,
                    positionMs = 3_000L,
                    isPaused = false,
                    eventName = PlaybackProgressEvent.TimeUpdate,
                )
                val failedStop = fallbackQueue.enqueueStop(fallbackSession, positionMs = 4_000L)
                runCurrent()
                assertTrue(retriedStart.await())
                assertFalse(failedStop.await())
                fallbackQueue.closeAfterDrain()
                runCurrent()
            } finally {
                Logger.setLogWriters(emptyList())
            }

            val diagnostics =
                captured.map { (severity, message, throwable) ->
                    assertEquals(null, throwable)
                    assertEquals(message, LogScrubber.capture(DiagnosticTag.PlaybackReportingQueue.wireValue, message))
                    Triple(severity, message, throwable)
                }
            assertEquals(6, diagnostics.size)
            assertEquals(Severity.Info, diagnostics[0].first)
            assertTrue(diagnostics[0].second.contains("reportingOperation=start"))
            assertTrue(diagnostics[0].second.contains("reportingResult=Success"))
            assertTrue(diagnostics[0].second.contains("sessionSequence=700"))
            assertEquals(Severity.Info, diagnostics[1].first)
            assertTrue(diagnostics[1].second.contains("reportingOperation=stop"))
            assertTrue(diagnostics[1].second.contains("reportingResult=Success"))
            assertTrue(diagnostics[1].second.contains("sessionSequence=700"))
            assertEquals(listOf(Severity.Warn, Severity.Info, Severity.Warn, Severity.Warn), diagnostics.drop(2).map { it.first })
            assertTrue(diagnostics[2].second.contains("reportingOperation=start"))
            assertTrue(diagnostics[2].second.contains("reportingResult=Failed"))
            assertTrue(diagnostics[2].second.contains("sessionSequence=12"))
            assertTrue(diagnostics[3].second.contains("reportingOperation=start"))
            assertTrue(diagnostics[3].second.contains("reportingResult=Success"))
            assertTrue(diagnostics[3].second.contains("sessionSequence=12"))
            assertTrue(diagnostics[4].second.contains("reportingOperation=progress"))
            assertTrue(diagnostics[4].second.contains("reportingResult=Failed"))
            assertTrue(diagnostics[5].second.contains("reportingOperation=stop"))
            assertTrue(diagnostics[5].second.contains("reportingResult=Failed"))
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("reportingOperation=progress reportingResult=Success") })
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("playSessionId") })
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("serverId") })
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("itemId") })
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("https://") })
            assertFalse(diagnostics.any { (_, message, _) -> message.contains("deliberate") })
        }
}

private class RecordingReporter(
    val startGate: CompletableDeferred<Unit>? = null,
    failedStartAttempts: Int = 0,
    failedProgressAttempts: Int = 0,
    failedStopAttempts: Int = 0,
) : PlaybackProgressReporter {
    val events = mutableListOf<String>()
    private var remainingFailedStartAttempts = failedStartAttempts
    private var remainingFailedProgressAttempts = failedProgressAttempts
    private var remainingFailedStopAttempts = failedStopAttempts

    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        events += "start-enter:$playSessionId"
        if (remainingFailedStartAttempts > 0) {
            remainingFailedStartAttempts -= 1
            events += "start-failed:$playSessionId"
            error("deliberate start failure")
        }
        startGate?.await()
        events += "start-exit:$playSessionId"
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        if (remainingFailedProgressAttempts > 0) {
            remainingFailedProgressAttempts -= 1
            events += "progress-enter:$playSessionId"
            events += "progress-failed:$playSessionId"
            error("deliberate progress failure")
        }
        events += "progress:$playSessionId:${eventName.name}"
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        if (remainingFailedStopAttempts > 0) {
            remainingFailedStopAttempts -= 1
            events += "stop-enter:$playSessionId"
            events += "stop-failed:$playSessionId"
            error("deliberate stop failure")
        }
        events += "stop:$playSessionId"
    }
}

private fun reportingSession(
    generation: Long,
    diagnosticSessionSequence: Long? = null,
): PlaybackReportingSession =
    PlaybackReportingSession(
        generation = generation,
        session =
            Session(
                serverUrl = "https://jellyfin.example",
                serverId = "server-1",
                serverName = "Home",
                userId = "user-1",
                userName = "User",
                accessToken = "token",
                deviceId = "device-1",
            ),
        plan =
            PlaybackPlan(
                itemId = "item-$generation",
                mediaSourceId = "source-$generation",
                startPositionMs = 0L,
                streamMode = StreamMode.DirectPlay,
                streamUrl = "https://jellyfin.example/Videos/item-$generation/stream",
                progressReportingPolicy = ProgressReportingPolicy(10_000L),
                diagnosticSessionSequence = diagnosticSessionSequence,
            ),
        playSessionId = generation.toString(),
    )
