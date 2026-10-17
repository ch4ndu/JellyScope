// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

import com.jellyscope.core.download.IosDownloadBackgroundExecution
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.downloads_section_downloading
import com.jellyscope.ui.generated.resources.downloads_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform

/** Closed native scheduler outcomes; identifiers and NSError payloads stay in Swift. */
enum class IosDownloadSchedulerEvent {
    Registering,
    RegistrationRejected,
    Submitting,
    Submitted,
    Unavailable,
    NotPermitted,
    TooManyRequests,
    ImmediateRunIneligible,
    SubmissionFailed,
}

/**
 * Swift-owned BackgroundTasks callbacks. Values are only opaque generations
 * and numeric progress; no download identity, media data, URLs, or secrets
 * leave shared Kotlin through this boundary.
 */
interface IosDownloadBackgroundTaskCallbacks {
    fun configureContinuationLabels(
        title: String,
        subtitle: String,
    )

    fun requestContinuation(wakeGeneration: Long)

    fun reportProgress(
        wakeGeneration: Long,
        transferredBytes: Long,
        expectedBytes: Long?,
    )

    fun finishContinuation(
        wakeGeneration: Long,
        succeeded: Boolean,
    )
}

/**
 * Swift-facing installation and callback bridge for iOS continued download
 * execution. Construct it only after [MainViewController] has initialized
 * Koin, then retain its native controller for the application's lifetime.
 */
class IosDownloadBackgroundBridge {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    private val execution = KoinPlatform.getKoin().get<IosDownloadBackgroundExecution>()
    private val applicationScope = KoinPlatform.getKoin().get<CoroutineScope>()

    fun reportSchedulerEvent(
        wakeGeneration: Long,
        event: IosDownloadSchedulerEvent,
    ) {
        logger.i { "stage=download-continuation event=native-scheduler generation=$wakeGeneration result=${event.name}" }
    }

    fun install(callbacks: IosDownloadBackgroundTaskCallbacks) {
        execution.installCallbacks(
            requestGrant = callbacks::requestContinuation,
            reportProgress = callbacks::reportProgress,
            completeGrant = callbacks::finishContinuation,
        )
        applicationScope.launch {
            callbacks.configureContinuationLabels(
                title = getString(Res.string.downloads_title),
                subtitle = getString(Res.string.downloads_section_downloading),
            )
        }
    }

    /** Verifies that this concrete native handler still belongs to the requested wake. */
    fun canGrant(wakeGeneration: Long): Boolean = execution.canReceiveGrant(wakeGeneration)

    /** Returns false when a native callback belongs to a completed or superseded wake. */
    fun grant(wakeGeneration: Long): Boolean = execution.receiveGrant(wakeGeneration)

    /** Revokes the synchronized Kotlin permission snapshot before suspension cleanup begins. */
    fun expire(wakeGeneration: Long): Boolean = execution.expireGrant(wakeGeneration)

    /** Clears a denied submission while preserving the existing foreground writer. */
    fun reject(wakeGeneration: Long) = execution.rejectGrant(wakeGeneration)
}
