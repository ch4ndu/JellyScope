// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.OpenSubtitleSearchResult

internal sealed interface OpenSubtitleSearchChange {
    data object SearchStarted : OpenSubtitleSearchChange

    data class SearchLoaded(
        val results: List<OpenSubtitleSearchResult>,
    ) : OpenSubtitleSearchChange

    data object SearchFailed : OpenSubtitleSearchChange

    data class InstallStarted(
        val fileId: String,
    ) : OpenSubtitleSearchChange

    data class Installed(
        val quotaRemaining: Int?,
        val quotaResetTime: String?,
    ) : OpenSubtitleSearchChange

    data object InstallFailed : OpenSubtitleSearchChange
}

internal fun reduceOpenSubtitleSearch(
    current: OpenSubtitleSearchUiState,
    change: OpenSubtitleSearchChange,
): OpenSubtitleSearchUiState =
    when (change) {
        OpenSubtitleSearchChange.SearchStarted -> current.copy(loading = true, error = false)
        is OpenSubtitleSearchChange.SearchLoaded -> current.copy(loading = false, results = change.results)
        OpenSubtitleSearchChange.SearchFailed -> current.copy(loading = false, error = true)
        is OpenSubtitleSearchChange.InstallStarted -> current.copy(downloadingFileId = change.fileId, error = false)
        is OpenSubtitleSearchChange.Installed ->
            current.copy(
                downloadingFileId = null,
                quotaRemaining = change.quotaRemaining,
                quotaResetTime = change.quotaResetTime,
            )
        OpenSubtitleSearchChange.InstallFailed -> current.copy(downloadingFileId = null, error = true)
    }
