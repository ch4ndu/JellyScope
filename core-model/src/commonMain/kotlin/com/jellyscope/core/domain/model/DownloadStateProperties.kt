// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

val DownloadState.isPersistedDownloadState: Boolean
    get() = this != DownloadState.NotDownloaded

val DownloadState.ownsActiveDownloadSlot: Boolean
    get() = this == DownloadState.Downloading || this == DownloadState.Finalizing

val DownloadState.retainsOutstandingReservation: Boolean
    get() =
        this == DownloadState.Queued ||
            this == DownloadState.Downloading ||
            this == DownloadState.Paused ||
            this == DownloadState.BlockedByQuota ||
            this == DownloadState.Finalizing
