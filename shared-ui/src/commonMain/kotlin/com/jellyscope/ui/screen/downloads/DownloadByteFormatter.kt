// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import androidx.compose.runtime.Composable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.downloads_bytes_gib
import com.jellyscope.ui.generated.resources.downloads_bytes_mib
import org.jetbrains.compose.resources.stringResource

internal const val DOWNLOAD_BYTES_PER_MIB = 1_048_576L
internal const val DOWNLOAD_BYTES_PER_GIB = 1_073_741_824L

internal enum class IntegerByteUnit {
    MiB,
    GiB,
}

internal data class IntegerByteDisplay(
    val value: Long,
    val unit: IntegerByteUnit,
)

internal fun integerByteDisplay(bytes: Long): IntegerByteDisplay =
    if (bytes >= DOWNLOAD_BYTES_PER_GIB) {
        IntegerByteDisplay(bytes / DOWNLOAD_BYTES_PER_GIB, IntegerByteUnit.GiB)
    } else {
        IntegerByteDisplay(bytes / DOWNLOAD_BYTES_PER_MIB, IntegerByteUnit.MiB)
    }

@Composable
internal fun formatIntegerBytes(bytes: Long): String {
    val display = integerByteDisplay(bytes)
    return stringResource(
        when (display.unit) {
            IntegerByteUnit.MiB -> Res.string.downloads_bytes_mib
            IntegerByteUnit.GiB -> Res.string.downloads_bytes_gib
        },
        display.value,
    )
}
