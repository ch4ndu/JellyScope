// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** Owns reporting state, periodic sampling, and queue settlement; UI semantics stay with the caller. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class PlaybackReportingCoordinator(
    private val queue: PlaybackReportingQueue,
    private val scope: CoroutineScope,
    private val playbackState: StateFlow<PlaybackState>,
) {
    private var generation = 0L
    private var lifecycle: ReportingLifecycle = ReportingLifecycle.Empty

    // Admission precedes synchronous enqueue; the receipt attaches later and survives disposal.
    private var stopResult: Deferred<Boolean>? = null
    private var periodicProgressJob: Job? = null
    private var retryTransition: RetryReportingTransition? = null

    /** The owner must settle the outgoing Stop before installing a new session. */
    fun install(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
    ) {
        if (lifecycle == ReportingLifecycle.Disposed) return
        cancelPeriodicProgress()
        lifecycle =
            ReportingLifecycle.Installed(
                PlaybackReportingSession(
                    generation = ++generation,
                    session = session,
                    plan = plan,
                    playSessionId = playSessionId,
                ),
            )
        stopResult = null
        retryTransition = null
    }

    /**
     * Arms a same-item Retry of the installed session whose native samples start transitionally
     * (VLCKit re-prepares paused at raw zero). Until settled, every report uses [targetPositionMs]
     * and a Pause edge is suppressed while [playIntended]. The first Buffering or Playing sample of
     * a prepare epoch other than [priorPrepareEpoch], or [settleRetryTransition], settles it; Failed
     * and Completed keep the target for the next stop. Returns false when nothing is installed.
     */
    fun beginRetryTransition(
        targetPositionMs: Long,
        playIntended: Boolean,
        priorPrepareEpoch: Long?,
    ): Boolean {
        val session = lifecycle.session
        if (session == null) return false
        retryTransition =
            RetryReportingTransition(
                generation = session.generation,
                targetPositionMs = targetPositionMs.coerceAtLeast(0L),
                playIntended = playIntended,
                priorPrepareEpoch = priorPrepareEpoch,
            )
        return true
    }

    /** Settles an armed Retry target before an explicit user command; returns whether one was armed. */
    fun settleRetryTransition(): Boolean {
        val armed = currentRetryTransition() != null
        retryTransition = null
        return armed
    }

    /**
     * [prepareEpoch] is the native prepare epoch that produced [current]; null is unknown and
     * never settles a Retry transition. Returns true when this sample settled one.
     */
    fun onPlaybackState(
        current: PlaybackState,
        previousStatus: PlaybackStatus,
        prepareEpoch: Long? = null,
    ): Boolean {
        val transition = currentRetryTransition()
        val settled =
            transition != null &&
                prepareEpoch != null &&
                prepareEpoch != transition.priorPrepareEpoch &&
                (current.status == PlaybackStatus.Buffering || current.status == PlaybackStatus.Playing)
        if (settled) retryTransition = null
        if (lifecycle == ReportingLifecycle.Disposed || lifecycle is ReportingLifecycle.StopAdmitted) {
            cancelPeriodicProgress()
            return settled
        }
        val reported = currentRetryTransition()?.let { armed -> current.copy(positionMs = armed.targetPositionMs) } ?: current
        maybeStart(reported)
        val syntheticPause = currentRetryTransition()?.playIntended == true && current.status == PlaybackStatus.Paused
        if (!syntheticPause) {
            reportStatusEdge(reported, previousStatus)
        }
        updatePeriodicProgress(current.status)
        return settled
    }

    fun progress(
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        val session = lifecycle.session ?: return
        if (lifecycle !is ReportingLifecycle.Starting && lifecycle !is ReportingLifecycle.Started) return
        queue.enqueueProgress(
            reportingSession = session,
            positionMs = reportedPosition(positionMs),
            isPaused = isPaused,
            eventName = eventName,
        )
    }

    fun stop(
        positionMs: Long,
        completed: Boolean = false,
    ) {
        val reportedPositionMs = consumeReportedStopPosition(positionMs)
        val session = sessionEligibleForStop() ?: return
        lifecycle = ReportingLifecycle.StopAdmitted(session)
        cancelPeriodicProgress()
        stopResult =
            queue.enqueueStop(
                reportingSession = session,
                positionMs = reportedPositionMs,
                completed = completed,
            )
    }

    suspend fun stopNow(
        positionMs: Long,
        completed: Boolean = false,
    ) {
        val reportedPositionMs = consumeReportedStopPosition(positionMs)
        stopResult?.let { result ->
            result.await()
            return
        }
        val session = sessionEligibleForStop() ?: return
        lifecycle = ReportingLifecycle.StopAdmitted(session)
        cancelPeriodicProgress()
        val result =
            queue.enqueueStop(
                reportingSession = session,
                positionMs = reportedPositionMs,
                completed = completed,
            )
        stopResult = result
        result.await()
    }

    fun dispose(
        positionMs: Long,
        completed: Boolean = playbackState.value.status == PlaybackStatus.Completed,
    ) {
        if (lifecycle == ReportingLifecycle.Disposed) return
        stop(positionMs, completed)
        lifecycle = ReportingLifecycle.Disposed
        cancelPeriodicProgress()
        queue.closeAfterDrain()
    }

    private fun maybeStart(playbackState: PlaybackState) {
        if (!playbackState.status.isReadyForStartReport()) return
        val session = (lifecycle as? ReportingLifecycle.Installed)?.session ?: return
        lifecycle = ReportingLifecycle.Starting(session)
        val result =
            queue.enqueueStart(
                reportingSession = session,
                positionMs = playbackState.positionMs,
            )
        scope.launch {
            val succeeded = result.await()
            if (lifecycle.session?.generation != session.generation) return@launch
            if (lifecycle is ReportingLifecycle.Starting) {
                lifecycle = if (succeeded) ReportingLifecycle.Started(session) else ReportingLifecycle.Installed(session)
            }
        }
    }

    private fun reportStatusEdge(
        current: PlaybackState,
        previousStatus: PlaybackStatus,
    ) {
        if ((lifecycle !is ReportingLifecycle.Starting && lifecycle !is ReportingLifecycle.Started) || previousStatus == current.status) {
            return
        }
        when (current.status) {
            PlaybackStatus.Paused ->
                progress(
                    positionMs = current.positionMs,
                    isPaused = true,
                    eventName = PlaybackProgressEvent.Pause,
                )
            PlaybackStatus.Playing ->
                if (previousStatus == PlaybackStatus.Paused) {
                    progress(
                        positionMs = current.positionMs,
                        isPaused = false,
                        eventName = PlaybackProgressEvent.Unpause,
                    )
                }
            else -> Unit
        }
    }

    private fun updatePeriodicProgress(status: PlaybackStatus) {
        if (status != PlaybackStatus.Playing) {
            cancelPeriodicProgress()
            return
        }
        if (periodicProgressJob != null) return
        val reportingSession = lifecycle.session ?: return
        periodicProgressJob =
            scope.launch {
                while (playbackState.value.status == PlaybackStatus.Playing) {
                    delay(reportingSession.plan.progressReportingPolicy.reportIntervalMs)
                    if (
                        lifecycle.session?.generation != reportingSession.generation ||
                        playbackState.value.status != PlaybackStatus.Playing
                    ) {
                        return@launch
                    }
                    progress(
                        positionMs = playbackState.value.positionMs,
                        isPaused = false,
                        eventName = PlaybackProgressEvent.TimeUpdate,
                    )
                }
            }
    }

    private fun sessionEligibleForStop(): PlaybackReportingSession? {
        if (lifecycle !is ReportingLifecycle.Starting && lifecycle !is ReportingLifecycle.Started) return null
        return lifecycle.session
    }

    private fun cancelPeriodicProgress() {
        periodicProgressJob?.cancel()
        periodicProgressJob = null
    }

    private fun currentRetryTransition(): RetryReportingTransition? =
        retryTransition?.takeIf { transition -> transition.generation == lifecycle.session?.generation }

    private fun reportedPosition(positionMs: Long): Long = currentRetryTransition()?.targetPositionMs ?: positionMs

    /** A stop is the only terminal consumer of an armed Retry target. */
    private fun consumeReportedStopPosition(positionMs: Long): Long {
        val reportedPositionMs = reportedPosition(positionMs)
        retryTransition = null
        return reportedPositionMs
    }
}

/** A Retry target owned by one reporting installation. */
private data class RetryReportingTransition(
    val generation: Long,
    val targetPositionMs: Long,
    val playIntended: Boolean,
    val priorPrepareEpoch: Long?,
)

private sealed interface ReportingLifecycle {
    val session: PlaybackReportingSession? get() = null

    data object Empty : ReportingLifecycle

    data class Installed(
        override val session: PlaybackReportingSession,
    ) : ReportingLifecycle

    data class Starting(
        override val session: PlaybackReportingSession,
    ) : ReportingLifecycle

    data class Started(
        override val session: PlaybackReportingSession,
    ) : ReportingLifecycle

    data class StopAdmitted(
        override val session: PlaybackReportingSession,
    ) : ReportingLifecycle

    data object Disposed : ReportingLifecycle
}
