// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult

data class OpenSubtitleSearchUiState(
    val loading: Boolean = true,
    val results: List<OpenSubtitleSearchResult> = emptyList(),
    val downloadingFileId: String? = null,
    val error: Boolean = false,
    val quotaRemaining: Int? = null,
    val quotaResetTime: String? = null,
)

internal class DetailSubtitleSelectionOwner

internal data class DetailSubtitleSelectionToken(
    val owner: DetailSubtitleSelectionOwner,
    val revision: Int,
)

internal data class InstalledSubtitleAcknowledgement(
    val asset: LocalSubtitleAsset,
    val selectionToken: DetailSubtitleSelectionToken,
)

internal sealed interface OpenSubtitleSearchIntent {
    data object Search : OpenSubtitleSearchIntent

    data class Install(
        val result: OpenSubtitleSearchResult,
        val selectionToken: DetailSubtitleSelectionToken,
    ) : OpenSubtitleSearchIntent
}
