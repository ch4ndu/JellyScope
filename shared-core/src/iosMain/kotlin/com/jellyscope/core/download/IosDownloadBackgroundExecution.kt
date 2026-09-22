// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * iOS-only adapter between the Apple-owned BackgroundTasks controller and the
 * common queue host. It retains only opaque generations and numeric progress.
 */
@Suppress("EXPOSED_SUPER_CLASS", "EXPOSED_SUPER_INTERFACE")
class IosDownloadBackgroundExecution : DownloadContinuedExecution {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    private val lock = ReentrantLock()
    private var callbacks: NativeCallbacks? = null
    private var requestedGeneration: Long? = null
    private var activeGeneration: Long? = null
    private var progressAttempt: DownloadAttemptIdentity? = null
    private var latestProgress: DownloadExecutionProgressUpdate? = null
    private var progressWakeGeneration: Long? = null
    private var pendingProgress: PendingProgress? = null
    private var expirationHandler: ((Long) -> Unit)? = null

    /**
     * Installs the shared-ui forwarding callbacks after Koin has created this
     * adapter. Swift itself sees the named shared-ui bridge, not these core
     * implementation details.
     */
    fun installCallbacks(
        requestGrant: (Long) -> Unit,
        reportProgress: (Long, Long, Long?) -> Unit,
        completeGrant: (Long, Boolean) -> Unit,
    ) {
        val pending =
            lock.withLock {
                callbacks = NativeCallbacks(requestGrant, reportProgress, completeGrant)
                requestedGeneration.takeIf { generation -> activeGeneration != generation }
            }
        pending?.let(requestGrant)
    }

    /** Checks a native handler's exact generation before it installs expiration handling. */
    fun canReceiveGrant(wakeGeneration: Long): Boolean = lock.withLock { requestedGeneration == wakeGeneration && activeGeneration == null }

    override fun beginWake(wakeGeneration: Long) {
        lock.withLock {
            if (progressWakeGeneration != wakeGeneration) {
                latestProgress = null
                progressAttempt = null
                progressWakeGeneration = wakeGeneration
            }
        }
    }

    /** Called by the shared-ui bridge only after the native task handler grants execution. */
    fun receiveGrant(wakeGeneration: Long): Boolean {
        val replay =
            lock.withLock {
                if (requestedGeneration != wakeGeneration || activeGeneration != null) {
                    null
                } else {
                    activeGeneration = wakeGeneration
                    val snapshot = pendingProgress?.takeIf { pending -> pending.generation == wakeGeneration }?.update
                    pendingProgress = null
                    progressAttempt = snapshot?.attempt
                    snapshot
                        ?.let { update ->
                            callbacks?.reportProgress?.let { reporter ->
                                GrantReplay.Progress(
                                    reporter = reporter,
                                    generation = wakeGeneration,
                                    transferredBytes = update.transferredBytes,
                                    expectedBytes = update.expectedBytes,
                                )
                            }
                        }
                        ?: GrantReplay.None
                }
            }
        val accepted = replay != null
        if (accepted) {
            logger.i { "stage=download-continuation event=granted result=Active" }
            replay?.report()
        } else {
            logger.i { "stage=download-continuation event=grant-rejected result=Stale" }
        }
        return accepted
    }

    /** Revokes native permission synchronously before the host cancels and joins its writer. */
    fun expireGrant(wakeGeneration: Long): Boolean {
        val expired =
            lock.withLock {
                if (activeGeneration != wakeGeneration) {
                    false
                } else {
                    activeGeneration = null
                    progressAttempt = null
                    pendingProgress = pendingProgress?.takeUnless { pending -> pending.generation == wakeGeneration }
                    clearRetainedProgress(wakeGeneration)
                    // Publish the host's generation-specific expiration state
                    // before another thread can observe this grant as absent.
                    // The handler only records that state and launches work; it
                    // never waits for Main or re-enters this adapter.
                    expirationHandler?.let { handler ->
                        handler(wakeGeneration)
                        true
                    } ?: false
                }
            }
        if (expired) {
            logger.i { "stage=download-continuation event=expired result=Revoked" }
            return true
        }
        return false
    }

    /** Drops a denied native submission without interrupting the foreground common writer. */
    fun rejectGrant(wakeGeneration: Long) {
        val rejected =
            lock.withLock {
                if (requestedGeneration != wakeGeneration || activeGeneration == wakeGeneration) {
                    false
                } else {
                    requestedGeneration = null
                    pendingProgress = pendingProgress?.takeUnless { pending -> pending.generation == wakeGeneration }
                    true
                }
            }
        if (rejected) {
            logger.i { "stage=download-continuation event=request-rejected result=Denied" }
        }
    }

    override fun requestGrant(wakeGeneration: Long) {
        val request =
            lock.withLock {
                if (requestedGeneration == wakeGeneration) {
                    return@withLock RequestCallback(
                        callbacks = callbacks,
                        supersededGeneration = null,
                        shouldRequest = false,
                    )
                }
                val superseded =
                    (activeGeneration ?: requestedGeneration)
                        ?.takeIf { generation -> generation != wakeGeneration }
                val callbacksToNotify = callbacks
                requestedGeneration = wakeGeneration
                if (activeGeneration != wakeGeneration) {
                    activeGeneration = null
                    progressAttempt = null
                }
                if (progressWakeGeneration != wakeGeneration) {
                    latestProgress = null
                    progressWakeGeneration = wakeGeneration
                }
                pendingProgress =
                    latestProgress
                        ?.takeIf { update -> update.terminal == null }
                        ?.let { update -> PendingProgress(wakeGeneration, update) }
                RequestCallback(
                    callbacks = callbacksToNotify,
                    supersededGeneration = superseded,
                    shouldRequest = true,
                )
            }
        request.supersededGeneration?.let { generation ->
            request.callbacks?.let { callbacks -> callbacks.completeGrant(generation, false) }
        }
        if (!request.shouldRequest) {
            logger.i { "stage=download-continuation event=request-joined result=Existing" }
        } else if (request.callbacks == null) {
            logger.i { "stage=download-continuation event=request-rejected result=Unavailable" }
        } else {
            logger.i { "stage=download-continuation event=requested result=Pending" }
            request.callbacks.requestGrant(wakeGeneration)
        }
    }

