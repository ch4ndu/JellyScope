// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JvmPreviousRunFailureStoreTest {
    @Test
    fun markerRoundTripsAcrossStoreInstancesAndIsConsumedOnce() {
        val directory = Files.createTempDirectory("jellyscope-previous-run-test")
        val markerFile = directory.resolve("marker").toFile()
        try {
            JvmPreviousRunFailureStore(markerFile).write(
                throwable = IllegalArgumentException("message=https://example.invalid/secret"),
                platform = PreviousRunFailurePlatform.Desktop,
            )

            val restored = JvmPreviousRunFailureStore(markerFile)
            assertEquals(
                PreviousRunFailureMarker(
                    exceptionType = "IllegalArgumentException",
                    platform = PreviousRunFailurePlatform.Desktop,
                ),
                restored.consume(),
            )
            assertNull(restored.consume())
            assertFalse(markerFile.exists())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
