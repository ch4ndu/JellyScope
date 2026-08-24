// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.DownloadCommandCoordinator
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.usecase.FixedDownloadAdmission
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmission
import com.jellyscope.core.download.DownloadLifecycleHost
import kotlinx.coroutines.CancellationException

class ConfigureDownloadQuotaAction(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(quotaBytes: Long?): DownloadSettings = repository.setQuotaBytes(quotaBytes)
}

class EnqueueDownloadAction(
    private val repository: DownloadRepository,
    private val lifecycleHost: DownloadLifecycleHost,
    private val admission: OriginalDownloadAdmission,
) {
    suspend operator fun invoke(draft: OriginalDownloadDraft): DownloadEnqueueResult {
        val result = admission.admitAndEnqueue(draft, repository::enqueue)
        val record = result.admittedRecordOrNull()
        if (record == null || wakeRejected(lifecycleHost)) {
            return if (record == null) result else DownloadEnqueueResult.SchedulingRejected(record)
        }
        return result
    }
}

/** Enqueues one fixed-quality request after authenticated admission. */
class EnqueueFixedDownloadAction(
    private val repository: DownloadRepository,
    private val lifecycleHost: DownloadLifecycleHost,
    private val admission: FixedDownloadAdmission,
) {
    suspend operator fun invoke(draft: FixedDownloadDraft): DownloadEnqueueResult {
        val result = admission.admitAndEnqueue(draft, repository::enqueue)
        val record = result.admittedRecordOrNull()
        if (record == null || wakeRejected(lifecycleHost)) {
            return if (record == null) result else DownloadEnqueueResult.SchedulingRejected(record)
        }
        return result
    }
}

private fun DownloadEnqueueResult.admittedRecordOrNull(): DownloadRecord? =
    when (this) {
        is DownloadEnqueueResult.Created -> record
        is DownloadEnqueueResult.Existing -> record
        is DownloadEnqueueResult.SchedulingRejected -> record
        DownloadEnqueueResult.RemovalInProgress,
        is DownloadEnqueueResult.Rejected,
        -> null
    }

class PauseDownloadAction(
    private val commandCoordinator: DownloadCommandCoordinator,
    private val lifecycleHost: DownloadLifecycleHost? = null,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = commandCoordinator.pause(accountIdentity, downloadId).wakeIfApplied(lifecycleHost)
}

class ResumeDownloadAction(
    private val repository: DownloadRepository,
    private val lifecycleHost: DownloadLifecycleHost,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = repository.resume(accountIdentity, downloadId).wakeIfApplied(lifecycleHost)
}

class RetryDownloadAction(
    private val repository: DownloadRepository,
    private val lifecycleHost: DownloadLifecycleHost,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = repository.retry(accountIdentity, downloadId).wakeIfApplied(lifecycleHost)
}

/**
 * Wakes the already-durable queue after a user explicitly asks the app to try scheduling it.
 *
 * This action intentionally has no repository dependency: a queued record must not be moved
 * through Failed just to retry native scheduling. The host owns the platform admission result,
 * while the durable row remains [com.jellyscope.core.domain.model.DownloadState.Queued] until the
 * normal execution driver observes it.
 */
class RetryDownloadSchedulingAction(
    private val lifecycleHost: DownloadLifecycleHost,
) {
    suspend operator fun invoke(): Result<Unit> = lifecycleHost.wakeFromUserAction()
}

private suspend fun DownloadCommandResult.wakeIfApplied(lifecycleHost: DownloadLifecycleHost?): DownloadCommandResult {
    if (this != DownloadCommandResult.Applied) return this
    if (lifecycleHost == null) return this
    return if (lifecycleHost.wakeFromUserAction().isSuccess) {
        this
    } else {
        DownloadCommandResult.SchedulingRejected
    }
}

private suspend fun wakeRejected(lifecycleHost: DownloadLifecycleHost): Boolean =
    try {
        lifecycleHost.wakeFromUserAction().isFailure
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        true
    }

class CancelDownloadAction(
    private val commandCoordinator: DownloadCommandCoordinator,
    private val lifecycleHost: DownloadLifecycleHost? = null,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult =
        commandCoordinator.cancel(accountIdentity, downloadId).also { result ->
            if (result == DownloadDeletionResult.Deleted) {
                // A cancellation may release the global active slot. Wake the common driver so
                // the next FIFO row is not stranded on app-active JVM/iOS hosts.
                try {
                    lifecycleHost?.wakeFromUserAction()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Durable cancellation remains the command result; a later lifecycle wake
                    // can recover a scheduling failure.
                }
            }
        }
}

class DeleteDownloadAction(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = repository.delete(accountIdentity, downloadId)
}

/** Persists local playback state only; it never performs network progress reporting. */
class UpdateDownloadedPlaybackAction(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean? = null,
    ): Boolean =
        repository.updateLocalPlayback(
            accountIdentity = accountIdentity,
            downloadId = downloadId,
            expectedAttemptGeneration = expectedAttemptGeneration,
            resumePositionMs = resumePositionMs,
            watched = watched,
        )
}
