// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.PLAYBACK_TIMELINE_FILENAME
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS export document picker. The timeline is staged as one file in a fixed app-private temporary
 * directory. Every write and removal of that directory, from any exporter, holds one process-wide
 * lock, and a save holds it from staging through its own cleanup, so no cleanup removes another
 * save's file and a cancelled save does not leave its file behind. The next entry removes what an
 * interrupted presentation (for example process termination) left. A removal the system refuses is
 * logged as closed evidence, turns that save's outcome into Failure, and blocks reuse of the
 * directory until a later removal succeeds. Presenting the picker is the handoff: once the user
 * exports, the system owns that copy.
 */
@Composable
actual fun rememberPlaybackTimelineExporter(host: PlaybackTimelineSaveHost): PlaybackTimelineExporter {
    val currentHost by rememberUpdatedState(host)
    val currentViewController by rememberUpdatedState(LocalUIViewController.current)
    val scope = rememberCoroutineScope()
    val exporter =
        remember(scope) {
            IosPlaybackTimelineExporter(
                scope = scope,
                host = { currentHost },
                viewController = { currentViewController },
            )
        }
    LaunchedEffect(exporter) { removeStaging() }
    DisposableEffect(exporter) {
        onDispose {
            // Scope cancellation then runs the save's own cleanup under the lock.
            exporter.dismissPicker()
            currentHost.releasePlaybackTimelineOperation()
        }
    }
    return exporter
}

private class IosPlaybackTimelineExporter(
    private val scope: CoroutineScope,
    private val host: () -> PlaybackTimelineSaveHost,
    private val viewController: () -> UIViewController,
) : PlaybackTimelineExporter {
    private var activeOperation: Long? = null

    // UIKit holds the delegate weakly, so the exporter retains it only while the picker is presented.
    private var picker: UIDocumentPickerViewController? = null
    private var pickerDelegate: PickerDelegate? = null

    override fun launch(operationId: Long) {
        // Stale, or collection changed after the request was queued: the host ignores the former
        // and ends the latter.
        val timeline = host().deliverablePlaybackTimeline(operationId)
        if (timeline == null) {
            host().onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Expired)
            return
        }
        // The host admits no new save until the previous one reports, so this is a guard only.
        if (activeOperation != null) {
            playbackTimelineExportLogger.w { "stage=playback-timeline event=save-busy" }
            host().onPlaybackTimelineSaveResult(operationId, PlaybackTimelineSaveResult.Failure)
            return
        }
        activeOperation = operationId
        scope.launch {
            val result =
                try {
                    stagingLock.withLock { stageAndExport(operationId, timeline.json) }
                } catch (exception: CancellationException) {
                    // Disposal: the host already released the operation and cleanup has run.
                    throw exception
                } catch (exception: Throwable) {
                    playbackTimelineExportLogger.w {
                        formatSafeFailureDiagnostic(stage = "playback-timeline", event = "save-stage-failed", throwable = exception)
                    }
                    PlaybackTimelineSaveResult.Failure
                }
            // Reported only after cleanup released the lock; a next save starts from an empty directory
            // or fails staging rather than reusing one that could not be cleared.
            activeOperation = null
            host().onPlaybackTimelineSaveResult(operationId, result)
        }
    }

    fun dismissPicker() {
        val presented = picker ?: return
        presented.delegate = null
        presented.dismissViewControllerAnimated(false, completion = null)
        picker = null
        pickerDelegate = null
    }

    /**
     * Runs with [stagingLock] held and removes the staged file before returning. If that removal
     * fails, the outcome is [PlaybackTimelineSaveResult.Failure] even after the picker answered,
     * because the app-private copy is still on disk; a copy the user exported may still exist.
     */
    private suspend fun stageAndExport(
        operationId: Long,
        json: String,
    ): PlaybackTimelineSaveResult {
        var cleanedUp = false
        val result =
            try {
                val staged = withContext(platformIoDispatcher()) { stage(json) }
                // Final current-operation and collection check before the OS handoff.
                if (host().deliverablePlaybackTimeline(operationId) == null) {
                    PlaybackTimelineSaveResult.Expired
                } else {
                    present(staged)
                }
            } finally {
                dismissPicker()
                // Also runs when cancelled. A write already in flight returned before this removal.
                cleanedUp = withContext(NonCancellable + platformIoDispatcher()) { removeStagingDirectory() }
                if (!cleanedUp) logStagingCleanupFailure()
            }
        return if (cleanedUp) result else PlaybackTimelineSaveResult.Failure
    }

    private suspend fun present(staged: NSURL): PlaybackTimelineSaveResult {
        var presenter = viewController()
        while (true) presenter = presenter.presentedViewController ?: break
        if (presenter.viewIfLoaded?.window == null) return PlaybackTimelineSaveResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            val delegate =
                PickerDelegate { saved ->
                    // The picker dismisses itself after either answer.
                    picker = null
                    pickerDelegate = null
                    continuation.resume(if (saved) PlaybackTimelineSaveResult.Saved else PlaybackTimelineSaveResult.Cancelled)
                }
            val controller = UIDocumentPickerViewController(forExportingURLs = listOf(staged), asCopy = true)
            controller.delegate = delegate
            picker = controller
            pickerDelegate = delegate
            presenter.presentViewController(controller, animated = true, completion = null)
        }
    }
}

private class PickerDelegate(
    private val onFinished: (saved: Boolean) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onFinished(true)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onFinished(false)
    }
}

/** Guards the single process-wide staging directory across every exporter instance. */
private val stagingLock = Mutex()

/** One-shot next-entry cleanup; a failure is logged and the next save's staging re-checks it. */
private suspend fun removeStaging() {
    val removed = stagingLock.withLock { withContext(platformIoDispatcher()) { removeStagingDirectory() } }
    if (!removed) logStagingCleanupFailure()
}

@OptIn(ExperimentalForeignApi::class)
private fun stage(json: String): NSURL {
    // A directory that could not be cleared is never reused for a new save.
    check(removeStagingDirectory()) { "Staging cleanup failed" }
    val directory = stagingDirectoryPath()
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    ) { "Staging directory unavailable" }
    val file = NSURL.fileURLWithPath("$directory/$PLAYBACK_TIMELINE_FILENAME", isDirectory = false)
    val bytes = json.encodeToByteArray()
    val data = bytes.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong()) }
    check(data.writeToURL(file, atomically = true)) { "Staging write failed" }
    return file
}

/**
 * Returns true when no staging directory remains; an absent directory is the normal clean state.
 * Never throws, so cleanup in a finally block or the entry effect cannot crash the route.
 */
@OptIn(ExperimentalForeignApi::class)
private fun removeStagingDirectory(): Boolean =
    runCatching {
        val manager = NSFileManager.defaultManager
        val path = stagingDirectoryPath()
        !manager.fileExistsAtPath(path) ||
            manager.removeItemAtPath(path, error = null) ||
            !manager.fileExistsAtPath(path)
    }.getOrDefault(false)

/** Closed evidence only; the native error and path are never logged. */
private fun logStagingCleanupFailure() {
    playbackTimelineExportLogger.w { "stage=playback-timeline event=staging-cleanup-failed" }
}

private fun stagingDirectoryPath(): String = NSTemporaryDirectory().trimEnd('/') + "/" + STAGING_DIRECTORY_NAME

/** The only app-owned export location; it holds at most one staged timeline. */
private const val STAGING_DIRECTORY_NAME = "playback-timeline-export"

private val playbackTimelineExportLogger = diagnosticLogger(DiagnosticTag.PlaybackTimeline)
