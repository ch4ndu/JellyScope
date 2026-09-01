// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import androidx.compose.runtime.Composable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.downloads_bytes_gb
import com.jellyscope.ui.generated.resources.downloads_bytes_mb
import org.jetbrains.compose.resources.stringResource

internal const val DOWNLOAD_BYTES_PER_MB = 1_000_000L
internal const val DOWNLOAD_BYTES_PER_GB = 1_000_000_000L

internal enum class IntegerByteUnit {
    MB,
    GB,
}

internal data class IntegerByteDisplay(
    val value: Long,
    val unit: IntegerByteUnit,
)

internal fun integerByteDisplay(bytes: Long): IntegerByteDisplay =
    if (bytes >= DOWNLOAD_BYTES_PER_GB) {
        IntegerByteDisplay(bytes / DOWNLOAD_BYTES_PER_GB, IntegerByteUnit.GB)
    } else {
        IntegerByteDisplay(bytes / DOWNLOAD_BYTES_PER_MB, IntegerByteUnit.MB)
    }

@Composable
internal fun formatIntegerBytes(bytes: Long): String {
    val display = integerByteDisplay(bytes)
    return stringResource(
        when (display.unit) {
            IntegerByteUnit.MB -> Res.string.downloads_bytes_mb
            IntegerByteUnit.GB -> Res.string.downloads_bytes_gb
        },
        display.value,
    )
}
