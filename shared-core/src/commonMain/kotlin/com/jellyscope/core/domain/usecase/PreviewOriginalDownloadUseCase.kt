// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.domain.model.OriginalDownloadDraft

/**
 * Runs the authenticated Original admission boundary without persisting a
 * record. The UI receives only trusted request facts for its confirmation
 * summary; it never receives a repository, API, or platform host.
 */
class PreviewOriginalDownloadUseCase(
    private val admission: OriginalDownloadAdmission,
) {
    suspend operator fun invoke(draft: OriginalDownloadDraft): OriginalDownloadAdmissionResult = admission.admit(draft)
}
