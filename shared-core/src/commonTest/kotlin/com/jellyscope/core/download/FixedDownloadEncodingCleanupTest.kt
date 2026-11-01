// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedDownloadEncodingCleanupTest {
    @Test
    fun cancelledWriterSettlesBeforeBlockedApplicationCleanupCompletes() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var checkpointed = false
            var cleanupStarted = false
            var cleanupCompletions = 0
            val writer =
                launch {
                    try {
                        withFixedDownloadEncodingCleanup(
                            cleanupScope = backgroundScope,
                            cleanupDispatcher = StandardTestDispatcher(testScheduler),
                            cleanup = {
                                cleanupStarted = true
                                releaseCleanup.await()
                                cleanupCompletions += 1
                            },
                        ) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    } catch (cancelled: CancellationException) {
                        checkpointed = true
                        throw cancelled
                    }
                }
            entered.await()
            writer.cancel()
            runCurrent()
            assertTrue(writer.isCompleted)
            assertTrue(checkpointed)
            assertTrue(cleanupStarted)
            assertFalse(releaseCleanup.isCompleted)
            assertEquals(0, cleanupCompletions)
            releaseCleanup.complete(Unit)
            runCurrent()
            assertEquals(1, cleanupCompletions)
        }

    @Test
    fun cleanupRunsOnceForSuccessFailureAndCancellation() =
        runTest {
            val attempts = mutableListOf<String>()

            val success =
                withFixedDownloadEncodingCleanup(
                    cleanup = { attempts += "success" },
                ) {
                    "completed"
                }
            assertEquals("completed", success)

            assertFailsWith<IllegalStateException> {
                withFixedDownloadEncodingCleanup(
                    cleanup = {
                        attempts += "failure"
                        error("cleanup failure")
                    },
                ) {
                    error("transfer failure")
                }
            }

            assertFailsWith<CancellationException> {
                withFixedDownloadEncodingCleanup(
                    cleanup = {
                        attempts += "cancellation"
                        error("cleanup failure")
                    },
                ) {
                    throw CancellationException("transfer cancellation")
                }
            }

            assertEquals(listOf("success", "failure", "cancellation"), attempts)
        }
}
