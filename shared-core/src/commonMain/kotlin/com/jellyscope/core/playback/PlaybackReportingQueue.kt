// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackReportingOperation
import com.jellyscope.core.domain.playback.PlaybackReportingResult
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class PlaybackReportingSession(
    val generation: Long,
    val session: Session,
    val plan: PlaybackPlan,
    val playSessionId: String,
)

/** Serializes Start -> Progress -> Stopped on a scope that can outlive the UI owner. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class PlaybackReportingQueue(
    private val reporter: PlaybackProgressReporter,
    dispatcher: CoroutineDispatcher,
    private val settlementRegistry: PlaybackStopSettlementRegistry,
) {
    private val logger = diagnosticLogger(DiagnosticTag.PlaybackReportingQueue)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var closeRequested = false

    init {
        scope.launch {
            val startedGenerations = mutableSetOf<Long>()
            val stoppedGenerations = mutableSetOf<Long>()
            for (command in commands) {
                when (command) {
                    is Command.Start -> {
                        val generation = command.reportingSession.generation
                        val succeeded =
                            when {
                                generation in stoppedGenerations -> false
                                generation in startedGenerations -> true
                                else ->
                                    reportSafely {
                                        reporter.reportStart(
                                            session = command.reportingSession.session,
                                            plan = command.reportingSession.plan,
                                            playSessionId = command.reportingSession.playSessionId,
                                            positionMs = command.positionMs,
                                        )
                                    }.also { success ->
                                        if (success) {
                                            startedGenerations += generation
                                        }
                                    }
                            }
                        logReportingResult(
                            reportingSession = command.reportingSession,
                            operation = PlaybackReportingOperation.Start,
                            succeeded = succeeded,
                        )
                        command.result.complete(succeeded)
                    }
                    is Command.Progress -> {
                        val generation = command.reportingSession.generation
                        if (generation in startedGenerations && generation !in stoppedGenerations) {
                            reportSafely {
                                reporter.reportProgress(
                                    session = command.reportingSession.session,
                                    plan = command.reportingSession.plan,
                                    playSessionId = command.reportingSession.playSessionId,
                                    positionMs = command.positionMs,
                                    isPaused = command.isPaused,
                                    eventName = command.eventName,
                                )
                            }.also { success ->
                                if (!success) {
                                    logReportingResult(
                                        reportingSession = command.reportingSession,
                                        operation = PlaybackReportingOperation.Progress,
                                        succeeded = false,
                                    )
                                }
                            }
                        } else {
                            logReportingResult(
                                reportingSession = command.reportingSession,
                                operation = PlaybackReportingOperation.Progress,
                                succeeded = false,
                            )
                        }
                    }
                    is Command.Stop -> {
                        val generation = command.reportingSession.generation
                        val attemptedStop =
                            generation !in stoppedGenerations && generation in startedGenerations
                        val succeeded =
                            when {
                                generation in stoppedGenerations -> true
                                generation !in startedGenerations -> false
                                else ->
                                    reportSafely {
                                        reporter.reportStopped(
                                            session = command.reportingSession.session,
                                            plan = command.reportingSession.plan,
                                            playSessionId = command.reportingSession.playSessionId,
                                            positionMs = command.positionMs,
                                            completed = command.completed,
                                        )
                                    }.also { success ->
                                        if (success) {
                                            stoppedGenerations += generation
                                        }
                                    }
                            }
                        logReportingResult(
                            reportingSession = command.reportingSession,
                            operation = PlaybackReportingOperation.Stop,
                            succeeded = succeeded,
                        )
                        if (attemptedStop) {
                            settlementRegistry.publish(
                                SettlementKey(
                                    serverId = command.reportingSession.session.serverId,
                                    userId = command.reportingSession.session.userId,
                                    itemId = command.reportingSession.plan.itemId,
                                ),
                            )
                        }
                        command.result.complete(succeeded)
                    }
                    Command.Close -> break
                }
            }
            commands.close()
            scope.cancel()
        }
    }

    fun enqueueStart(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
    ): Deferred<Boolean> =
        CompletableDeferred<Boolean>().also { result ->
            if (closeRequested || commands.trySend(Command.Start(reportingSession, positionMs, result)).isFailure) {
                result.complete(false)
            }
        }

    fun enqueueProgress(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        if (closeRequested) {
            return
        }
        commands.trySend(
            Command.Progress(
                reportingSession = reportingSession,
                positionMs = positionMs,
                isPaused = isPaused,
                eventName = eventName,
            ),
        )
    }

    fun enqueueStop(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
        completed: Boolean = false,
    ): Deferred<Boolean> =
        CompletableDeferred<Boolean>().also { result ->
            if (
                closeRequested ||
                commands.trySend(Command.Stop(reportingSession, positionMs, completed, result)).isFailure
            ) {
                result.complete(false)
            }
        }

    fun closeAfterDrain() {
        if (closeRequested) {
            return
        }
        closeRequested = true
        commands.trySend(Command.Close)
    }

    private suspend fun reportSafely(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            false
        } catch (_: Throwable) {
            false
        }

    private fun logReportingResult(
        reportingSession: PlaybackReportingSession,
        operation: PlaybackReportingOperation,
        succeeded: Boolean,
    ) {
        val diagnosticLine =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Reporting,
                    event = PlaybackDiagnosticEvent.Reporting,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    sessionSequence = reportingSession.plan.diagnosticSessionSequence ?: reportingSession.generation,
                    streamMode = reportingSession.plan.streamMode,
                    reportingOperation = operation,
                    reportingResult =
                        if (succeeded) {
                            PlaybackReportingResult.Success
                        } else {
                            PlaybackReportingResult.Failed
                        },
                ),
            )
        if (succeeded) {
            logger.i { diagnosticLine }
        } else {
            logger.w { diagnosticLine }
        }
    }

    private sealed interface Command {
        data class Start(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data class Progress(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val isPaused: Boolean,
            val eventName: PlaybackProgressEvent,
        ) : Command

        data class Stop(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val completed: Boolean,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data object Close : Command
    }
}
