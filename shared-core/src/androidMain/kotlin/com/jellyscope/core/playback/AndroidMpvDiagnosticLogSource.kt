// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal const val ANDROID_MPV_DIAGNOSTIC_LOG_NAME = "mpv-verbose.log"
internal const val ANDROID_MPV_DIAGNOSTIC_LOG_PREVIOUS_NAME = "mpv-verbose.prev.log"

/**
 * App-internal storage on purpose: the raw file contains the stream URL and
 * the Authorization header (mpv echoes `http-header-fields` at verbose level,
 * device-verified), and external-files is readable by other storage-permission
 * apps on API 25-28. Raw files never enter client-log uploads.
 */
internal fun androidMpvDiagnosticLogDirectory(context: Context): File? =
    runCatching { File(context.filesDir, "mpv-logs").apply { mkdirs() } }.getOrNull()

/**
 * Owns deletion of raw mpv verbose logs when diagnostic collection is
 * disabled. The raw files remain app-internal and are never uploaded.
 */
internal class AndroidMpvDiagnosticLogSource internal constructor(
    private val directoryProvider: () -> File?,
) : NativeDiagnosticLogSource {
    constructor(context: Context) : this(
        directoryProvider = { androidMpvDiagnosticLogDirectory(context.applicationContext) },
    )

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val directory = directoryProvider() ?: return@withContext
            listOf(ANDROID_MPV_DIAGNOSTIC_LOG_NAME, ANDROID_MPV_DIAGNOSTIC_LOG_PREVIOUS_NAME).forEach { name ->
                runCatching { File(directory, name).delete() }
            }
        }
    }
}
