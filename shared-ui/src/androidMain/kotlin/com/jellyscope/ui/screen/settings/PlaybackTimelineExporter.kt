// SPDX-License-Identifier: MPL-2.0
@file:JvmName("PlaybackTimelineExporterAndroid")

package com.jellyscope.ui.screen.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.PLAYBACK_TIMELINE_FILENAME
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework save. The launcher is registered on every composition, even with no
 * pending save, so a result restored after activity or process recreation still arrives here.
 * Only the operation id is saved across recreation; the payload never is. The picker cannot be
 * dismissed by the app, so the host keeps its operation current until this bridge reports.
 */
@Composable
actual fun rememberPlaybackTimelineExporter(host: PlaybackTimelineSaveHost): PlaybackTimelineExporter {
    val activity = LocalActivity.current
    val resolver = LocalContext.current.applicationContext.contentResolver
    val currentHost by rememberUpdatedState(host)
    val scope = rememberCoroutineScope()
    var launchedOperation by rememberSaveable { mutableStateOf<Long?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME_TYPE)) { destination ->
            val operationId = launchedOperation ?: NO_PLAYBACK_TIMELINE_OPERATION
            launchedOperation = null
            if (destination == null) {
                currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Cancelled)
                return@rememberLauncherForActivityResult
            }
            // Fresh authority check after the destination arrives. A restored, cancelled, or disabled
            // request writes nothing and never regenerates a different snapshot. The provider may
            // already have created the empty document; it may be a pre-existing user file, so it is
            // never deleted.
            val timeline = currentHost.deliverablePlaybackTimeline(operationId)
            if (timeline == null) {
                currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Interrupted)
                return@rememberLauncherForActivityResult
            }
            // Handoff boundary: from here the write is committed. It completes and reports even if
            // collection is turned off or this composition is disposed meanwhile. A write that fails
            // partway may leave an incomplete document, which is never deleted.
            scope.launch(NonCancellable) {
                val result =
                    try {
                        withContext(platformIoDispatcher()) {
                            val output = resolver.openOutputStream(destination, "wt") ?: error("No output stream")
                            output.use { stream -> stream.write(timeline.json.encodeToByteArray()) }
                        }
                        PlaybackTimelineSaveResult.Saved
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        playbackTimelineExportLogger.w {
                            formatSafeFailureDiagnostic(stage = "playback-timeline", event = "save-write-failed", throwable = exception)
                        }
                        PlaybackTimelineSaveResult.Failure
                    }
                currentHost.onPlaybackTimelineSaveResult(operationId, result)
            }
        }
    DisposableEffect(Unit) {
        onDispose {
            // A configuration change keeps the ViewModel and this launcher's registration, so the
            // pending save continues in the recreated activity. Any other disposal ends it.
            if (activity?.isChangingConfigurations != true) currentHost.releasePlaybackTimelineOperation()
        }
    }
    return remember(launcher) {
        object : PlaybackTimelineExporter {
            override fun launch(operationId: Long) {
                // Stale, or collection changed after the request was queued: the host ignores the
                // former and ends the latter.
                if (currentHost.deliverablePlaybackTimeline(operationId) == null) {
                    currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Expired)
                    return
                }
                // The host admits no new save until the previous picker reports, so this is a guard only.
                if (launchedOperation != null) {
                    playbackTimelineExportLogger.w { "stage=playback-timeline event=save-busy" }
                    currentHost.onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Failure)
                    return
                }
                launchedOperation = operationId
                try {
                    launcher.launch(PLAYBACK_TIMELINE_FILENAME)
                } catch (exception: Throwable) {
                    playbackTimelineExportLogger.w {
                        formatSafeFailureDiagnostic(stage = "playback-timeline", event = "save-launch-failed", throwable = exception)
                    }
                    // No document provider (common on TV) is reported as such; any other platform
                    // failure still ends the operation instead of escaping into the event collector.
                    launchedOperation = null
                    currentHost.onPlaybackTimelineSaveResult(
                        operationId,
                        if (exception is ActivityNotFoundException) {
                            PlaybackTimelineSaveResult.Unavailable
                        } else {
                            PlaybackTimelineSaveResult.Failure
                        },
                    )
                }
            }
        }
    }
}

private const val JSON_MIME_TYPE = "application/json"

private val playbackTimelineExportLogger = diagnosticLogger(DiagnosticTag.PlaybackTimeline)
