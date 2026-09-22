// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification

/**
 * Apple-core seam for an iOS continued-processing permission around the
 * existing writer. It carries only opaque wake generations and completion
 * facts; media, account, URL, and artifact data remain in common core.
 */
internal interface DownloadContinuedExecution {
    /** Binds in-memory progress to the one retained common writer generation. */
    fun beginWake(wakeGeneration: Long)

    fun requestGrant(wakeGeneration: Long)

    fun isGrantActive(wakeGeneration: Long): Boolean

    fun completeGrant(
        wakeGeneration: Long,
        succeeded: Boolean,
    )

    fun bindExpirationHandler(handler: (Long) -> Unit)
}

/**
 * Apple lifecycle owner for the existing common Ktor writers. tvOS keeps its
 * app-active behavior; iOS may inject an actual continued-processing grant.
 * This class never creates an OS-managed transfer or a second queue runner.
 */
internal class AppleDownloadLifecycleHost(
    private val driver: DownloadExecutionDriver,
    private val recovery: DownloadExecutionRecovery,
    private val scope: CoroutineScope,
    private val continuedExecution: DownloadContinuedExecution? = null,
) : DownloadExecutionHost,
    DownloadLifecycleHost {
    private var started = false
    private val observers = mutableListOf<Any>()
    private val wakeGate = DownloadWakeJobGate(scope)
    private val expirationLock = ReentrantLock()
    private val enrollmentLock = ReentrantLock()
    private val joinedFollowUpLock = ReentrantLock()
    private var expirationTransaction: ExpirationTransaction? = null
    private var generationEnrollment: Pair<Long, DownloadContinuedWorkEnrollment>? = null
    private val pendingJoinedFollowUps = mutableListOf<DownloadContinuedWorkEnrollment>()

    override fun start() {
        if (started) return
        started = true
        wakeGate.markLifecycleActive()
        continuedExecution?.bindExpirationHandler(::handleContinuedExpiration)
        val center = NSNotificationCenter.defaultCenter
        observers +=
            center.addObserverForName(
                name = UIApplicationWillResignActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                wakeGate.markLifecycleInactive()
                // A pending request is not a grant. Before UIKit can suspend a
                // foreground-only writer, synchronously revoke it, join it,
                // and persist the existing lifecycle checkpoint.
                runBlocking(Dispatchers.Default) { cancelForInactiveLifecycle() }
            }
        observers +=
            center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                if (continuedExecution == null) {
                    wakeGate.markLifecycleActive()
                    scope.launch(Dispatchers.Default) {
                        if (!hasActiveContinuedGrant(wakeGate.activeGeneration())) {
                            recovery.recover(this@AppleDownloadLifecycleHost)
                        }
                    }
                } else {
                    wakeGate.beginActiveLifecycleRecovery()?.let { lifecycleRecovery ->
                        val recoveryJob =
                            scope.launch(Dispatchers.Default) {
                                val recovery: Result<Boolean> =
                                    try {
                                        Result.success(recoverAfterActiveTransition())
                                    } catch (cancellation: CancellationException) {
                                        Result.failure(cancellation)
                                    } catch (throwable: Throwable) {
                                        Result.failure(throwable)
                                    }
                                // Releases new foreground writers only after the
                                // durable recovery or exact expiration checkpoint
                                // has settled. A failure is returned to admissions;
                                // it is never converted into a successful barrier.
                                wakeGate.completeActiveLifecycleRecovery(
                                    lifecycleRecovery,
                                    recovery.map { Unit },
                                )
                                if (recovery.getOrNull() == true) {
                                    schedulePendingJoinedFollowUps()
                                }
                            }
                        recoveryJob.invokeOnCompletion { failure ->
                            if (failure != null) {
                                wakeGate.completeActiveLifecycleRecovery(
                                    lifecycleRecovery,
                                    Result.failure(failure),
                                )
                            }
                        }
                    }
                }
            }
        observers +=
            center.addObserverForName(
                name = UIApplicationWillTerminateNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                wakeGate.markLifecycleInactive()
                runBlocking(Dispatchers.Default) { cancelForTermination() }
            }
        scope.launch(Dispatchers.Default) {
            recovery.recoverBeforeFirstWake(this@AppleDownloadLifecycleHost)
        }
    }

    /** Removes lifecycle observers and completes the bounded exit checkpoint. */
    override fun stop() {
        if (!started) return
        started = false
        val center = NSNotificationCenter.defaultCenter
        observers.forEach(center::removeObserver)
        observers.clear()
        wakeGate.markLifecycleInactive()
        runBlocking(Dispatchers.Default) { cancelForTermination() }
    }

    /** Explicit user transfer intent is the only path that requests a native grant. */
    override suspend fun wakeFromUserAction(): Result<Unit> =
        scheduleWake(
            recoverBeforeFirstWake = true,
            userInitiated = true,
        )

    override suspend fun wakeFromUserAction(enrollment: DownloadExplicitWorkEnrollment): Result<Unit> =
        scheduleWake(
            recoverBeforeFirstWake = true,
            userInitiated = true,
            explicitEnrollment = enrollment,
        )

    override suspend fun wake(): Result<Unit> = wakeFromPassiveEvent()

    /** Passive screen, recovery, pause, cancellation, and removal-release work never submits. */
    override suspend fun wakeFromPassiveEvent(): Result<Unit> =
        scheduleWake(
            recoverBeforeFirstWake = true,
            userInitiated = false,
        )

    private suspend fun scheduleWake(
        recoverBeforeFirstWake: Boolean,
        userInitiated: Boolean,
        explicitEnrollment: DownloadExplicitWorkEnrollment? = null,
        preparedEnrollment: DownloadContinuedWorkEnrollment? = null,
        allowJoinedFollowUp: Boolean = true,
        requireActiveLifecycle: Boolean = continuedExecution != null,
        onInactiveLifecycle: () -> Unit = {},
    ): Result<Unit> {
        val prepared =
            preparedEnrollment ?: explicitEnrollment?.let { enrollment ->
                driver.prepareContinuedWorkEnrollment(enrollment)
            }
        var attachedEnrollment: DownloadContinuedWorkEnrollment? = null
        val launch =
            wakeGate.launchForGeneration(
                requireActiveLifecycle = requireActiveLifecycle,
                onInactiveLifecycle = onInactiveLifecycle,
                onGenerationInstalled = { wakeGeneration, _ ->
                    continuedExecution?.beginWake(wakeGeneration)
                    attachedEnrollment =
                        prepared?.let { enrollment ->
                            attachEnrollment(wakeGeneration, enrollment)
                        }
                },
            ) { wakeGeneration ->
                try {
                    val result =
                        if (recoverBeforeFirstWake) {
                            recoverBeforeFirstWakeThenWake(wakeGeneration)
                        } else {
                            recoverThenWake(wakeGeneration)
                        }
                    if (result.isFailure) {
                        continuedExecution?.completeGrant(wakeGeneration, succeeded = false)
                    }
                    result
                } catch (cancellation: CancellationException) {
                    continuedExecution?.completeGrant(wakeGeneration, succeeded = false)
                    throw cancellation
                } catch (throwable: Throwable) {
                    continuedExecution?.completeGrant(wakeGeneration, succeeded = false)
                    Result.failure(throwable)
                }
            }
        val wake = launch.getOrNull()
        if (userInitiated && wake != null) {
            val enrollment = attachedEnrollment
            // A supplied enrollment that cannot attach is for another account
            // epoch. It may receive one serialized re-probe after this wake,
            // but it must not authorize this generation's writer or task.
            val hasExplicitIntent = explicitEnrollment != null || preparedEnrollment != null
            val mayRequestGrant = !hasExplicitIntent || enrollment != null
            if (mayRequestGrant) {
                continuedExecution?.requestGrant(wake.generation)
            }
            // A fast foreground drain can settle between installation and this
            // request. Clear its pending native request rather than reviving it.
            if (mayRequestGrant && wakeGate.activeGeneration() != wake.generation) {
                continuedExecution?.completeGrant(wake.generation, succeeded = false)
            }
            if (allowJoinedFollowUp && wake.joinedExistingWake && prepared != null) {
                observeJoinedExplicitIntent(wake, enrollment ?: prepared)
            }
        }
        return launch.map { Unit }
    }

    private suspend fun recoverThenWake(wakeGeneration: Long): Result<Unit> =
        recovery.recover(this).fold(
            onSuccess = { wakeIfRunnable(wakeGeneration) },
            onFailure = { failure -> Result.failure(failure) },
        )

    private suspend fun recoverBeforeFirstWakeThenWake(wakeGeneration: Long): Result<Unit> =
        recovery.recoverBeforeFirstWake(this).fold(
            onSuccess = { wakeIfRunnable(wakeGeneration) },
            onFailure = { failure -> Result.failure(failure) },
        )

    private suspend fun wakeIfRunnable(wakeGeneration: Long): Result<Unit> =
        if (continuedExecution == null) {
            driver.wake()
        } else {
            val enrollment = enrollmentFor(wakeGeneration)
            val outcome = driver.drainContinuedWork(enrollment)
            continuedExecution.completeGrant(
                wakeGeneration = wakeGeneration,
                succeeded = enrollment.closeWith(outcome),
            )
            Result.success(Unit)
        }

    /** Schedules at most one foreground-safe re-probe after a joined wake has torn down. */
    private fun observeJoinedExplicitIntent(
        wake: DownloadWakeJobGate.Launch,
        enrollment: DownloadContinuedWorkEnrollment,
    ) {
        scope.launch(Dispatchers.Default) {
            val completion =
                try {
                    wake.awaitCompletion()
                } catch (cancellation: CancellationException) {
                    return@launch
                }
            if (!completion.isSuccess || !enrollment.requiresFollowUpAfterJoin()) return@launch
            scheduleJoinedFollowUp(enrollment)
        }
    }

    /** One completion-edge re-probe is retained until the app can create its writer safely. */
    private suspend fun scheduleJoinedFollowUp(enrollment: DownloadContinuedWorkEnrollment) {
        scheduleWake(
            recoverBeforeFirstWake = true,
            userInitiated = true,
            preparedEnrollment = enrollment,
            allowJoinedFollowUp = false,
            requireActiveLifecycle = continuedExecution != null,
            onInactiveLifecycle = { retainJoinedFollowUp(enrollment) },
        )
    }

    private suspend fun schedulePendingJoinedFollowUps() {
        val pending =
            joinedFollowUpLock.withLock {
                pendingJoinedFollowUps.toList().also { pendingJoinedFollowUps.clear() }
            }
        pending.forEach { enrollment -> scheduleJoinedFollowUp(enrollment) }
    }

    private fun retainJoinedFollowUp(enrollment: DownloadContinuedWorkEnrollment) {
        joinedFollowUpLock.withLock {
            if (pendingJoinedFollowUps.any { pending -> pending.absorb(enrollment) }) return
            pendingJoinedFollowUps += enrollment
        }
    }

    private fun enrollmentFor(wakeGeneration: Long): DownloadContinuedWorkEnrollment =
        enrollmentLock.withLock {
            generationEnrollment
                ?.takeIf { (generation, _) -> generation == wakeGeneration }
                ?.second
                ?: DownloadContinuedWorkEnrollment(boundary = null).also { enrollment ->
                    generationEnrollment = wakeGeneration to enrollment
                }
        }

    private fun attachEnrollment(
        wakeGeneration: Long,
        enrollment: DownloadContinuedWorkEnrollment,
    ): DownloadContinuedWorkEnrollment? {
        val generationEnrollment = enrollmentFor(wakeGeneration)
        return generationEnrollment.takeIf { target -> target.absorb(enrollment) }
    }

    private suspend fun hasActiveContinuedGrant(wakeGeneration: Long?): Boolean =
        wakeGeneration?.let { generation ->
            continuedExecution?.isGrantActive(generation) == true
        } == true

    /**
     * `beginActiveLifecycleRecovery` has already stopped new writers. An
     * actual grant owns its exact retained writer; a merely pending request
     * does not. Any retained no-grant writer is therefore quiesced before the
     * durable recovery can requeue an active row beneath it.
     */
    private suspend fun recoverAfterActiveTransition(): Boolean {
        val activeGeneration = wakeGate.activeGeneration()
        if (hasActiveContinuedGrant(activeGeneration)) return true
        // Expiration owns its selected attempt until it has cancelled, joined,
        // and checkpointed it. Do not let activation requeue it or release an
        // earlier joined follow-up into that teardown.
        awaitExpirationTeardown(activeGeneration)?.let { result ->
            result.getOrThrow()
            return false
        }
        // The transaction can retire before its gate fence is terminal, so
        // observe the exact fence as well. Recovery then cannot succeed in a
        // transaction-clear-to-terminal transition or clear a later expiration.
        var observedExpiration = wakeGate.awaitExpirationTerminalForRecovery()
        if (observedExpiration?.result?.isSuccess == true) return false
        if (activeGeneration != null && observedExpiration == null) {
            // This adapter call serializes with `expireGrant`: either it wins
            // and prevents a later expiration callback, or expiration wins
            // and has already published the gate terminal that we await here.
            continuedExecution?.completeGrant(activeGeneration, succeeded = false)
            observedExpiration = wakeGate.awaitExpirationTerminalForRecovery()
            if (observedExpiration?.result?.isSuccess == true) return false
            if (observedExpiration == null) {
                cancelWakeAndCheckpoint(revokeContinuedGrant = false).getOrThrow()
            }
        }
        recovery.recover(this@AppleDownloadLifecycleHost).getOrThrow()
        val terminalAfterRecovery = wakeGate.awaitExpirationTerminalForRecovery()
        if (
            terminalAfterRecovery != null &&
            terminalAfterRecovery.admission !== observedExpiration?.admission
        ) {
            terminalAfterRecovery.result.getOrThrow()
            return false
        }
        observedExpiration?.let { expiration ->
            check(expiration.result.isFailure)
            check(wakeGate.clearFailedExpirationAdmissionAfterSuccessfulRecovery(expiration.admission))
        }
        return true
    }

    private suspend fun cancelForTermination(): Result<Unit> {
        val activeGeneration = wakeGate.activeGeneration()
        return awaitExpirationTeardown(activeGeneration) ?: cancelWakeAndCheckpoint()
    }

    private suspend fun cancelForInactiveLifecycle() {
        val activeGeneration = wakeGate.activeGeneration()
        if (
            !isExpirationPendingFor(activeGeneration) &&
            !hasActiveContinuedGrant(activeGeneration)
        ) {
            // A submitted request is not permission to keep writing after the
            // app becomes inactive. Invalidate it before the ordinary
            // cancellation/requeue so a delayed native handler cannot revive
            // this generation after its writer has stopped.
            cancelWakeAndCheckpoint()
        }
    }

    /**
     * The iOS bridge revokes its synchronized grant snapshot before invoking
     * this handler. The joined writer therefore never waits for Main while a
     * native expiration callback owns Main.
     */
    private fun handleContinuedExpiration(wakeGeneration: Long) {
        // The adapter holds its lock while invoking this callback. This gate
        // method is deliberately lock-free so foreground launch cannot deadlock
        // on the inverse gate-lock-then-adapter-lock path.
        val admission = wakeGate.beginExpirationAdmission(wakeGeneration) ?: return
        val transactionInstalled =
            expirationLock.withLock {
                if (expirationTransaction != null) {
                    false
                } else {
                    expirationTransaction =
                        ExpirationTransaction(
                            generation = wakeGeneration,
                            admission = admission,
                        )
                    true
                }
            }
        if (!transactionInstalled) {
            wakeGate.completeExpirationAdmission(
                admission,
                Result.failure(IllegalStateException("An iOS continued-processing expiration is already active.")),
            )
            return
        }
        discardPendingJoinedFollowUps()
        val expirationJob =
            scope.launch(Dispatchers.Default) {
                var result: Result<Unit> =
                    Result.failure(IllegalStateException("iOS continued-processing expiration did not complete."))
                try {
                    // `beginExpirationAdmission` atomically closed new
                    // attachments. Wait only for those that reserved before
                    // that linearization point, never on the native callback.
                    admission.awaitAdmittedAttachments()
                    val attempt = driver.activeAttempt()
                    result =
                        if (
                            wakeGate.cancelAndJoin(
                                expectedGeneration = wakeGeneration,
                                cancellation = CancellationException("iOS continued processing expired."),
                            )
                        ) {
                            driver.checkpointAndSuspend(attempt)
                        } else {
                            Result.failure(IllegalStateException("Expired download wake is no longer active."))
                        }
                } catch (cancellation: CancellationException) {
                    result = Result.failure(cancellation)
                } catch (throwable: Throwable) {
                    result = Result.failure(throwable)
                } finally {
                    finishExpiration(wakeGeneration, result)
                }
            }
        expirationJob.invokeOnCompletion { failure ->
            if (failure != null) {
                finishExpiration(wakeGeneration, Result.failure(failure))
            }
        }
    }

    private fun finishExpiration(
        wakeGeneration: Long,
        result: Result<Unit>,
    ) {
        val transaction =
            expirationLock.withLock {
                expirationTransaction
                    ?.takeIf { current -> current.generation == wakeGeneration }
                    ?.takeIf { current -> !current.finishing }
                    ?.also { current -> current.finishing = true }
            }
        if (transaction == null) return
        try {
            // The bridge revoked the grant before this handler ran. Complete
            // the matching native task only after the common writer has had
            // its exact cancel/join/checkpoint chance.
            continuedExecution?.completeGrant(wakeGeneration, succeeded = false)
        } finally {
            // Keep the gate expiring until this exact transaction is gone:
            // it prevents the next generation from being admitted while the
            // old expiration remains registered. Recovery observes and waits
            // on that exact gate fence during this clear-to-terminal interval.
            expirationLock.withLock {
                if (expirationTransaction === transaction) {
                    expirationTransaction = null
                }
            }
            wakeGate.completeExpirationAdmission(transaction.admission, result)
            transaction.completion.complete(result)
        }
    }

    private suspend fun awaitExpirationTeardown(wakeGeneration: Long?): Result<Unit>? {
        val completion =
            expirationLock.withLock {
                expirationTransaction
                    ?.takeIf { transaction -> wakeGeneration == null || transaction.generation == wakeGeneration }
                    ?.completion
            }
        return completion?.await()
    }

    private fun discardPendingJoinedFollowUps() {
        joinedFollowUpLock.withLock {
            pendingJoinedFollowUps.clear()
        }
    }

    private fun isExpirationPendingFor(wakeGeneration: Long?): Boolean =
        wakeGeneration != null &&
            expirationLock.withLock {
                expirationTransaction?.generation == wakeGeneration
            }

    private data class ExpirationTransaction(
        val generation: Long,
        val admission: DownloadWakeJobGate.ExpirationAdmission,
        val completion: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        var finishing: Boolean = false,
    )

    private suspend fun cancelWakeAndCheckpoint(revokeContinuedGrant: Boolean = true): Result<Unit> {
        if (revokeContinuedGrant) {
            wakeGate.activeGeneration()?.let { generation ->
                continuedExecution?.completeGrant(generation, succeeded = false)
            }
        }
        return cancelWakeAndRequeue(wakeGate, driver).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    DownloadLifecycleRequeueOutcome.Requeued,
                    DownloadLifecycleRequeueOutcome.AlreadyQueued,
                    DownloadLifecycleRequeueOutcome.NoActiveAttempt,
                    -> Result.success(Unit)
                    DownloadLifecycleRequeueOutcome.StaleAttempt,
                    DownloadLifecycleRequeueOutcome.RemovalInProgress,
                    DownloadLifecycleRequeueOutcome.Finalizing,
                    -> Result.failure(IllegalStateException("Download lifecycle requeue was not admitted."))
                }
            },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = driver.checkpointAndSuspend(attempt)

    override suspend fun queryActiveWork(): Result<List<DownloadExecutionWork>> = Result.success(emptyList())

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> =
        Result.failure(IllegalStateException("Apple platforms have no surviving native download work to reassociate."))

    override suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)
}