    override fun isGrantActive(wakeGeneration: Long): Boolean =
        lock.withLock { activeGeneration == wakeGeneration && requestedGeneration == wakeGeneration }

    override fun completeGrant(
        wakeGeneration: Long,
        succeeded: Boolean,
    ) {
        val callback =
            lock.withLock {
                val ownsNativeGrant =
                    requestedGeneration == wakeGeneration || activeGeneration == wakeGeneration
                val ownsProgress = progressWakeGeneration == wakeGeneration
                if (!ownsNativeGrant && !ownsProgress) {
                    null
                } else {
                    if (ownsNativeGrant) {
                        requestedGeneration = null
                        activeGeneration = null
                        progressAttempt = null
                        pendingProgress = pendingProgress?.takeUnless { pending -> pending.generation == wakeGeneration }
                    }
                    if (ownsProgress) {
                        clearRetainedProgress(wakeGeneration)
                    }
                    if (ownsNativeGrant) callbacks?.completeGrant else null
                }
            }
        callback?.invoke(wakeGeneration, succeeded)
        logger.i {
            "stage=download-continuation event=finished result=" +
                if (succeeded) "Completed" else "Incomplete"
        }
    }

    override fun bindExpirationHandler(handler: (Long) -> Unit) {
        lock.withLock {
            expirationHandler = handler
        }
    }

    internal fun report(update: DownloadExecutionProgressUpdate) {
        val callback =
            lock.withLock {
                if (update.terminal == null) {
                    (activeGeneration ?: requestedGeneration ?: progressWakeGeneration)?.let { generation ->
                        progressWakeGeneration = generation
                    }
                }
                retainLatestProgress(update)
                // The native request can be pending while the retained common
                // writer reaches its first real byte snapshot. Keep the latest
                // nonterminal exact attempt ready for a later grant, rather
                // than replaying only the snapshot that existed at submission.
                val requested = requestedGeneration
                if (
                    activeGeneration == null &&
                    requested != null &&
                    update.terminal == null
                ) {
                    pendingProgress = PendingProgress(requested, update)
                }
                val generation = activeGeneration ?: return@withLock null
                if (requestedGeneration != generation) return@withLock null
                val currentAttempt = progressAttempt
                when {
                    currentAttempt == null && update.terminal != null -> return@withLock null
                    currentAttempt == null -> progressAttempt = update.attempt
                    currentAttempt != update.attempt -> return@withLock null
                    update.terminal != null -> progressAttempt = null
                }
                callbacks?.reportProgress?.let { reporter ->
                    ProgressCallback(
                        reporter = reporter,
                        generation = generation,
                        transferredBytes = update.transferredBytes,
                        expectedBytes = update.expectedBytes,
                    )
                }
            }
        callback?.reporter?.invoke(
            callback.generation,
            callback.transferredBytes,
            callback.expectedBytes,
        )
    }

    private data class NativeCallbacks(
        val requestGrant: (Long) -> Unit,
        val reportProgress: (Long, Long, Long?) -> Unit,
        val completeGrant: (Long, Boolean) -> Unit,
    )

    private data class ProgressCallback(
        val reporter: (Long, Long, Long?) -> Unit,
        val generation: Long,
        val transferredBytes: Long,
        val expectedBytes: Long?,
    )

    private data class PendingProgress(
        val generation: Long,
        val update: DownloadExecutionProgressUpdate,
    )

    private sealed interface GrantReplay {
        fun report()

        data object None : GrantReplay {
            override fun report() = Unit
        }

        data class Progress(
            val reporter: (Long, Long, Long?) -> Unit,
            val generation: Long,
            val transferredBytes: Long,
            val expectedBytes: Long?,
        ) : GrantReplay {
            override fun report() = reporter(generation, transferredBytes, expectedBytes)
        }
    }

    private data class RequestCallback(
        val callbacks: NativeCallbacks?,
        val supersededGeneration: Long?,
        val shouldRequest: Boolean,
    )

    private fun retainLatestProgress(update: DownloadExecutionProgressUpdate) {
        val current = latestProgress
        when {
            update.terminal == null -> latestProgress = update
            current?.attempt == update.attempt -> latestProgress = null
            // A terminal callback from an older writer must not overwrite the
            // newer attempt whose start/progress is waiting for a grant.
            else -> Unit
        }
    }

    private fun clearRetainedProgress(wakeGeneration: Long) {
        if (progressWakeGeneration == wakeGeneration) {
            progressAttempt = null
            latestProgress = null
            progressWakeGeneration = null
        }
    }
}

/** Keeps the common attempt-qualified progress contract inside the core module. */
internal class IosDownloadBackgroundProgressSink(
    private val execution: IosDownloadBackgroundExecution,
) : DownloadExecutionProgressSink {
    override fun report(update: DownloadExecutionProgressUpdate) {
        execution.report(update)
    }
}
