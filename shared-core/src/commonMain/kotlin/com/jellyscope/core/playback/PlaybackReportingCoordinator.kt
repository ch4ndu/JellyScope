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
    private var installedSession: PlaybackReportingSession? = null
    private var startPending = false
    private var started = false
    private var stopRequested = false
    private var stopResult: Deferred<Boolean>? = null
    private var periodicProgressJob: Job? = null
    private var disposed = false

    /** The owner must settle the outgoing Stop before installing a new session. */
    fun install(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
    ) {
        if (disposed) return
        cancelPeriodicProgress()
        installedSession =
            PlaybackReportingSession(
                generation = ++generation,
                session = session,
                plan = plan,
                playSessionId = playSessionId,
            )
        startPending = false
        started = false
        stopRequested = false
        stopResult = null
    }

    fun onPlaybackState(
        current: PlaybackState,
        previousStatus: PlaybackStatus,
    ) {
        if (disposed || stopRequested) {
            cancelPeriodicProgress()
            return
        }
        maybeStart(current)
        reportStatusEdge(current, previousStatus)
        updatePeriodicProgress(current.status)
    }

    fun progress(
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        val session = installedSession ?: return
        if (disposed || stopRequested || (!started && !startPending)) return
        queue.enqueueProgress(
            reportingSession = session,
            positionMs = positionMs,
            isPaused = isPaused,
            eventName = eventName,
        )
    }

    fun stop(
        positionMs: Long,
        completed: Boolean = false,
    ) {
        val session = sessionEligibleForStop() ?: return
        stopRequested = true
        cancelPeriodicProgress()
        stopResult =
            queue.enqueueStop(
                reportingSession = session,
                positionMs = positionMs,
                completed = completed,
            )
    }

    suspend fun stopNow(
        positionMs: Long,
        completed: Boolean = false,
    ) {
        stopResult?.let { result ->
            result.await()
            return
        }
        val session = sessionEligibleForStop() ?: return
        stopRequested = true
        cancelPeriodicProgress()
        val result =
            queue.enqueueStop(
                reportingSession = session,
                positionMs = positionMs,
                completed = completed,
            )
        stopResult = result
        result.await()
    }

    fun dispose(
        positionMs: Long,
        completed: Boolean = playbackState.value.status == PlaybackStatus.Completed,
    ) {
        if (disposed) return
        stop(positionMs, completed)
        disposed = true
        cancelPeriodicProgress()
        queue.closeAfterDrain()
    }

    private fun maybeStart(playbackState: PlaybackState) {
        if (started || startPending || !playbackState.status.isReadyForStartReport()) return
        val session = installedSession ?: return
        startPending = true
        val result =
            queue.enqueueStart(
                reportingSession = session,
                positionMs = playbackState.positionMs,
            )
        scope.launch {
            val succeeded = result.await()
            if (installedSession?.generation != session.generation) return@launch
            startPending = false
            if (!stopRequested && !disposed) {
                started = succeeded
            }
        }
    }

    private fun reportStatusEdge(
        current: PlaybackState,
        previousStatus: PlaybackStatus,
    ) {
        if ((!started && !startPending) || previousStatus == current.status) return
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
        val reportingSession = installedSession ?: return
        periodicProgressJob =
            scope.launch {
                while (playbackState.value.status == PlaybackStatus.Playing) {
                    delay(reportingSession.plan.progressReportingPolicy.reportIntervalMs)
                    if (
                        installedSession?.generation != reportingSession.generation ||
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
        if (disposed || stopRequested || (!started && !startPending)) return null
        return installedSession
    }

    private fun cancelPeriodicProgress() {
        periodicProgressJob?.cancel()
        periodicProgressJob = null
    }
}
