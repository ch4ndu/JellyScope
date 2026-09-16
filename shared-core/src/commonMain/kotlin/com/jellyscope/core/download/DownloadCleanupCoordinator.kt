// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.DownloadQueueRepository
import com.jellyscope.core.data.repository.DownloadRemovalMutex
import com.jellyscope.core.data.repository.PreparedSessionRemoval
import com.jellyscope.core.data.repository.SessionBoundaryParticipant
import com.jellyscope.core.data.repository.SessionRemovalExecutor
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.BeginDownloadRemovalResult
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRemovalKind
import com.jellyscope.core.domain.model.DownloadRemovalOperation
import com.jellyscope.core.domain.model.DownloadRemovalOperationId
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.ownsActiveDownloadSlot
import com.jellyscope.core.playback.OfflineArtifactDeletionGuardResult
import com.jellyscope.core.playback.OfflineArtifactLeaseIdentity
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Download-owned account-boundary cleanup.  It is intentionally a participant rather than a
 * session repository: core supplies credential/account removal as a narrow executor, while this
 * class owns only download attempts, artifacts, rows, and the durable removal saga.
 */
class DownloadCleanupCoordinator internal constructor(
    private val removalStore: DownloadRemovalStore,
    private val queueRepository: DownloadQueueRepository,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val artifactStore: DownloadArtifactStore,
    private val artifactLeaseRegistry: OfflineArtifactLeaseRegistry,
    private val removalMutex: DownloadRemovalMutex,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val nowEpochMilliseconds: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val operationIdProvider: () -> DownloadRemovalOperationId = ::newDownloadRemovalOperationId,
) : SessionBoundaryParticipant {
    /** A preview remains quiesced and claim-blocked until it is confirmed or dismissed. */
    private val activePreviewGuards = mutableMapOf<ScopeFingerprint, DownloadRemovalPreview>()

    /**
     * Produces the exact preview shown by the destructive confirmation dialog. The session
     * boundary gate is acquired before queue/removal state, so a live writer is checkpointed and
     * the account is prevented from claiming another row for the lifetime of this preview.
     */
    suspend fun previewRemoval(scope: SessionRemovalScope): DownloadRemovalPreview =
        serverScopedStoreRegistry.withBoundaryMutation {
            val fingerprint = ScopeFingerprint.from(scope)
            val existing = removalMutex.withLock { activePreviewGuards[fingerprint] }
            if (existing != null) {
                quiesceScope(scope, this).getOrThrow()
                val refreshed = removalMutex.withLock { previewLocked(scope) }
                if (refreshed == existing) return@withBoundaryMutation existing
                removalMutex.withLock { activePreviewGuards[fingerprint] = refreshed }
                return@withBoundaryMutation refreshed
            }

            val accounts = fingerprint.accounts.toSet()
            if (!queueCoordinator.beginRemovalPreview(accounts)) {
                throw SessionRemovalError.ConfirmationStale
            }
            try {
                quiesceScope(scope, this).getOrThrow()
                val preview = removalMutex.withLock { previewLocked(scope) }
                removalMutex.withLock { activePreviewGuards[fingerprint] = preview }
                preview
            } catch (throwable: Throwable) {
                queueCoordinator.endRemovalPreview(accounts)
                throw throwable
            }
        }

    /**
     * Issues an opaque value bound to the exact preview displayed by the caller. A caller cannot
     * skip the participant-owned quiesced preview by manufacturing a count/byte snapshot.
     */
    suspend fun issueRemovalAuthorization(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): SessionRemovalAuthorization =
        serverScopedStoreRegistry.withBoundaryMutation {
            val fingerprint = ScopeFingerprint.from(scope)
            val guardedPreview = removalMutex.withLock { activePreviewGuards[fingerprint] }
            val currentPreview = removalMutex.withLock { previewLocked(scope) }
            if (guardedPreview != preview || currentPreview != preview) {
                throw SessionRemovalError.ConfirmationStale
            }
            SessionRemovalAuthorization.confirmed(
                DownloadRemovalAuthorizationToken(
                    scope = fingerprint,
                    preview = preview,
                ),
            )
        }

    /**
     * Releases a dismissed preview. The affected rows were checkpointed to a resumable state
     * before the dialog was shown; removing the claim block makes them eligible for the normal
     * FIFO runner again. A mismatched preview never releases another dialog's guard.
     */
    suspend fun releaseRemovalPreview(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean {
        val fingerprint = ScopeFingerprint.from(scope)
        // The two in-memory guards are one logical release. Keep that bounded mutation
        // non-cancellable so cancellation cannot leave the account claim-blocked after the
        // preview handle has been dismissed.
        val released =
            serverScopedStoreRegistry.withBoundaryMutation {
                withContext(NonCancellable) {
                    releasePreviewGuards(fingerprint, preview)
                }
            }
        if (released) {
            // The durable rows were already quiesced by preview. Wake only after the exact
            // guards are removed so app-active hosts can continue FIFO without spinning on a
            // still-protected account. A scheduling rejection/cancellation does not undo
            // dismissal; the rows remain durably runnable for the next lifecycle/user wake.
            try {
                queueCoordinator.wakeAfterRemovalPreviewRelease()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Dismissal has already released the durable claim guard. A later lifecycle
                // wake can recover a host scheduling failure without reopening the dialog.
            }
        }
        return released
    }

    override suspend fun beforeAccountSwitch(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> =
        resultFromBoundaryCheckpointSuspend(
            accountIdentity = accountIdentity,
            gateHeldBoundaryCommit = gateHeldBoundaryCommit,
        )

    override suspend fun prepareRemoval(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> =
        try {
            prepareRemovalWhileQuiesced(scope, authorization, gateHeldBoundaryCommit)
        } finally {
            // Confirmation consumes the preview whether it succeeds, becomes stale, or fails
            // because a playback lease is still held. The rows are already in a resumable state;
            // releasing this in-memory claim block is what lets dismissal/re-prompt continue FIFO.
            val fingerprint = ScopeFingerprint.from(scope)
            withContext(NonCancellable) {
                releasePreviewGuards(fingerprint, expectedPreview = null)
            }
        }

    /** Removes the participant and queue guards as one bounded logical operation. */
    private suspend fun releasePreviewGuards(
        fingerprint: ScopeFingerprint,
        expectedPreview: DownloadRemovalPreview?,
    ): Boolean {
        val released =
            removalMutex.withLock {
                val current = activePreviewGuards[fingerprint]
                if (expectedPreview != null && current != expectedPreview) {
                    false
                } else if (current == null && expectedPreview != null) {
                    false
                } else {
                    activePreviewGuards.remove(fingerprint)
                    true
                }
            }
        if (released) {
            queueCoordinator.endRemovalPreview(fingerprint.accounts.toSet())
        }
        return released
    }

    private suspend fun prepareRemovalWhileQuiesced(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<PreparedSessionRemoval?> =
        try {
            // Quiescence runs before the removal mutex.  Queue callbacks take the runner mutex
            // and then the repository's removal mutex; taking these in the opposite order here
            // would permit a progress callback and boundary cleanup to wait on one another.
            quiesceScope(scope, gateHeldBoundaryCommit).getOrThrow()
            removalMutex.withLock {
                val preview = previewLocked(scope)
                authorize(scope, authorization, preview)

                if (preview.confirmations.all { confirmation -> confirmation.recordCount == 0L }) {
                    return@withLock Result.success(null)
                }

                val records = queueRepository.allDownloads().filterFor(scope)
                val identities =
                    records
                        .map { record ->
                            OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration)
                        }.toSet()

                // The batch guard is acquired before the durable saga header and before core is
                // allowed to mutate credentials.  Once the header exists, normal resolver paths
                // also fail closed on its pending-removal membership.
                val beginResult =
                    withDeletionGuard(identities) {
                        removalStore.begin(
                            operationId = operationIdProvider(),
                            kind = ScopeFingerprint.from(scope).kind,
                            accountIdentities = ScopeFingerprint.from(scope).accounts,
                            confirmations = preview.confirmations,
                            createdAtEpochMs = now(),
                        )
                    }
                when (beginResult) {
                    GuardedResult.ArtifactInUse ->
                        Result.failure(SessionRemovalError.ArtifactInUse)
                    is GuardedResult.Granted ->
                        when (val result = beginResult.value) {
                            is BeginDownloadRemovalResult.Created ->
                                Result.success(PreparedSessionRemoval.create(PreparedDownloadRemoval(result.operation)))
                            BeginDownloadRemovalResult.NoDownloads -> Result.success(null)
                            BeginDownloadRemovalResult.ConfirmationStale,
                            BeginDownloadRemovalResult.RemovalAlreadyInProgress,
                            -> Result.failure(SessionRemovalError.ConfirmationStale)
                        }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun completeRemoval(
        removal: PreparedSessionRemoval,
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> =
        try {
            val prepared =
                removal.participantToken as? PreparedDownloadRemoval
                    ?: return Result.failure(IllegalStateException("Unknown download removal operation."))
            removalMutex.withLock {
                settleOperation(prepared.operation, executor)
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    /** Replays both halves of every pending saga without acquiring the session gate recursively. */
    override suspend fun resumeIncompleteRemovalOperations(
        executor: SessionRemovalExecutor,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> =
        try {
            removalMutex.withLock {
                removalStore.pending().forEach { operation ->
                    settleOperation(operation, executor)
                }
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    private suspend fun settleOperation(
        operation: DownloadRemovalOperation,
        executor: SessionRemovalExecutor,
    ) {
        // This is process-local only.  If a previous crash marked one target settled but the
        // full-account mutation never reached another target, the next replay must issue the
        // idempotent remove-all call again rather than treating a settled sibling as proof that
        // the whole operation was attempted.
        var fullLogoutAttempted = false
        operation.targets
            .sortedWith(compareBy({ it.accountIdentity.serverId }, { it.accountIdentity.userId }))
            .forEach { target ->
                if (!target.accountRemoved) {
                    val absentBefore = executor.isAccountAbsent(target.accountIdentity).getOrThrow()
                    if (!absentBefore) {
                        val removalResult =
                            if (operation.kind == DownloadRemovalKind.FullLogout) {
                                if (!fullLogoutAttempted) {
                                    fullLogoutAttempted = true
                                    executor.removeAllAccounts()
                                } else {
                                    Result.success(Unit)
                                }
                            } else {
                                executor.removeAccount(target.accountIdentity)
                            }
                        // A credential mutation may have succeeded and lost its reply.  Verify
                        // absence before failing the replay, making the operation idempotent.
                        if (removalResult.isFailure && !executor.isAccountAbsent(target.accountIdentity).getOrThrow()) {
                            removalResult.getOrThrow()
                        }
                    }
                    check(executor.isAccountAbsent(target.accountIdentity).getOrThrow()) {
                        "Session account removal did not settle."
                    }
                    check(
                        removalStore.markAccountRemoved(
                            operationId = target.operationId,
                            accountIdentity = target.accountIdentity,
                            cleanupGeneration = target.cleanupGeneration,
                        ),
                    ) { "Download removal account settlement became stale." }
                }

                deleteTargetArtifacts(target)
            }
        removalStore.deleteIfSettled(operation.operationId)
    }

    private suspend fun deleteTargetArtifacts(target: com.jellyscope.core.domain.model.DownloadRemovalTarget) {
        if (target.downloadsRemoved) return
        val remaining = target.remainingArtifactKeys
        if (remaining.isEmpty()) {
            check(
                removalStore.markDownloadsRemoved(
                    operationId = target.operationId,
                    accountIdentity = target.accountIdentity,
                    cleanupGeneration = target.cleanupGeneration,
                ),
            ) { "Download removal target could not settle rows." }
            return
        }

        val rowsByKey =
            queueRepository
                .allDownloads()
                .filter { record ->
                    record.businessKey.accountIdentity == target.accountIdentity &&
                        record.request.artifactKey in remaining
                }.associateBy { record -> record.request.artifactKey }
        val identities =
            rowsByKey.values
                .map { record -> OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration) }
                .toSet()
        val outcome =
            withDeletionGuard(identities) {
                val stillRemaining = remaining.toMutableList()
                val failures = mutableListOf<Throwable>()
                remaining.forEach { key ->
                    try {
                        artifactStore.delete(key, DownloadArtifactArea.Staging)
                        artifactStore.delete(key, DownloadArtifactArea.Completed)
                        artifactStore.deletePresentation(key)
                        stillRemaining.remove(key)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        failures += throwable
                    }
                }
                ArtifactDeletionOutcome(stillRemaining, failures)
            }
        when (outcome) {
            is GuardedResult.Granted -> {
                check(
                    removalStore.updateRemainingArtifactKeys(
                        operationId = target.operationId,
                        accountIdentity = target.accountIdentity,
                        cleanupGeneration = target.cleanupGeneration,
                        remainingArtifactKeys = outcome.value.remaining,
                    ),
                ) { "Download removal artifact checkpoint became stale." }
                if (outcome.value.failures.isNotEmpty()) {
                    throw outcome.value.failures.first()
                }
                check(
                    removalStore.markDownloadsRemoved(
                        operationId = target.operationId,
                        accountIdentity = target.accountIdentity,
                        cleanupGeneration = target.cleanupGeneration,
                    ),
                ) { "Download removal target could not settle rows." }
            }
            GuardedResult.ArtifactInUse -> throw SessionRemovalError.ArtifactInUse
        }
    }

    private suspend fun quiesceScope(
        scope: SessionRemovalScope,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> {
        val accounts = ScopeFingerprint.from(scope).accounts
        accounts.forEach { account ->
            val result = queueCoordinator.checkpointAndRequeueForBoundary(account, gateHeldBoundaryCommit)
            when (result) {
                is DownloadAttemptInvalidationResult.Invalidated,
                is DownloadAttemptInvalidationResult.Finalizing,
                -> Unit
                DownloadAttemptInvalidationResult.RemovalInProgress ->
                    return Result.failure(IllegalStateException("Download removal is already in progress."))
                DownloadAttemptInvalidationResult.StaleAttempt ->
                    // There may be no active attempt for this account.  Only fail if the durable
                    // state still claims that a writer is active; ordinary queued/completed rows
                    // require no quiescence callback.
                    if (queueRepository.allDownloads().any { record ->
                            record.businessKey.accountIdentity == account &&
                                record.state.ownsActiveDownloadSlot
                        }
                    ) {
                        return Result.failure(IllegalStateException("Active download has no live lease."))
                    }
            }
        }
        return Result.success(Unit)
    }

    private suspend fun resultFromBoundaryCheckpointSuspend(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): Result<Unit> =
        when (val result = queueCoordinator.checkpointAndRequeueForBoundary(accountIdentity, gateHeldBoundaryCommit)) {
            is DownloadAttemptInvalidationResult.Invalidated,
            is DownloadAttemptInvalidationResult.Finalizing,
            -> Result.success(Unit)
            DownloadAttemptInvalidationResult.RemovalInProgress ->
                Result.failure(IllegalStateException("Download removal is already in progress."))
            DownloadAttemptInvalidationResult.StaleAttempt -> {
                val active =
                    queueRepository.allDownloads().any { record ->
                        record.businessKey.accountIdentity == accountIdentity && record.state.ownsActiveDownloadSlot
                    }
                if (active) {
                    Result.failure(IllegalStateException("Active download has no current lease."))
                } else {
                    Result.success(Unit)
                }
            }
        }

    private suspend fun previewLocked(scope: SessionRemovalScope): DownloadRemovalPreview =
        when (scope) {
            is SessionRemovalScope.Account -> removalStore.preview(scope.accountIdentity)
            is SessionRemovalScope.FullLogout -> removalStore.previewAll(ScopeFingerprint.from(scope).accounts)
        }

    private fun authorize(
        scope: SessionRemovalScope,
        authorization: SessionRemovalAuthorization,
        preview: DownloadRemovalPreview,
    ) {
        val hasDownloads = preview.confirmations.any { confirmation -> confirmation.recordCount > 0L }
        when (authorization) {
            SessionRemovalAuthorization.None -> {
                if (hasDownloads) throw SessionRemovalError.DownloadRemovalConfirmationRequired
            }
            is SessionRemovalAuthorization.Confirmed -> {
                val token =
                    authorization.participantToken as? DownloadRemovalAuthorizationToken
                        ?: throw SessionRemovalError.ConfirmationStale
                if (token.scope != ScopeFingerprint.from(scope) || token.preview != preview) {
                    throw SessionRemovalError.ConfirmationStale
                }
            }
        }
    }

    private suspend fun <T> withDeletionGuard(
        identities: Set<OfflineArtifactLeaseIdentity>,
        block: suspend () -> T,
    ): GuardedResult<T> {
        if (identities.isEmpty()) return GuardedResult.Granted(block())
        return when (val result = artifactLeaseRegistry.withDeletionGuard(identities, block)) {
            OfflineArtifactDeletionGuardResult.ArtifactInUse -> GuardedResult.ArtifactInUse
            is OfflineArtifactDeletionGuardResult.Granted -> GuardedResult.Granted(result.value)
        }
    }

    private fun now(): Long = nowEpochMilliseconds().also { value -> require(value >= 0L) }

    private data class ScopeFingerprint(
        val kind: DownloadRemovalKind,
        val accounts: List<AccountIdentity>,
    ) {
        companion object {
            fun from(scope: SessionRemovalScope): ScopeFingerprint =
                when (scope) {
                    is SessionRemovalScope.Account ->
                        ScopeFingerprint(DownloadRemovalKind.SingleAccount, listOf(scope.accountIdentity))
                    is SessionRemovalScope.FullLogout ->
                        ScopeFingerprint(
                            DownloadRemovalKind.FullLogout,
                            scope.accountIdentities.distinct().sortedWith(
                                compareBy(AccountIdentity::serverId, AccountIdentity::userId),
                            ),
                        )
                }
        }
    }

    private data class DownloadRemovalAuthorizationToken(
        val scope: ScopeFingerprint,
        val preview: DownloadRemovalPreview,
    )

    private data class PreparedDownloadRemoval(
        val operation: DownloadRemovalOperation,
    )

    private data class ArtifactDeletionOutcome(
        val remaining: List<DownloadArtifactKey>,
        val failures: List<Throwable>,
    )

    private sealed interface GuardedResult<out T> {
        data object ArtifactInUse : GuardedResult<Nothing>

        data class Granted<T>(
            val value: T,
        ) : GuardedResult<T>
    }

    private fun <T> Result<T>.getOrThrow(): T = getOrElse { throwable -> throw throwable }

    private fun List<DownloadRecord>.filterFor(scope: SessionRemovalScope): List<DownloadRecord> {
        val accounts = ScopeFingerprint.from(scope).accounts.toSet()
        return filter { record -> record.businessKey.accountIdentity in accounts }
    }
}

private fun newDownloadRemovalOperationId(): DownloadRemovalOperationId =
    DownloadRemovalOperationId("removal-${Random.nextLong().toString(16)}")
