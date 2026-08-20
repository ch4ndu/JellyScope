// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.OriginalDownloadDraft

/** Payload-free result of the authenticated Original admission boundary. */
sealed interface OriginalDownloadAdmissionResult {
    data class Ready(
        val request: DownloadRequest,
    ) : OriginalDownloadAdmissionResult

    data class Rejected(
        val decision: DownloadAdmissionDecision,
    ) : OriginalDownloadAdmissionResult
}

/**
 * Domain-facing admission seam used before a request is persisted. Implementations live behind
 * the data/repository boundary; callers provide only an untrusted Original draft, never a
 * caller-invented size, validator, or reservation.
 */
interface OriginalDownloadAdmission {
    suspend fun admit(draft: OriginalDownloadDraft): OriginalDownloadAdmissionResult

    /** Performs the final lease-guarded enqueue immediately after admission. */
    suspend fun admitAndEnqueue(
        draft: OriginalDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult
}
