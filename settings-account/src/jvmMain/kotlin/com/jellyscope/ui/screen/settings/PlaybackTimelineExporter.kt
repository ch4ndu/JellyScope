// SPDX-License-Identifier: MPL-2.0
@file:JvmName("PlaybackTimelineExporterJvm")

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.PLAYBACK_TIMELINE_FILENAME
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * JDK native save dialog. The dialog lives entirely on the AWT event thread and keeps the
 * platform's own overwrite confirmation; the file write runs on the IO dispatcher. No dependency
 * is added.
 */
@Composable
actual fun rememberPlaybackTimelineExporter(host: PlaybackTimelineSaveHost): PlaybackTimelineExporter {
    val currentHost by rememberUpdatedState(host)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        // Disposal cancels the scope, which disposes any open dialog before it can return a file.
        onDispose { currentHost.releasePlaybackTimelineOperation() }
    }
    return remember(scope) {
        object : PlaybackTimelineExporter {
            private var activeOperation: Long? = null

            override fun launch(operationId: Long) {
                // Stale, or collection changed after the request was queued: the host ignores the
                // former and ends the latter.
                if (currentHost.deliverablePlaybackTimeline(operationId) == null) {
                    currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Expired)
                    return
                }
                // The host admits no new save until the previous dialog reports, so this is a guard only.
                if (activeOperation != null) {
                    playbackTimelineExportLogger.w { "stage=playback-timeline event=save-busy" }
                    currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Failure)
                    return
                }
                activeOperation = operationId
                scope.launch {
                    try {
                        val destination =
                            try {
                                chooseDestination()
                            } catch (exception: CancellationException) {
                                // Disposal before any file was returned; the host already released
                                // the operation and nothing was written.
                                throw exception
                            } catch (exception: Throwable) {
                                playbackTimelineExportLogger.w {
                                    formatSafeFailureDiagnostic(
                                        stage = "playback-timeline",
                                        event = "save-dialog-failed",
                                        throwable = exception,
                                    )
                                }
                                currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Unavailable)
                                return@launch
                            }
                        if (destination == null) {
                            currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Cancelled)
                            return@launch
                        }
                        // Fresh authority check after the destination is chosen; stale bytes are never written.
                        val timeline = currentHost.deliverablePlaybackTimeline(operationId)
                        if (timeline == null) {
                            currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Expired)
                            return@launch
                        }
                        // Handoff boundary: from here the write is committed. It and its report finish
                        // even if collection is turned off or disposal follows. A write that fails
                        // partway may leave an incomplete file, which is never deleted.
                        withContext(NonCancellable) {
                            val result =
                                withContext(platformIoDispatcher()) {
                                    try {
                                        destination.writeBytes(timeline.json.encodeToByteArray())
                                        PlaybackTimelineSaveResult.Saved
                                    } catch (exception: Throwable) {
                                        playbackTimelineExportLogger.w {
                                            formatSafeFailureDiagnostic(
                                                stage = "playback-timeline",
                                                event = "save-write-failed",
                                                throwable = exception,
                                            )
                                        }
                                        PlaybackTimelineSaveResult.Failure
                                    }
                                }
                            currentHost.onPlaybackTimelineSaveResult(operationId, result)
                        }
                    } finally {
                        activeOperation = null
                    }
                }
            }
        }
    }
}

/**
 * Creates, shows, reads, and disposes the modal save dialog on the AWT event thread. Cancellation
 * before creation skips the dialog, and cancellation while it is shown disposes it, which ends its
 * modal loop. The continuation is resumed once; a resume after cancellation is ignored.
 */
private suspend fun chooseDestination(): File? =
    suspendCancellableCoroutine { continuation ->
        // Read and written only on the event thread.
        var shownDialog: FileDialog? = null
        continuation.invokeOnCancellation { EventQueue.invokeLater { shownDialog?.dispose() } }
        EventQueue.invokeLater {
            if (!continuation.isActive) return@invokeLater
            val outcome =
                runCatching {
                    val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
                    shownDialog = dialog
                    try {
                        dialog.file = PLAYBACK_TIMELINE_FILENAME
                        // Blocks this event-thread callback in a nested loop until the user answers.
                        dialog.isVisible = true
                        val directory = dialog.directory
                        val file = dialog.file
                        if (directory != null && file != null) File(directory, file) else null
                    } finally {
                        shownDialog = null
                        dialog.dispose()
                    }
                }
            continuation.resumeWith(outcome)
        }
    }

private val playbackTimelineExportLogger = diagnosticLogger(DiagnosticTag.PlaybackTimeline)
