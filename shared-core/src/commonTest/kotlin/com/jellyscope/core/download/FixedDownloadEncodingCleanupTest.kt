// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FixedDownloadEncodingCleanupTest {
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
