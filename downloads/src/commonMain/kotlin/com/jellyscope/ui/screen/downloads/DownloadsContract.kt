// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage

data class DownloadsUiState(
    val records: List<DownloadRecord> = emptyList(),
    val sections: List<DownloadsSection> = downloadSections(records),
    val usage: DownloadUsage? = null,
    val settings: DownloadSettings? = null,
    val isLoading: Boolean = true,
    val isRefreshingUsage: Boolean = false,
    val inFlightDownloadId: String? = null,
    val isBulkResumeInFlight: Boolean = false,
    val leasedDownloadIds: Set<String> = emptySet(),
    val detailsByDownloadId: Map<DownloadId, DownloadDetailUi> = emptyMap(),
    val error: DownloadsUiError? = null,
)

sealed interface DownloadsIntent {
    data object Refresh : DownloadsIntent

    data object WakeQueueOnScreenResume : DownloadsIntent

    data class ConfigureQuota(
        val quotaBytes: Long?,
    ) : DownloadsIntent

    data class Pause(
        val downloadId: String,
    ) : DownloadsIntent

    data class Resume(
        val downloadId: String,
    ) : DownloadsIntent

    data object ResumePausedDownloads : DownloadsIntent

    data class Retry(
        val downloadId: String,
    ) : DownloadsIntent

    data class Cancel(
        val downloadId: String,
    ) : DownloadsIntent

    data class Delete(
        val downloadId: String,
    ) : DownloadsIntent
}
