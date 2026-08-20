// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.FixedDownloadDraft

sealed interface FixedDownloadAdmissionResult {
    data class Ready(
        val request: DownloadRequest,
    ) : FixedDownloadAdmissionResult

    data class Rejected(
        val decision: DownloadAdmissionDecision,
    ) : FixedDownloadAdmissionResult
}

/** Authenticated admission boundary for one fixed-quality VOD HLS request. */
interface FixedDownloadAdmission {
    suspend fun admit(draft: FixedDownloadDraft): FixedDownloadAdmissionResult

    suspend fun admitAndEnqueue(
        draft: FixedDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult
}
