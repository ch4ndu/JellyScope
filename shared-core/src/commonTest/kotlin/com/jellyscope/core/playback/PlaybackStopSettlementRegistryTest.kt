// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStopSettlementRegistryTest {
    @Test
    fun queuesPublishSettlementsForCompletedAndTerminallyFailedStops() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val registry = PlaybackStopSettlementRegistry()
            val queue = PlaybackReportingQueue(StopReporter(failingItemId = "failed-item"), dispatcher, registry)
            val completed = reportingSession(generation = 1L, itemId = "completed-item")
            val failed = reportingSession(generation = 2L, itemId = "failed-item")

            queue.enqueueStart(completed, positionMs = 0L)
            val completedStop = queue.enqueueStop(completed, positionMs = 1_000L)
            queue.enqueueStart(failed, positionMs = 0L)
            val failedStop = queue.enqueueStop(failed, positionMs = 1_000L)
            runCurrent()

            assertTrue(completedStop.await())
            assertFalse(failedStop.await())
            assertEquals(
                listOf("completed-item", "failed-item"),
                registry.observe("server-1", "user-1").valueItemIds(),
            )
            queue.closeAfterDrain()
            runCurrent()
        }

    @Test
    fun retainsLatestSettlementPerKeyWithAppGlobalMonotonicSequences() =
        runTest {
            val registry = PlaybackStopSettlementRegistry()
            val firstKey = SettlementKey("server-1", "user-1", "item-1")
            val otherKey = SettlementKey("server-1", "user-1", "item-2")

            val first = registry.publish(firstKey)
            val other = registry.publish(otherKey)
            val replacement = registry.publish(firstKey)

            assertTrue(first.sequence < other.sequence)
            assertTrue(other.sequence < replacement.sequence)
            assertEquals(
                listOf(other, replacement),
                registry.observe("server-1", "user-1").value(),
            )
        }

    @Test
    fun replayIsScopedToItsServerAndUser() =
        runTest {
            val registry = PlaybackStopSettlementRegistry()
            val expected = registry.publish(SettlementKey("server-1", "user-1", "item-1"))
            registry.publish(SettlementKey("server-1", "user-2", "item-1"))
            registry.publish(SettlementKey("server-2", "user-1", "item-1"))

            assertEquals(listOf(expected), registry.observe("server-1", "user-1").value())
        }

    @Test
    fun independentQueuesWithLocalGenerationZeroReceiveDistinctSequences() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val registry = PlaybackStopSettlementRegistry()
            val firstQueue = PlaybackReportingQueue(StopReporter(), dispatcher, registry)
            val secondQueue = PlaybackReportingQueue(StopReporter(), dispatcher, registry)
            val session = reportingSession(generation = 0L, itemId = "same-item")

            firstQueue.enqueueStart(session, positionMs = 0L)
            val firstStop = firstQueue.enqueueStop(session, positionMs = 1_000L)
            runCurrent()
            assertTrue(firstStop.await())
            val firstSequence =
                registry
                    .observe("server-1", "user-1")
                    .value()
                    .single()
                    .sequence

            secondQueue.enqueueStart(session, positionMs = 0L)
            val secondStop = secondQueue.enqueueStop(session, positionMs = 1_000L)
            runCurrent()
            assertTrue(secondStop.await())
            val secondSequence =
                registry
                    .observe("server-1", "user-1")
                    .value()
                    .single()
                    .sequence

            assertTrue(secondSequence > firstSequence)
            firstQueue.closeAfterDrain()
            secondQueue.closeAfterDrain()
            runCurrent()
        }
}

private suspend fun Flow<List<PlaybackStopSettlement>>.value(): List<PlaybackStopSettlement> = first()

private suspend fun Flow<List<PlaybackStopSettlement>>.valueItemIds(): List<String> = value().map { settlement -> settlement.key.itemId }

private class StopReporter(
    private val failingItemId: String? = null,
) : PlaybackProgressReporter {
    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) = Unit

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) = Unit

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        if (plan.itemId == failingItemId) {
            error("deliberate stop failure")
        }
    }
}

private fun reportingSession(
    generation: Long,
    itemId: String,
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
                itemId = itemId,
                mediaSourceId = "source-$itemId",
                startPositionMs = 0L,
                streamMode = StreamMode.DirectPlay,
                streamUrl = "https://jellyfin.example/Videos/$itemId/stream",
                progressReportingPolicy = ProgressReportingPolicy(10_000L),
            ),
        playSessionId = generation.toString(),
    )
