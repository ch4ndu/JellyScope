// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId

/**
 * One durable row that an explicit foreground transfer action expects a
 * continued-processing generation to finish. This remains entirely within
 * common core; native bridges receive only the opaque wake generation.
 */
data class DownloadExplicitWork(
    val downloadId: DownloadId,
    val minimumCompletionAttemptGeneration: Long,
) {
    init {
        require(minimumCompletionAttemptGeneration >= 0L) {
            "Completion attempt generation must be non-negative."
        }
    }
}

/** Exact account-scoped work enrolled by one explicit Start, Resume, or Retry action. */
data class DownloadExplicitWorkEnrollment(
    val accountIdentity: AccountIdentity,
    val work: Set<DownloadExplicitWork>,
) {
    init {
        require(work.isNotEmpty()) { "An explicit download enrollment must contain work." }
    }
}

/** Supported-app entry-point lifecycle surface; tvOS never binds this feature. */
interface DownloadLifecycleHost {
    fun start()

    fun stop()

    /**
     * Schedules the already-durable runnable head after an explicit foreground
     * enqueue/resume/retry action.  Implementations return the native admission
     * result and never substitute another SDK path.
     */
    suspend fun wakeFromUserAction(): Result<Unit>

    /**
     * Explicit wake carrying the exact durable rows that caused admission.
     * Existing hosts retain their former behavior through the default; iOS
     * uses it only to report native completion truthfully.
     */
    suspend fun wakeFromUserAction(enrollment: DownloadExplicitWorkEnrollment): Result<Unit> = wakeFromUserAction()

    /**
     * Runs durable work after a passive lifecycle, recovery, or screen event.
     *
     * The default preserves existing platform behavior. iOS overrides this so
     * observing a queued row never submits a continued-processing request.
     */
    suspend fun wakeFromPassiveEvent(): Result<Unit> = wakeFromUserAction()
}
