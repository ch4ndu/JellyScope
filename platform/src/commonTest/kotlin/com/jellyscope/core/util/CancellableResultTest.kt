// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CancellableResultTest {
    @Test
    fun capturesOrdinaryFailure() {
        val result = runCatchingCancellable<Int> { error("ordinary failure") }

        assertEquals("ordinary failure", assertIs<IllegalStateException>(result.exceptionOrNull()).message)
    }

    @Test
    fun rethrowsCancellation() {
        val cancellation =
            assertFailsWith<CancellationException> {
                runCatchingCancellable<Unit> { throw CancellationException("scope stopped") }
            }

        assertEquals("scope stopped", cancellation.message)
    }
}
