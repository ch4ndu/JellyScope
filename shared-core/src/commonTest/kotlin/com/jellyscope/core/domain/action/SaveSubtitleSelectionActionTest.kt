// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveSubtitleSelectionActionTest {
    @Test
    fun rapidWritesFinishInUserActionOrderWithNewestValueLast() =
        runTest {
            val store = SlowSubtitleSelectionStore()
            val action = saveAction(store, backgroundScope)
            val key = SubtitleSelectionKey("server", "user", "item", "source")

            action.save(key, SubtitleSelectionIntent.Track(2))
            action.save(key, SubtitleSelectionIntent.Off)
            val latest = action.save(key, SubtitleSelectionIntent.Track(5))
            advanceUntilIdle()
            latest.await()

            assertEquals(
                listOf(
                    SubtitleSelectionIntent.Track(2),
                    SubtitleSelectionIntent.Off,
                    SubtitleSelectionIntent.Track(5),
                ),
                store.writes,
            )
            assertEquals(SubtitleSelectionIntent.Track(5), store.value)
        }

    @Test
    fun storeFailureCompletesTheReturnedWriteExceptionally() =
        runTest {
            val action = saveAction(FailingSubtitleSelectionStore(), backgroundScope)
            val write = action.save(testKey(), SubtitleSelectionIntent.Track(2))

            runCurrent()

            assertFailsWith<IllegalStateException> { write.await() }
        }

    @Test
    fun cancellingOwnerCancelsActiveAndQueuedWrites() =
        runTest {
            val ownerJob = SupervisorJob()
            val ownerScope = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
            val action = saveAction(BlockingSubtitleSelectionStore(), ownerScope)
            val active = action.save(testKey(), SubtitleSelectionIntent.Track(2))
            val queued = action.save(testKey(), SubtitleSelectionIntent.Off)
            runCurrent()

            ownerJob.cancel()
            runCurrent()

            assertTrue(active.isCancelled)
            assertTrue(queued.isCancelled)
        }

    @Test
    fun cancellingDrainRemainsCancellation() =
        runTest {
            val action = saveAction(BlockingSubtitleSelectionStore(), backgroundScope)
            action.save(testKey(), SubtitleSelectionIntent.Track(2))
            runCurrent()
            val drain = action.drainLatest()
            runCurrent()

            drain.cancel()
            runCurrent()

            assertTrue(drain.isCancelled)
        }

    @Test
    fun drainTimeoutCompletesNormally() =
        runTest {
            val action = saveAction(BlockingSubtitleSelectionStore(), backgroundScope)
            action.save(testKey(), SubtitleSelectionIntent.Track(2))
            runCurrent()
            val drain = action.drainLatest(timeoutMs = 100L)

            advanceTimeBy(101L)
            runCurrent()

            assertTrue(drain.isCompleted)
            assertFalse(drain.isCancelled)
        }

    private fun testKey() = SubtitleSelectionKey("server", "user", "item", "source")

    private fun saveAction(
        store: SubtitleSelectionStore,
        scope: CoroutineScope,
    ): SaveSubtitleSelectionAction {
        val coordinator = LocalSubtitleMutationCoordinator(null, null, store, scope)
        return SaveSubtitleSelectionAction(coordinator, scope)
    }
}

private class FailingSubtitleSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Unit = throw IllegalStateException("write failed")

    override suspend fun delete(key: SubtitleSelectionKey): Unit = throw IllegalStateException("delete failed")

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class BlockingSubtitleSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Unit = awaitCancellation()

    override suspend fun delete(key: SubtitleSelectionKey): Unit = awaitCancellation()

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class SlowSubtitleSelectionStore : SubtitleSelectionStore {
    val writes = mutableListOf<SubtitleSelectionIntent>()
    var value: SubtitleSelectionIntent? = null

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = value

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        if (writes.isEmpty()) delay(100)
        writes += selection
        value = selection
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        value = null
    }

    override suspend fun clearServerScoped(serverId: String) {
        value = null
    }

    override suspend fun clearServerScoped() {
        value = null
    }
}
