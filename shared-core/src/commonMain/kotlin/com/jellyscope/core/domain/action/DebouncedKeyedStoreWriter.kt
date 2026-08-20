// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

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

/**
 * Coalesces rapid per-key writes before they reach a store. Same-key calls are
 * debounced by [debounceMs]; a superseded pending write completes early; and the
 * enqueue/flush lane runs on `limitedParallelism(1)` so two rapid same-key calls
 * cannot acquire the mutex out of order on a multi-threaded dispatcher. [save] is
 * non-suspending so UI callers can invoke it from event handlers.
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

    fun save(
        key: K,
        value: V,
    ): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        latest = completion
        scope.launch(writeDispatcher) {
            mutex.withLock {
                pending[key]?.completion?.complete(Unit)
                pending[key] = PendingWrite(value, completion)
                jobs[key]?.cancel()
                jobs[key] =
                    scope.launch(writeDispatcher) {
                        delay(debounceMs)
                        flush(key)
                    }
            }
        }
        return completion
    }

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

    private data class PendingWrite<V>(
        val value: V,
        val completion: CompletableDeferred<Unit>,
    )
}
