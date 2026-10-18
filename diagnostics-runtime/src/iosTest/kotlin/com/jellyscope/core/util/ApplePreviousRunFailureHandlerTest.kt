// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalNativeApi::class)
class ApplePreviousRunFailureHandlerTest {
    @Test
    fun installsOnceWritesInvokesPreviousAndTerminatesAfterItReturns() {
        var installedHook: ReportUnhandledExceptionHook? = null
        var previousCalls = 0
        var terminationCalls = 0
        val previous: ReportUnhandledExceptionHook = { previousCalls += 1 }
        val store = RecordingStore()
        val installer =
            ApplePreviousRunFailureHandlerInstaller(
                installHook = { hook ->
                    installedHook = hook
                    previous
                },
                terminate = { terminationCalls += 1 },
            )

        assertTrue(installer.install(store, PreviousRunFailurePlatform.Ios))
        assertFalse(installer.install(store, PreviousRunFailurePlatform.Ios))

        requireNotNull(installedHook).invoke(IllegalStateException("secret payload"))

        assertEquals(1, store.writeCalls)
        assertEquals(1, previousCalls)
        assertEquals(1, terminationCalls)
    }

    @Test
    fun terminatesAfterPreviousThrowsAndWhenPreviousIsAbsent() {
        var throwingTerminationCalls = 0
        var throwingHook: ReportUnhandledExceptionHook? = null
        val throwingInstaller =
            ApplePreviousRunFailureHandlerInstaller(
                installHook = { hook ->
                    throwingHook = hook
                    { error("previous hook failure") }
                },
                terminate = { throwingTerminationCalls += 1 },
            )

        assertTrue(throwingInstaller.install(RecordingStore(), PreviousRunFailurePlatform.Ios))
        assertFailsWith<IllegalStateException> {
            requireNotNull(throwingHook).invoke(IllegalStateException("secret payload"))
        }
        assertEquals(1, throwingTerminationCalls)

        var absentTerminationCalls = 0
        var absentHook: ReportUnhandledExceptionHook? = null
        val absentInstaller =
            ApplePreviousRunFailureHandlerInstaller(
                installHook = { hook ->
                    absentHook = hook
                    null
                },
                terminate = { absentTerminationCalls += 1 },
            )
        assertTrue(absentInstaller.install(RecordingStore(), PreviousRunFailurePlatform.TvOs))
        requireNotNull(absentHook).invoke(IllegalStateException("secret payload"))
        assertEquals(1, absentTerminationCalls)
    }

    private class RecordingStore : PreviousRunFailureStore {
        var writeCalls = 0

        override fun write(
            throwable: Throwable,
            platform: PreviousRunFailurePlatform,
        ) {
            writeCalls += 1
        }

        override fun consume() = null

        override fun clear() = Unit
    }
}
