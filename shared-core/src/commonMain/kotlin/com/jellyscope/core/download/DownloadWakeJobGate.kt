// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.withLock as withLifecycleLock

/**
 * Owns the one app-active wake job used by the iOS/JVM lifecycle hosts.
 *
 * The mutex protects installation, removal, and the cancellation barrier for
 * the retained [Deferred]. The transfer and its cancellation join both run
 * outside the mutex; the barrier remains installed until that join completes,
 * so a new generation cannot overlap its predecessor's cleanup.
 */
internal class DownloadWakeJobGate(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    private val mutex = Mutex()
    private val lifecycleLock = ReentrantLock()
    private var wakeJob: Deferred<Result<Unit>>? = null
    private var wakeGeneration: Long? = null
    private var nextWakeGeneration = 0L
    private var lifecycleIsActive = true
    private var activeLifecycleRecovery: ActiveLifecycleRecovery? = null
    private var wakeCancellation: CompletableDeferred<Unit>? = null
    private var cancellingWakeGeneration: Long? = null
    private val generationAdmission = atomic<GenerationAdmission>(NoGeneration)

    /**
     * Stable observation of one retained wake. A caller that joined an
     * already-finishing wake can await this exact deferred without observing a
     * later generation that replaces it.
     */
    internal class Launch internal constructor(
        val generation: Long,
        val joinedExistingWake: Boolean,
        private val completion: Deferred<Result<Unit>>,
    ) {
        suspend fun awaitCompletion(): Result<Unit> = completion.await()
    }

    /** One active-transition recovery barrier, retained after failure for later admissions. */
    internal class ActiveLifecycleRecovery internal constructor() {
        private val completion = CompletableDeferred<Result<Unit>>()

        internal val isCompleted: Boolean
            get() = completion.isCompleted

        internal suspend fun await(): Result<Unit> = completion.await()

        internal fun complete(result: Result<Unit>) {
            completion.complete(result)
        }
    }

    /**
     * The one atomic owner of the retained generation's attachment phase.
     * Native expiration can close it while holding the adapter lock, whereas
     * foreground launch reserves an attachment while holding the gate locks.
     */
    private sealed interface GenerationAdmission

    private object NoGeneration : GenerationAdmission

    private class OpenGeneration(
        val generation: Long,
        val attachments: List<AttachmentReservation>,
        val retirementRequested: Boolean,
    ) : GenerationAdmission

    private class ExpiringGeneration(
        val generation: Long,
        val attachments: List<AttachmentReservation>,
        val admission: ExpirationAdmission,
    ) : GenerationAdmission

    private class FailedExpiration(
        val admission: ExpirationAdmission,
    ) : GenerationAdmission

    /** A launch-owned reservation held through its enrollment attachment. */
    internal class AttachmentReservation {
        private val completion = CompletableDeferred<Unit>()

        fun release() {
            completion.complete(Unit)
        }

        suspend fun awaitRelease() {
            completion.await()
        }
    }

    /**
     * Lock-free terminal record for one exact native expiration. Its attachment
     * snapshot is frozen by the Open-to-Expiring compare-and-set, so cleanup
     * can wait only for admissions that linearized before expiration.
     */
    internal class ExpirationAdmission internal constructor(
        val generation: Long,
        private val admittedAttachments: List<AttachmentReservation>,
    ) {
        private val completion = CompletableDeferred<Result<Unit>>()

        internal suspend fun await(): Result<Unit> = completion.await()

        internal suspend fun awaitAdmittedAttachments() {
            admittedAttachments.forEach { attachment -> attachment.awaitRelease() }
        }

        internal fun complete(result: Result<Unit>) {
            completion.complete(result)
        }
    }

    /** Exact terminal expiration observed by an active-transition recovery. */
    internal class ExpirationTerminal internal constructor(
        val admission: ExpirationAdmission,
        val result: Result<Unit>,
    )

    /**
     * Installs and starts one retained wake job, returning once the job is
     * safely owned by this gate.  Callers use this for foreground actions:
     * admission is acknowledged when the recovery/wake coroutine is launched,
     * not after it drains the whole download.
     */
    suspend fun launch(block: suspend () -> Result<Unit>): Result<Unit> = launchForGeneration { block() }.map { Unit }

    /**
     * Installs one retained wake and returns its opaque generation. Repeated
     * requests join the active job so a native grant cannot create a writer.
     */
    suspend fun launchForGeneration(
        requireActiveLifecycle: Boolean = false,
        onInactiveLifecycle: () -> Unit = {},
        onGenerationInstalled: (generation: Long, joinedExistingWake: Boolean) -> Unit = { _, _ -> },
        block: suspend (Long) -> Result<Unit>,
    ): Result<Launch> {
        if (!scope.isActive) {
            logger.w { "stage=download-wake event=rejected result=InactiveScope" }
            return Result.failure(IllegalStateException("The download wake scope is no longer active."))
        }
        try {
            while (true) {
                awaitActiveLifecycleRecovery().exceptionOrNull()?.let { failure ->
                    return Result.failure(failure)
                }
                awaitExpirationAdmission().exceptionOrNull()?.let { failure ->
                    return Result.failure(failure)
                }
                awaitWakeCancellation()
                var attachmentsToAwait: List<AttachmentReservation>? = null
                val launch =
                    mutex.withLock {
                        lifecycleLock.withLifecycleLock {
                            // Recovery and cancellation can begin after the
                            // awaits above and before this writer boundary.
                            // Expiration cannot: each branch reserves its
                            // exact generation before attaching enrollment.
                            if (
                                activeLifecycleRecovery != null ||
                                wakeCancellation != null
                            ) {
                                null
                            } else if (requireActiveLifecycle && !lifecycleIsActive) {
                                onInactiveLifecycle()
                                logger.i { "stage=download-wake event=deferred result=InactiveLifecycle" }
                                Result.failure(IllegalStateException("The download wake requires an active lifecycle."))
                            } else if (wakeJob?.isActive == true) {
                                val generation = requireNotNull(wakeGeneration)
                                reserveGenerationAttachment(generation)?.let { reservation ->
                                    try {
                                        onGenerationInstalled(generation, true)
                                        Result.success(
                                            Launch(
                                                generation = generation,
                                                joinedExistingWake = true,
                                                completion = requireNotNull(wakeJob),
                                            ),
                                        )
                                    } finally {
                                        releaseGenerationAttachment(generation, reservation)
                                    }
                                } ?: kotlin.run {
                                    attachmentsToAwait = outstandingAttachments()
                                    null
                                }
                            } else {
                                val generation = nextWakeGeneration
                                publishGeneration(generation)?.let { reservation ->
                                    nextWakeGeneration += 1L
                                    val created =
                                        scope.async(dispatcher, start = CoroutineStart.LAZY) {
                                            logger.i { "stage=download-wake event=started" }
                                            try {
                                                block(generation).also { result ->
                                                    result.fold(
                                                        onSuccess = { logger.i { "stage=download-wake event=completed" } },
                                                        onFailure = { failure ->
                                                            logger.w {
                                                                "stage=download-wake event=failed exceptionType=${failure.playbackExceptionType()}"
                                                            }
                                                        },
                                                    )
                                                }
                                            } catch (cancellation: CancellationException) {
                                                logger.i { "stage=download-wake event=cancelled" }
                                                throw cancellation
                                            } catch (throwable: Throwable) {
                                                logger.w {
                                                    "stage=download-wake event=failed exceptionType=${throwable.playbackExceptionType()}"
                                                }
                                                Result.failure(throwable)
                                            }
                                        }
                                    wakeJob = created
                                    wakeGeneration = generation
                                    created.invokeOnCompletion { retireGeneration(generation) }
                                    try {
                                        onGenerationInstalled(generation, false)
                                        created.start()
                                        Result.success(
                                            Launch(
                                                generation = generation,
                                                joinedExistingWake = false,
                                                completion = created,
                                            ),
                                        )
                                    } finally {
                                        releaseGenerationAttachment(generation, reservation)
                                    }
                                } ?: kotlin.run {
                                    attachmentsToAwait = outstandingAttachments()
                                    null
                                }
                            }
                        }
                    }
                if (launch != null) return launch
                attachmentsToAwait?.forEach { attachment -> attachment.awaitRelease() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.w { "stage=download-wake event=failed exceptionType=${throwable.playbackExceptionType()}" }
            return Result.failure(throwable)
        }
    }

    /**
     * Records the foreground boundary before its asynchronous cancellation
     * begins. A foreground-only launch holds this same lock through writer
     * creation, so it either starts while active or is retained for the next
     * active transition.
     */
    fun markLifecycleInactive() {
        lifecycleLock.withLifecycleLock {
            lifecycleIsActive = false
        }
    }

    fun markLifecycleActive() {
        lifecycleLock.withLifecycleLock {
            lifecycleIsActive = true
        }
    }

    /**
     * Blocks new writer creation until the active-transition recovery has
     * reconciled durable work. A completed failure stays visible to later
     * admission until a later active transition owns a fresh recovery.
     */
    fun beginActiveLifecycleRecovery(): ActiveLifecycleRecovery? =
        lifecycleLock.withLifecycleLock {
            lifecycleIsActive = true
            val current = activeLifecycleRecovery
            if (current != null && !current.isCompleted) {
                null
            } else {
                ActiveLifecycleRecovery().also { recovery ->
                    activeLifecycleRecovery = recovery
                }
            }
        }

    /** Resolves only the active transition that installed this recovery barrier. */
    fun completeActiveLifecycleRecovery(
        recovery: ActiveLifecycleRecovery,
        result: Result<Unit>,
    ) {
        val completedRecovery =
            lifecycleLock.withLifecycleLock {
                if (activeLifecycleRecovery !== recovery) {
                    null
                } else {
                    if (result.isSuccess) {
                        activeLifecycleRecovery = null
                    }
                    recovery
                }
            }
        completedRecovery?.complete(result)
    }

    /**
     * Atomically closes one exact open generation without taking the gate
     * mutex or lifecycle lock. The adapter calls this while holding its own
     * lock, so it cannot invert foreground launch's gate-lock-then-adapter-
     * lock ordering. The returned admission carries every attachment that won
     * the same atomic protocol before expiration.
     */
    fun beginExpirationAdmission(wakeGeneration: Long): ExpirationAdmission? {
        while (true) {
            val state = generationAdmission.value
            if (state !is OpenGeneration || state.generation != wakeGeneration) return null
            val admission = ExpirationAdmission(wakeGeneration, state.attachments)
            val expiring =
                ExpiringGeneration(
                    generation = wakeGeneration,
                    attachments = state.attachments,
                    admission = admission,
                )
            if (generationAdmission.compareAndSet(state, expiring)) return admission
        }
    }

    /**
     * Resolves only this exact expiration. Its terminal state is published
     * before waiters are completed, so a completed success never leaves a
     * caller spinning on an Expiring state. Failure remains identity-bound
     * until the active recovery that observed it reconciles durable state.
     */
    fun completeExpirationAdmission(
        admission: ExpirationAdmission,
        result: Result<Unit>,
    ) {
        while (true) {
            val state = generationAdmission.value
            if (state !is ExpiringGeneration || state.admission !== admission) return
            val terminal: GenerationAdmission =
                if (result.isSuccess) {
                    NoGeneration
                } else {
                    FailedExpiration(admission)
                }
            if (generationAdmission.compareAndSet(state, terminal)) {
                admission.complete(result)
                return
            }
        }
    }

    /**
     * Observes an exact expiration terminal outside Main. If cleanup is still
     * running, this waits for its terminal state rather than treating a host
     * transaction transition as proof that no expiration exists.
     */
    suspend fun awaitExpirationTerminalForRecovery(): ExpirationTerminal? =
        when (val state = generationAdmission.value) {
            is ExpiringGeneration -> {
                val admission = state.admission
                ExpirationTerminal(admission, admission.await())
            }
            is FailedExpiration -> {
                val admission = state.admission
                ExpirationTerminal(admission, admission.await())
            }
            else -> null
        }

    /**
     * A successful active recovery can clear only the failure it observed
     * before reconciling durable state; an identity mismatch preserves any
     * later expiration for a later active recovery.
     */
    fun clearFailedExpirationAdmissionAfterSuccessfulRecovery(admission: ExpirationAdmission): Boolean {
        while (true) {
            val state = generationAdmission.value
            if (state !is FailedExpiration || state.admission !== admission) return false
            if (generationAdmission.compareAndSet(state, NoGeneration)) return true
        }
    }

    /** Returns the generation for the retained job, including a joined foreground wake. */
    suspend fun activeGeneration(): Long? =
        mutex.withLock {
            wakeJob?.takeIf { job -> job.isActive }?.let { wakeGeneration }
        }

    suspend fun run(block: suspend () -> Result<Unit>): Result<Unit> {
        while (true) {
            awaitWakeCancellation()
            val job =
                mutex.withLock {
                    if (wakeCancellation != null) {
                        null
                    } else {
                        wakeJob?.takeIf { candidate -> candidate.isActive }
                            ?: scope.async(dispatcher) { block() }.also { created -> wakeJob = created }
                    }
                }
            if (job == null) continue
            return try {
                job.await()
            } finally {
                mutex.withLock {
                    if (wakeJob === job) {
                        wakeJob = null
                        wakeGeneration?.let(::retireGeneration)
                        wakeGeneration = null
                    }
                }
            }
        }
    }

    /**
     * Cancels the retained wake and holds its generation barrier until its
     * writer has joined. An expected generation makes delayed native expiry
     * harmless after a newer wake has been installed.
     */
    suspend fun cancelAndJoin(
        expectedGeneration: Long? = null,
        cancellation: CancellationException = CancellationException("Download wake cancellation requested."),
    ): Boolean {
        var rejected = false
        var ownsCancellation = false
        var jobToJoin: Deferred<Result<Unit>>? = null
        val completion =
            mutex.withLock {
                val existingCancellation = wakeCancellation
                when {
                    existingCancellation != null -> {
                        if (
                            expectedGeneration != null &&
                            cancellingWakeGeneration != expectedGeneration
                        ) {
                            rejected = true
                            null
                        } else {
                            existingCancellation
                        }
                    }
                    expectedGeneration != null && wakeGeneration != expectedGeneration -> {
                        rejected = true
                        null
                    }
                    wakeJob == null -> null
                    else -> {
                        val barrier = CompletableDeferred<Unit>()
                        val job = requireNotNull(wakeJob)
                        wakeCancellation = barrier
                        cancellingWakeGeneration = wakeGeneration
                        job.cancel(cancellation)
                        jobToJoin = job
                        ownsCancellation = true
                        barrier
                    }
                }
            }
        if (rejected) return false
        completion ?: return true
        if (!ownsCancellation) {
            withContext(NonCancellable) {
                completion.await()
            }
            return true
        }
        withContext(NonCancellable) {
            requireNotNull(jobToJoin).join()
            val completed =
                mutex.withLock {
                    if (wakeJob === jobToJoin) {
                        wakeJob = null
                        wakeGeneration?.let(::retireGeneration)
                        wakeGeneration = null
                    }
                    wakeCancellation
                        ?.takeIf { barrier -> barrier === completion }
                        ?.also {
                            wakeCancellation = null
                            cancellingWakeGeneration = null
                        }
                }
            completed?.complete(Unit)
        }
        return true
    }

    /** Publishes a new generation and its first attachment in one CAS. */
    private fun publishGeneration(generation: Long): AttachmentReservation? {
        while (true) {
            val state = generationAdmission.value
            val reservation = AttachmentReservation()
            val published =
                when (state) {
                    NoGeneration ->
                        OpenGeneration(
                            generation = generation,
                            attachments = listOf(reservation),
                            retirementRequested = false,
                        )
                    is OpenGeneration -> {
                        if (state.attachments.isNotEmpty()) return null
                        OpenGeneration(
                            generation = generation,
                            attachments = listOf(reservation),
                            retirementRequested = false,
                        )
                    }
                    else -> return null
                }
            if (generationAdmission.compareAndSet(state, published)) return reservation
        }
    }

    /** Reserves attachment to an existing open generation before callback entry. */
    private fun reserveGenerationAttachment(generation: Long): AttachmentReservation? {
        while (true) {
            val state = generationAdmission.value
            if (
                state !is OpenGeneration ||
                state.generation != generation ||
                state.retirementRequested
            ) {
                return null
            }
            val reservation = AttachmentReservation()
            val reserved =
                OpenGeneration(
                    generation = generation,
                    attachments = state.attachments + reservation,
                    retirementRequested = false,
                )
            if (generationAdmission.compareAndSet(state, reserved)) return reservation
        }
    }

    /** Releases a completed attachment and retires only the exact old generation. */
    private fun releaseGenerationAttachment(
        generation: Long,
        reservation: AttachmentReservation,
    ) {
        reservation.release()
        while (true) {
            val state = generationAdmission.value
            if (state !is OpenGeneration || state.generation != generation) return
            if (state.attachments.none { attachment -> attachment === reservation }) return
            val remaining = state.attachments.filterNot { attachment -> attachment === reservation }
            val released: GenerationAdmission =
                if (state.retirementRequested && remaining.isEmpty()) {
                    NoGeneration
                } else {
                    OpenGeneration(
                        generation = generation,
                        attachments = remaining,
                        retirementRequested = state.retirementRequested,
                    )
                }
            if (generationAdmission.compareAndSet(state, released)) return
        }
    }

    /** A finished job cannot retire a newer generation or an expiration fence. */
    private fun retireGeneration(generation: Long) {
        while (true) {
            val state = generationAdmission.value
            if (state !is OpenGeneration || state.generation != generation) return
            val retired: GenerationAdmission =
                if (state.attachments.isEmpty()) {
                    NoGeneration
                } else {
                    OpenGeneration(
                        generation = generation,
                        attachments = state.attachments,
                        retirementRequested = true,
                    )
                }
            if (generationAdmission.compareAndSet(state, retired)) return
        }
    }

    /** Only used after a failed publication attempt; all waits happen outside locks. */
    private fun outstandingAttachments(): List<AttachmentReservation> =
        when (val state = generationAdmission.value) {
            is OpenGeneration -> state.attachments
            is ExpiringGeneration -> state.attachments
            else -> emptyList()
        }

    /** Waits outside the gate mutex while a predecessor cancellation joins. */
    private suspend fun awaitWakeCancellation() {
        val cancellation = mutex.withLock { wakeCancellation } ?: return
        cancellation.await()
    }

    private suspend fun awaitExpirationAdmission(): Result<Unit> {
        while (true) {
            when (val state = generationAdmission.value) {
                is ExpiringGeneration -> {
                    val result = state.admission.await()
                    if (result.isFailure) return result
                }
                is FailedExpiration -> return state.admission.await()
                else -> return Result.success(Unit)
            }
        }
    }

    private suspend fun awaitActiveLifecycleRecovery(): Result<Unit> {
        while (true) {
            val recovery = lifecycleLock.withLifecycleLock { activeLifecycleRecovery } ?: return Result.success(Unit)
            val result = recovery.await()
            if (result.isFailure) return result
        }
    }
}
