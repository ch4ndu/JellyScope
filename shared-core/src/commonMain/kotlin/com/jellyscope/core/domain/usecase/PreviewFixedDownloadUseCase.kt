// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.model.FixedDownloadDraft

/**
 * Runs the authenticated fixed-quality admission boundary without persisting a record.
 *
 * The UI receives only trusted size/source facts from the admission boundary. It never
 * constructs a transcode URL or estimates a fixed package from local metadata.
 */
class PreviewFixedDownloadUseCase(
    private val admission: FixedDownloadAdmission,
) {
    suspend operator fun invoke(draft: FixedDownloadDraft): FixedDownloadAdmissionResult = admission.admit(draft)
}
