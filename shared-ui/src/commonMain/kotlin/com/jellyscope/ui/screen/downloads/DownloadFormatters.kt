// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.download_state_blocked
import com.jellyscope.ui.generated.resources.download_state_completed
import com.jellyscope.ui.generated.resources.download_state_downloading
import com.jellyscope.ui.generated.resources.download_state_failed
import com.jellyscope.ui.generated.resources.download_state_finalizing
import com.jellyscope.ui.generated.resources.download_state_paused
import com.jellyscope.ui.generated.resources.download_state_queued
import org.jetbrains.compose.resources.StringResource

internal fun DownloadState.labelResource(): StringResource =
    when (this) {
        DownloadState.Queued -> Res.string.download_state_queued
        DownloadState.Downloading -> Res.string.download_state_downloading
        DownloadState.Paused -> Res.string.download_state_paused
        DownloadState.BlockedByQuota -> Res.string.download_state_blocked
        DownloadState.Finalizing -> Res.string.download_state_finalizing
        DownloadState.Completed -> Res.string.download_state_completed
        DownloadState.Failed -> Res.string.download_state_failed
        DownloadState.NotDownloaded -> Res.string.download_state_failed
    }
