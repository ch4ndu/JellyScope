// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.paging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginatorTest {
    @Test
    fun activeMoreIsRejectedAndFirstCancelsThatActualJob() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val calls = mutableListOf<Boolean>()
            val paginator =
                Paginator(scope, dispatcher) { reset ->
                    calls += reset
                    if (!reset) {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            cancelled.complete(Unit)
                            throw cancellation
                        }
                    }
                }

            try {
                paginator.more()
                runCurrent()
                started.await()
                paginator.more()
                paginator.first()
                runCurrent()

                assertTrue(cancelled.isCompleted)
                assertEquals(listOf(false, true), calls)
            } finally {
                scope.cancel()
            }
        }
}
