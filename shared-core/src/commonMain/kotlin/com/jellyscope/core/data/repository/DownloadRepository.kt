// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.download.DownloadAttemptIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Domain-facing download persistence. Transfer execution remains an internal coordinator concern. */
interface DownloadRepository {
    fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>>

    suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord?

    /**
     * Credential-free preflight for an explicit offline launch.  Implementations must reread the
     * generation-bound Completed row and inspect the private artifact package, but must not
     * expose a filesystem path or acquire a playback lease here.  The controller resolver repeats
     * the check and acquires the lease immediately before native prepare.
     *
     * The default is fail-closed for lightweight implementations and test doubles that do not
     * own an artifact store.
     */
    suspend fun hasCompletedArtifact(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): Boolean = false

    /** Read-only UI hint; deletion remains guarded authoritatively by [delete]. */
    suspend fun isArtifactLeased(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): Boolean = false

    suspend fun getDownloadSettings(): DownloadSettings

    /**
     * Returns device-global quota facts plus a privacy-preserving physical-byte split for the
     * requesting account and one opaque aggregate covering every other account.
     */
    suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage

    suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings

    suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult

    suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult

    suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult

    suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult

    suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult

    suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult

    suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean? = null,
    ): Boolean
}

/**
 * User-command seam for operations that may need to quiesce the one live writer first.
 * Implementations keep the writer registration and runner serialization private; actions do not
 * receive a platform host or a network transfer handle.
 */
interface DownloadCommandCoordinator {
    suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult

    suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult
}

/** Shared lock that keeps removal preview/prepare and ordinary membership mutations ordered. */
internal class DownloadRemovalMutex {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Internal generation-checked surface used by the one application-scoped queue coordinator. */
internal interface DownloadQueueRepository {
    suspend fun allDownloads(): List<DownloadRecord>

    suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult

    suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult

    suspend fun claimOldest(
        activeAccount: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
    ): DownloadRecord?

    suspend fun updateAttemptProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean

    suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean

    suspend fun extendAttemptReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
    ): DownloadReservationExtensionResult

    suspend fun transitionAttempt(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity? = null,
        failure: DownloadFailure? = null,
    ): Boolean

    suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
    ): Boolean

    /**
     * Registers the exact live attempt that owns a writer.  A durable row is not enough to
     * perform a boundary checkpoint: the registration carries the live account lease and the
     * last writer-owned durable facts. Hosts call this after they have admitted a
     * response and before they begin streaming.
     */
    suspend fun registerActiveAttempt(registration: DownloadActiveAttemptRegistration): Boolean

    suspend fun updateRegisteredAttemptFacts(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean

    suspend fun clearRegisteredAttempt(attempt: DownloadAttemptIdentity): Boolean

    /** Revokes a claim that has not acquired a writer yet. */
    suspend fun checkpointAndInvalidateUnregisteredAttempt(
        accountIdentity: AccountIdentity,
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult

    /**
     * Closes the registered writer and atomically revokes its generation before a user command.
     * Unlike the boundary path this does not consult the session gate; it is used for pause and
     * cancel while the current account remains active.
     */
    suspend fun checkpointAndInvalidateActiveAttempt(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult

    /**
     * Used only while core already holds the non-reentrant account-boundary gate.  The
     * implementation validates the registration's actual [AccountWorkLease] through
     * [gateHeldBoundaryCommit] immediately before the atomic store mutation and never attempts
     * to reacquire the gate.
     */
    suspend fun checkpointAndRequeueForBoundary(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): DownloadAttemptInvalidationResult

    /** Reconciles a durable Finalizing row without requiring a live streaming writer lease. */
    suspend fun reconcileFinalizingForBoundary(accountIdentity: AccountIdentity): DownloadAttemptInvalidationResult
}

/** The only durable facts a writer may hand to a boundary checkpoint. */
internal data class DownloadCheckpointFacts(
    val physicalBytes: Long,
    val checkpointBytes: Long,
) {
    init {
        require(physicalBytes >= 0L) { "Physical bytes must not be negative." }
        require(checkpointBytes in 0L..physicalBytes) {
            "Checkpoint bytes must be within physical bytes."
        }
    }
}

/**
 * Explicit, bounded live-attempt registration.  It deliberately has no timer, heartbeat, or
 * retry job.  The writer callback is invoked once by boundary quiescence and may return only
 * bounded checkpoint facts; it cannot expose a network response or platform task to the queue.
 */
internal class DownloadActiveAttemptRegistration internal constructor(
    val accountIdentity: AccountIdentity,
    val lease: AccountWorkLease,
    val attempt: DownloadAttemptIdentity,
    initialFacts: DownloadCheckpointFacts,
    private val checkpointAndCloseWriter: suspend () -> DownloadCheckpointFacts,
) {
    private var durableFacts: DownloadCheckpointFacts = initialFacts
    private var writerClosed = false

    fun currentFacts(): DownloadCheckpointFacts = durableFacts

    fun updateFacts(facts: DownloadCheckpointFacts) {
        durableFacts = facts
    }

    suspend fun checkpointAndClose(): DownloadCheckpointFacts {
        if (!writerClosed) {
            durableFacts = checkpointAndCloseWriter()
            writerClosed = true
        }
        return durableFacts
    }
}
