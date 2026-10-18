// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SerializedLatestValueWriter<T>(
    private val scope: CoroutineScope,
    private val monotonicTimeNanos: () -> Long = System::nanoTime,
    private val write: suspend (T) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var pendingSubmittedAtNanos: Long? = null
    private var writing = false

    fun submit(value: T) {
        val shouldStart =
            synchronized(lock) {
                pending = value
                pendingSubmittedAtNanos = monotonicTimeNanos()
                if (writing) {
                    false
                } else {
                    writing = true
                    true
                }
            }
        if (!shouldStart) return

        scope.launch {
            withContext(NonCancellable) {
                while (true) {
                    when (val step = nextStep()) {
                        WriterStep.Idle -> return@withContext
                        is WriterStep.Wait -> delay(step.delayMillis)
                        is WriterStep.Ready -> {
                            try {
                                write(step.value)
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Throwable) {
                                // A failed preference write must not strand newer values.
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nextStep(): WriterStep<T> =
        synchronized(lock) {
            val submittedAtNanos = pendingSubmittedAtNanos
            if (submittedAtNanos == null) {
                writing = false
                WriterStep.Idle
            } else {
                val remainingNanos =
                    SERIALIZED_LATEST_VALUE_DEBOUNCE_NANOS -
                        (monotonicTimeNanos() - submittedAtNanos)
                if (remainingNanos > 0L) {
                    WriterStep.Wait(remainingNanos.toDelayMillis())
                } else {
                    val next = pending
                    pending = null
                    pendingSubmittedAtNanos = null
                    if (next == null) {
                        writing = false
                        WriterStep.Idle
                    } else {
                        WriterStep.Ready(next)
                    }
                }
            }
        }

    private sealed interface WriterStep<out T> {
        data object Idle : WriterStep<Nothing>

        data class Wait(
            val delayMillis: Long,
        ) : WriterStep<Nothing>

        data class Ready<T>(
            val value: T,
        ) : WriterStep<T>
    }
}

private fun Long.toDelayMillis(): Long =
    ((this / NANOS_PER_MILLISECOND) + if (this % NANOS_PER_MILLISECOND == 0L) 0L else 1L)
        .coerceAtLeast(1L)

private const val SERIALIZED_LATEST_VALUE_DEBOUNCE_NANOS = 500_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
