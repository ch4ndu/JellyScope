// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.withLock as withVisibilityLock

/**
 * Coalesces rapid per-key writes before they reach a store. Same-key calls are
 * debounced by [debounceMs]; a superseded pending write completes early; and the
 * enqueue/flush lane runs on `limitedParallelism(1)` so two rapid same-key calls
 * cannot acquire the mutex out of order on a multi-threaded dispatcher. [save] is
 * non-suspending so UI callers can invoke it from event handlers.
 *
 * [outstanding] exposes the latest value submitted per key from [save] until its write
 * terminates: the enqueue job retires it unless it was handed to a flush job, whose completion
 * retires it on success, failure, or cancellation. A newer same-key value is never retired by an
 * older request. Visibility is transient work state, not evidence of durable success, and a
 * superseded caller's Deferred still completes early.
 */
internal class DebouncedKeyedStoreWriter<K : Any, V : Any>(
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val debounceMs: Long,
    private val write: suspend (K, V) -> Unit,
) {
    private val mutex = Mutex()
    private val writeDispatcher = dispatcher.limitedParallelism(1)
    private val pending = mutableMapOf<K, PendingWrite<V>>()
    private val jobs = mutableMapOf<K, Job>()
    private var latest: Deferred<Unit>? = null

    // Guards only the visible requests and handoff flags; never held across suspension, writes,
    // job cancellation, or Deferred completion. The coroutine mutex may take it, never the reverse.
    private val visibilityLock = ReentrantLock()
    private val visible = mutableMapOf<K, PendingWrite<V>>()

    fun save(
        key: K,
        value: V,
    ): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        latest = completion
        val request = PendingWrite(value, completion)
        // Publish and dispatch together so publication order matches enqueue order.
        visibilityLock.withVisibilityLock {
            visible[key] = request
            val enqueue =
                scope.launch(writeDispatcher) {
                    mutex.withLock {
                        pending[key]?.completion?.complete(Unit)
                        pending[key] = request
                        jobs[key]?.cancel()
                        val flushJob =
                            scope.launch(writeDispatcher) {
                                delay(debounceMs)
                                flush(key)
                            }
                        jobs[key] = flushJob
                        visibilityLock.withVisibilityLock {
                            request.handedOff = true
                            flushJob.invokeOnCompletion { retire(key, request) }
                        }
                    }
                }
            // Registered last: it runs immediately if the scope already cancelled the enqueue.
            enqueue.invokeOnCompletion {
                visibilityLock.withVisibilityLock { if (!request.handedOff) retire(key, request) }
            }
        }
        return completion
    }

    /** Latest value submitted for [key] whose write has not yet terminated. */
    fun outstanding(key: K): V? = visibilityLock.withVisibilityLock { visible[key]?.value }

    fun latestWrite(): Deferred<Unit>? = latest

    fun drainLatest(timeoutMs: Long): Job =
        scope.launch(writeDispatcher) {
            val deferred = latest ?: return@launch
            withTimeoutOrNull(timeoutMs) {
                try {
                    deferred.await()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Teardown is best-effort; save() reports persistence failures.
                }
            }
        }

    private suspend fun flush(key: K) {
        val entry =
            mutex.withLock {
                jobs.remove(key)
                pending.remove(key)
            } ?: return
        try {
            write(key, entry.value)
            entry.completion.complete(Unit)
        } catch (cancellation: CancellationException) {
            entry.completion.cancel(cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            entry.completion.completeExceptionally(throwable)
        }
    }

    private fun retire(
        key: K,
        request: PendingWrite<V>,
    ) {
        visibilityLock.withVisibilityLock { if (visible[key] === request) visible.remove(key) }
    }

    // Identity, not value equality, distinguishes repeated submissions of the same value.
    private class PendingWrite<V>(
        val value: V,
        val completion: CompletableDeferred<Unit>,
    ) {
        // Set under visibilityLock once a flush job owns retirement.
        var handedOff = false
    }
}
