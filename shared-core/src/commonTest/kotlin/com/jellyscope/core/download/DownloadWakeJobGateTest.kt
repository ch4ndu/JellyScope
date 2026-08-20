// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadWakeJobGateTest {
    @Test
    fun launchAcknowledgesUserWakeBeforeTheRetainedTransferCompletes() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gate = DownloadWakeJobGate(scope)
                val started = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()

                val admission =
                    withTimeout(2_000L) {
                        gate.launch {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                finished.complete(Unit)
                            }
                        }
                    }

                assertTrue(admission.isSuccess)
                withTimeout(2_000L) { started.await() }
                assertFalse(finished.isCompleted)

                withTimeout(2_000L) { gate.cancelAndJoin() }
                withTimeout(2_000L) { finished.await() }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun cancellationReleasesAnInFlightWakeBeforeCheckpoint() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gate = DownloadWakeJobGate(scope)
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val waiting =
                    async {
                        runCatching {
                            gate.run {
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                        }
                    }

                withTimeout(2_000L) { started.await() }
                withTimeout(2_000L) { gate.cancelAndJoin() }
                withTimeout(2_000L) { cancelled.await() }

                assertTrue(waiting.await().isFailure)
            } finally {
                scope.cancel()
            }
        }
}
