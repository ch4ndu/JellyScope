// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind

internal enum class AndroidDownloadExecutionPath {
    Uidt,
    WorkManager,
}

/** SDK selection is exclusive; the caller must not use the other path as a fallback. */
internal fun androidDownloadExecutionPath(sdkInt: Int): AndroidDownloadExecutionPath =
    if (sdkInt >= 34) AndroidDownloadExecutionPath.Uidt else AndroidDownloadExecutionPath.WorkManager

internal fun androidDownloadWorkIdentity(
    path: AndroidDownloadExecutionPath,
    value: String,
): DownloadPlatformWorkIdentity =
    DownloadPlatformWorkIdentity(
        kind =
            when (path) {
                AndroidDownloadExecutionPath.Uidt -> DownloadPlatformWorkKind.AndroidUserInitiatedJob
                AndroidDownloadExecutionPath.WorkManager -> DownloadPlatformWorkKind.AndroidWorkManager
            },
        value = value,
    )
