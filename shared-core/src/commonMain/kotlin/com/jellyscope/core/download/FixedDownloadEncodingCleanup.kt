// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Admission awaits cleanup; transfers hand it to the app so local settlement never awaits HTTP. */
internal suspend fun <T> withFixedDownloadEncodingCleanup(
    cleanupScope: CoroutineScope? = null,
    cleanupDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    cleanup: suspend () -> Unit,
    block: suspend () -> T,
): T =
    try {
        block()
    } finally {
        if (cleanupScope == null) {
            withContext(NonCancellable) {
                try {
                    cleanup()
                } catch (failure: Throwable) {
                    fixedDownloadCleanupLogger.w { formatSafeFailureDiagnostic("fixed-download", "encoding-cleanup-failed", failure) }
                }
            }
        } else {
            // This must be an application child, never a child of the cancelling writer.
            cleanupScope.launch(cleanupDispatcher) {
                try {
                    cleanup()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    fixedDownloadCleanupLogger.w { formatSafeFailureDiagnostic("fixed-download", "encoding-cleanup-failed", failure) }
                }
            }
        }
    }

private val fixedDownloadCleanupLogger = diagnosticLogger(DiagnosticTag.FixedDownload)
