// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.data.local.PreviousRunFailureMarker
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmPreviousRunFailureHandlerTest {
    @Test
    fun installsOnceWritesSanitizedEvidenceAndPreservesPreviousHandler() {
        val installer = PreviousRunFailureHandlerInstaller()
        val store = RecordingPreviousRunFailureStore()
        var previousCalls = 0
        var installed: Thread.UncaughtExceptionHandler? = null
        val previous =
            Thread.UncaughtExceptionHandler { _, _ ->
                previousCalls += 1
            }

        assertTrue(
            installer.install(
                store = store,
                platform = PreviousRunFailurePlatform.Desktop,
                previousHandler = previous,
                setDefaultHandler = { handler -> installed = handler },
            ),
        )
        assertFalse(
            installer.install(
                store = store,
                platform = PreviousRunFailurePlatform.Desktop,
                previousHandler = previous,
                setDefaultHandler = {},
            ),
        )

        val handler = requireNotNull(installed)
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret payload"))

        assertEquals(1, store.writeCalls)
        assertEquals("IllegalStateException", store.marker?.exceptionType)
        assertEquals(1, previousCalls)
    }
}

private class RecordingPreviousRunFailureStore : PreviousRunFailureStore {
    var writeCalls = 0
    var marker: PreviousRunFailureMarker? = null

    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) {
        writeCalls += 1
        marker = PreviousRunFailureMarker(throwable.safeDiagnosticType(), platform)
    }

    override fun consume(): PreviousRunFailureMarker? = null

    override fun clear() = Unit
}
