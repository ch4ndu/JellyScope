// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackReportingCoordinatorTest {
    @Test
    fun failedStartSuppressesProgressAndStopThenRetriesFromReadyState() =
        runTest {
            val reporter = CoordinatorReporter(failedStartAttempts = 1)
            val fixture = coordinatorFixture(reporter)
            fixture.coordinator.install(testSession, testPlan("item-1"), playSessionId = "first")

            val playing = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
            fixture.state.value = playing
            fixture.coordinator.onPlaybackState(playing, PlaybackStatus.Loading)
            runCurrent()
            fixture.coordinator.progress(2_000L, false, PlaybackProgressEvent.TimeUpdate)
            fixture.coordinator.stop(3_000L)
            runCurrent()

            assertEquals(listOf("start:first:1000", "start-failed:first"), reporter.events)

            fixture.coordinator.onPlaybackState(playing.copy(positionMs = 4_000L), PlaybackStatus.Playing)
            runCurrent()
            fixture.coordinator.progress(5_000L, false, PlaybackProgressEvent.TimeUpdate)
            fixture.coordinator.stop(6_000L)
            runCurrent()

            assertEquals(
                listOf(
                    "start:first:1000",
                    "start-failed:first",
                    "start:first:4000",
                    "progress:first:TimeUpdate:5000",
                    "stop:first:6000",
                ),
                reporter.events,
            )
        }

    @Test
    fun staleStartCompletionCannotMutateReplacementSession() =
        runTest {
            val oldStartGate = CompletableDeferred<Unit>()
            val reporter = CoordinatorReporter(startGates = mutableMapOf("old" to oldStartGate))
            val fixture = coordinatorFixture(reporter)
            fixture.coordinator.install(testSession, testPlan("old-item"), playSessionId = "old")
            val oldPlaying = playbackState(PlaybackStatus.Playing, 1_000L)
            fixture.state.value = oldPlaying
            fixture.coordinator.onPlaybackState(oldPlaying, PlaybackStatus.Loading)
            runCurrent()

            fixture.coordinator.stop(2_000L)
            fixture.coordinator.install(testSession, testPlan("new-item"), playSessionId = "new")
            val newPlaying = playbackState(PlaybackStatus.Playing, 3_000L)
            fixture.state.value = newPlaying
            fixture.coordinator.onPlaybackState(newPlaying, PlaybackStatus.Loading)
            runCurrent()
            assertEquals(listOf("start:old:1000"), reporter.events)

            oldStartGate.complete(Unit)
            runCurrent()
            fixture.coordinator.progress(4_000L, false, PlaybackProgressEvent.TimeUpdate)
            runCurrent()

            assertEquals(
                listOf(
                    "start:old:1000",
                    "stop:old:2000",
                    "start:new:3000",
                    "progress:new:TimeUpdate:4000",
                ),
                reporter.events,
            )
            fixture.coordinator.dispose(4_000L)
            runCurrent()
        }

    @Test
    fun periodicProgressSamplesStableProjectionAfterControllerReplacement() =
        runTest {
            val reporter = CoordinatorReporter()
            val fixture = coordinatorFixture(reporter)
            fixture.coordinator.install(testSession, testPlan("item-1", 1_000L), playSessionId = "session")
            val playing = playbackState(PlaybackStatus.Playing, 100L)
            fixture.state.value = playing
            fixture.coordinator.onPlaybackState(playing, PlaybackStatus.Loading)
            runCurrent()

            fixture.state.value = playing.copy(positionMs = 900L)
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(
                listOf("start:session:100", "progress:session:TimeUpdate:900"),
                reporter.events,
            )

            val paused = fixture.state.value.copy(status = PlaybackStatus.Paused, positionMs = 950L)
            fixture.state.value = paused
            fixture.coordinator.onPlaybackState(paused, PlaybackStatus.Playing)
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(
                listOf(
                    "start:session:100",
                    "progress:session:TimeUpdate:900",
                    "progress:session:Pause:950",
                ),
                reporter.events,
            )
            fixture.coordinator.dispose(950L)
            runCurrent()
        }

    @Test
    fun duplicateStopAndDisposeAreSuppressed() =
        runTest {
            val reporter = CoordinatorReporter()
            val fixture = coordinatorFixture(reporter)
            fixture.coordinator.install(testSession, testPlan("item-1"), playSessionId = "session")
            val playing = playbackState(PlaybackStatus.Playing, 1_000L)
            fixture.state.value = playing
            fixture.coordinator.onPlaybackState(playing, PlaybackStatus.Loading)
            runCurrent()

            fixture.coordinator.stop(2_000L)
            fixture.coordinator.stop(3_000L)
            fixture.coordinator.dispose(4_000L)
            fixture.coordinator.dispose(5_000L)
            runCurrent()

            assertEquals(listOf("start:session:1000", "stop:session:2000"), reporter.events)
        }
}

private data class CoordinatorFixture(
    val coordinator: PlaybackReportingCoordinator,
    val state: MutableStateFlow<PlaybackState>,
)

private fun kotlinx.coroutines.test.TestScope.coordinatorFixture(reporter: CoordinatorReporter): CoordinatorFixture {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val state = MutableStateFlow(playbackState(PlaybackStatus.Idle, 0L))
    val coordinator =
        PlaybackReportingCoordinator(
            queue = PlaybackReportingQueue(reporter, dispatcher, PlaybackStopSettlementRegistry()),
            scope = this,
            playbackState = state,
        )
    return CoordinatorFixture(coordinator, state)
}

private class CoordinatorReporter(
    private var failedStartAttempts: Int = 0,
    private val startGates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf(),
) : PlaybackProgressReporter {
    val events = mutableListOf<String>()

    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        events += "start:$playSessionId:$positionMs"
        if (failedStartAttempts > 0) {
            failedStartAttempts -= 1
            events += "start-failed:$playSessionId"
            error("deliberate start failure")
        }
        startGates.remove(playSessionId)?.await()
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        events += "progress:$playSessionId:${eventName.name}:$positionMs"
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        events += "stop:$playSessionId:$positionMs"
    }
}

private val testSession =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )

private fun testPlan(
    itemId: String,
    reportIntervalMs: Long = 10_000L,
): PlaybackPlan =
    PlaybackPlan(
        itemId = itemId,
        mediaSourceId = "source-$itemId",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/$itemId/stream",
        progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs),
    )

private fun playbackState(
    status: PlaybackStatus,
    positionMs: Long,
): PlaybackState =
    PlaybackState(
        status = status,
        positionMs = positionMs,
        durationMs = 100_000L,
        bufferedPositionMs = positionMs,
    )
