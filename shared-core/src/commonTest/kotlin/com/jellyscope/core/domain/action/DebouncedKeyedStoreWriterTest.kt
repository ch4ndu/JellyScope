// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebouncedKeyedStoreWriterTest {
    @Test
    fun coalescesSameKeyToOneWriteKeepingTheLastValue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val stored = mutableMapOf<String, Int>()
                var writes = 0
                val writer =
                    DebouncedKeyedStoreWriter<String, Int>(scope, dispatcher, debounceMs = 150L) { key, value ->
                        stored[key] = value
                        writes += 1
                    }

                val first = writer.save("k", 1)
                val second = writer.save("k", 2)
                advanceUntilIdle()

                assertEquals(2, stored["k"])
                assertEquals(1, writes) // rapid same-key writes coalesce to a single store write
                assertTrue(first.isCompleted) // superseded write completes early
                assertTrue(second.isCompleted)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun distinctKeysEachPersist() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val stored = mutableMapOf<String, Int>()
                val writer =
                    DebouncedKeyedStoreWriter<String, Int>(scope, dispatcher, debounceMs = 150L) { key, value ->
                        stored[key] = value
                    }

                writer.save("a", 1)
                writer.save("b", 2)
                advanceUntilIdle()

                assertEquals(mapOf("a" to 1, "b" to 2), stored)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun concurrentSameKeySavesStayConsistentUnderRealDispatcher() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // Writes run on limitedParallelism(1) so a plain map is only mutated by
                // one coroutine at a time; it is read after every write has drained.
                val stored = mutableMapOf<String, Int>()
                val writer =
                    DebouncedKeyedStoreWriter<String, Int>(scope, Dispatchers.Default, debounceMs = 5L) { key, value ->
                        stored[key] = value
                    }

                val count = 100
                coroutineScope {
                    repeat(count) { index ->
                        launch(Dispatchers.Default) { writer.save("k", index) }
                    }
                }
                // Let the serialized write lane run every enqueue and the final debounce
                // flush. drainLatest awaits only the `latest` completion, which can
                // resolve early when superseded, so wait past the debounce as well.
                writer.drainLatest(5_000L).join()
                delay(300L)

                // No lost/corrupted write under contention: the store holds exactly one
                // of the saved values (the debounce coalesces the racing saves).
                val finalValue = stored["k"]
                assertTrue(finalValue != null && finalValue in 0 until count)
            } finally {
                scope.cancel()
            }
        }
}
