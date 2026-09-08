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
import com.jellyscope.core.domain.playback.SplitPlaybackProgressReporter
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
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

/** Serializes local settlement ahead of an independent ordered remote drain. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class PlaybackReportingQueue(
    private val reporter: PlaybackProgressReporter,
    dispatcher: CoroutineDispatcher,
    private val settlementRegistry: PlaybackStopSettlementRegistry,
) {
    private val logger = diagnosticLogger(DiagnosticTag.PlaybackReportingQueue)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val frontCommands = Channel<Command>(Channel.UNLIMITED)
    private val remoteCommands = Channel<RemoteCommand>(Channel.UNLIMITED)
    private val frontIngressLock = ReentrantLock()
    private val remoteIngressLock = ReentrantLock()
    private var closeRequested = false
    private var pendingFrontTimeUpdate: PendingFrontTimeUpdate? = null
    private var pendingRemoteTimeUpdate: PendingRemoteTimeUpdate? = null
    private val splitReporter = reporter as? SplitPlaybackProgressReporter

    init {
        scope.launch { drainFront() }
        if (splitReporter != null) {
            scope.launch { drainRemote() }
        }
    }

    fun enqueueStart(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
    ): Deferred<Boolean> =
        CompletableDeferred<Boolean>().also { result ->
            frontIngressLock.withLock {
                if (closeRequested) {
                    result.complete(false)
                } else {
                    pendingFrontTimeUpdate = null
                    if (frontCommands.trySend(Command.Start(reportingSession, positionMs, result)).isFailure) {
                        result.complete(false)
                    }
                }
            }
        }

    fun enqueueProgress(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        frontIngressLock.withLock {
            if (closeRequested) return@withLock
            val command =
                Command.Progress(
                    reportingSession = reportingSession,
                    positionMs = positionMs,
                    isPaused = isPaused,
                    eventName = eventName,
                )
            if (eventName == PlaybackProgressEvent.TimeUpdate) {
                val existing = pendingFrontTimeUpdate
                if (existing?.latest?.reportingSession?.generation == reportingSession.generation) {
                    existing.latest = command
                } else {
                    val pending = PendingFrontTimeUpdate(command)
                    pendingFrontTimeUpdate = pending
                    if (frontCommands.trySend(Command.TimeUpdate(pending)).isFailure) {
                        pendingFrontTimeUpdate = null
                    }
                }
            } else {
                pendingFrontTimeUpdate = null
                frontCommands.trySend(command)
            }
        }
    }

    fun enqueueStop(
        reportingSession: PlaybackReportingSession,
        positionMs: Long,
        completed: Boolean = false,
    ): Deferred<Boolean> =
        CompletableDeferred<Boolean>().also { result ->
            frontIngressLock.withLock {
                if (closeRequested) {
                    result.complete(false)
                } else {
                    pendingFrontTimeUpdate = null
                    if (
                        frontCommands.trySend(Command.Stop(reportingSession, positionMs, completed, result)).isFailure
                    ) {
                        result.complete(false)
                    }
                }
            }
        }

    fun closeAfterDrain() {
        frontIngressLock.withLock {
            if (closeRequested) return@withLock
            closeRequested = true
            pendingFrontTimeUpdate = null
            frontCommands.trySend(Command.Close)
            frontCommands.close()
        }
    }

    private fun consumeFrontTimeUpdate(pending: PendingFrontTimeUpdate): Command.Progress =
        frontIngressLock.withLock {
            if (pendingFrontTimeUpdate === pending) {
                pendingFrontTimeUpdate = null
            }
            pending.latest
        }

    private fun enqueueRemote(command: RemoteCommand) {
        remoteIngressLock.withLock {
            if (command is RemoteCommand.Progress && command.eventName == PlaybackProgressEvent.TimeUpdate) {
                val existing = pendingRemoteTimeUpdate
                if (existing?.latest?.reportingSession?.generation == command.reportingSession.generation) {
                    existing.latest = command
                } else {
                    val pending = PendingRemoteTimeUpdate(command)
                    pendingRemoteTimeUpdate = pending
                    if (remoteCommands.trySend(RemoteCommand.TimeUpdate(pending)).isFailure) {
                        pendingRemoteTimeUpdate = null
                    }
                }
            } else {
                pendingRemoteTimeUpdate = null
                if (remoteCommands.trySend(command).isFailure) {
                    command.completeFailure()
                }
            }
        }
    }

    private fun consumeRemoteTimeUpdate(pending: PendingRemoteTimeUpdate): RemoteCommand.Progress =
        remoteIngressLock.withLock {
            if (pendingRemoteTimeUpdate === pending) {
                pendingRemoteTimeUpdate = null
            }
            pending.latest
        }

    private fun closeRemoteAfterDrain() {
        remoteIngressLock.withLock {
            pendingRemoteTimeUpdate = null
            remoteCommands.trySend(RemoteCommand.Close)
            remoteCommands.close()
        }
    }

    private suspend fun drainFront() {
        val startedGenerations = mutableSetOf<Long>()
        val stoppedGenerations = mutableSetOf<Long>()
        var activeCommand: Command? = null
        try {
            for (command in frontCommands) {
                activeCommand = command
                if (!currentCoroutineContext().isActive) break
                when (command) {
                    is Command.Start ->
                        when {
                            splitReporter == null ->
                                processRemoteStart(
                                    RemoteCommand.Start(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        result = command.result,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                    startedGenerations,
                                    stoppedGenerations,
                                )
                            command.reportingSession.usesSplitOfflineReporter() ->
                                processLocalStart(command, startedGenerations, stoppedGenerations)
                            else ->
                                enqueueRemote(
                                    RemoteCommand.Start(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        result = command.result,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                )
                        }
                    is Command.Progress ->
                        when {
                            splitReporter == null ->
                                processRemoteProgress(
                                    RemoteCommand.Progress(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        isPaused = command.isPaused,
                                        eventName = command.eventName,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                    startedGenerations,
                                    stoppedGenerations,
                                )
                            command.reportingSession.usesSplitOfflineReporter() ->
                                processLocalProgress(command, startedGenerations, stoppedGenerations)
                            else ->
                                enqueueRemote(
                                    RemoteCommand.Progress(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        isPaused = command.isPaused,
                                        eventName = command.eventName,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                )
                        }
                    is Command.Stop ->
                        when {
                            splitReporter == null ->
                                processRemoteStop(
                                    RemoteCommand.Stop(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        completed = command.completed,
                                        result = command.result,
                                        reporter = reporter,
                                        publishSettlementOnAttempt = true,
                                        optionalRemote = false,
                                    ),
                                    startedGenerations,
                                    stoppedGenerations,
                                )
                            command.reportingSession.usesSplitOfflineReporter() ->
                                processLocalStop(command, startedGenerations, stoppedGenerations)
                            else ->
                                enqueueRemote(
                                    RemoteCommand.Stop(
                                        reportingSession = command.reportingSession,
                                        positionMs = command.positionMs,
                                        completed = command.completed,
                                        result = command.result,
                                        reporter = reporter,
                                        publishSettlementOnAttempt = true,
                                        optionalRemote = false,
                                    ),
                                )
                        }
                    is Command.TimeUpdate -> {
                        val progress = consumeFrontTimeUpdate(command.pending)
                        when {
                            splitReporter == null ->
                                processRemoteProgress(
                                    RemoteCommand.Progress(
                                        reportingSession = progress.reportingSession,
                                        positionMs = progress.positionMs,
                                        isPaused = progress.isPaused,
                                        eventName = progress.eventName,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                    startedGenerations,
                                    stoppedGenerations,
                                )
                            progress.reportingSession.usesSplitOfflineReporter() ->
                                processLocalProgress(progress, startedGenerations, stoppedGenerations)
                            else ->
                                enqueueRemote(
                                    RemoteCommand.Progress(
                                        reportingSession = progress.reportingSession,
                                        positionMs = progress.positionMs,
                                        isPaused = progress.isPaused,
                                        eventName = progress.eventName,
                                        reporter = reporter,
                                        optionalRemote = false,
                                    ),
                                )
                        }
                    }
                    Command.Close -> break
                }
                activeCommand = null
            }
        } finally {
            failPendingFrontCommands(activeCommand)
            if (splitReporter == null) {
                remoteIngressLock.withLock {
                    pendingRemoteTimeUpdate = null
                    remoteCommands.close()
                }
                scope.cancel()
            } else {
                closeRemoteAfterDrain()
            }
        }
    }

    private suspend fun processLocalStart(
        command: Command.Start,
        startedGenerations: MutableSet<Long>,
        stoppedGenerations: Set<Long>,
    ) {
        val generation = command.reportingSession.generation
        val localReporter = splitReporter?.localSettlementReporter
        val succeeded =
            when {
                generation in stoppedGenerations -> false
                generation in startedGenerations -> true
                localReporter == null -> false
                else ->
                    reportSafely {
                        localReporter.reportStart(
                            session = command.reportingSession.session,
                            plan = command.reportingSession.plan,
                            playSessionId = command.reportingSession.playSessionId,
                            positionMs = command.positionMs,
                        )
                    }.also { success ->
                        if (success) startedGenerations += generation
                    }
            }
        logReportingResult(command.reportingSession, PlaybackReportingOperation.Start, succeeded)
        command.result.complete(succeeded)
        if (succeeded) {
            splitReporter?.guardedRemoteReporter?.let { remoteReporter ->
                enqueueRemote(
                    RemoteCommand.Start(
                        reportingSession = command.reportingSession,
                        positionMs = command.positionMs,
                        result = null,
                        reporter = remoteReporter,
                        optionalRemote = true,
                    ),
                )
            }
        }
    }

    private suspend fun processLocalProgress(
        command: Command.Progress,
        startedGenerations: Set<Long>,
        stoppedGenerations: Set<Long>,
    ) {
        val generation = command.reportingSession.generation
        val localReporter = splitReporter?.localSettlementReporter
        val succeeded =
            generation in startedGenerations &&
                generation !in stoppedGenerations &&
                localReporter != null &&
                reportSafely {
                    localReporter.reportProgress(
                        session = command.reportingSession.session,
                        plan = command.reportingSession.plan,
                        playSessionId = command.reportingSession.playSessionId,
                        positionMs = command.positionMs,
                        isPaused = command.isPaused,
                        eventName = command.eventName,
                    )
                }
        if (!succeeded) {
            logReportingResult(command.reportingSession, PlaybackReportingOperation.Progress, succeeded = false)
            return
        }
        splitReporter?.guardedRemoteReporter?.let { remoteReporter ->
            enqueueRemote(
                RemoteCommand.Progress(
                    reportingSession = command.reportingSession,
                    positionMs = command.positionMs,
                    isPaused = command.isPaused,
                    eventName = command.eventName,
                    reporter = remoteReporter,
                    optionalRemote = true,
                ),
            )
        }
    }

    private suspend fun processLocalStop(
        command: Command.Stop,
        startedGenerations: Set<Long>,
        stoppedGenerations: MutableSet<Long>,
    ) {
        val generation = command.reportingSession.generation
        val attemptedStop = generation !in stoppedGenerations && generation in startedGenerations
        val localReporter = splitReporter?.localSettlementReporter
        val succeeded =
            when {
                generation in stoppedGenerations -> true
                generation !in startedGenerations -> false
                localReporter == null -> false
                else ->
                    reportSafely {
                        localReporter.reportStopped(
                            session = command.reportingSession.session,
                            plan = command.reportingSession.plan,
                            playSessionId = command.reportingSession.playSessionId,
                            positionMs = command.positionMs,
                            completed = command.completed,
                        )
                    }.also { success ->
                        if (success) stoppedGenerations += generation
                    }
            }
        logReportingResult(command.reportingSession, PlaybackReportingOperation.Stop, succeeded)
        if (attemptedStop && succeeded) {
            publishSettlement(command.reportingSession)
        }
        command.result.complete(succeeded)
        if (succeeded) {
            splitReporter?.guardedRemoteReporter?.let { remoteReporter ->
                enqueueRemote(
                    RemoteCommand.Stop(
                        reportingSession = command.reportingSession,
                        positionMs = command.positionMs,
                        completed = command.completed,
                        result = null,
                        reporter = remoteReporter,
                        publishSettlementOnAttempt = false,
                        optionalRemote = true,
                    ),
                )
            }
        }
    }

    private suspend fun drainRemote() {
        val startedGenerations = mutableSetOf<Long>()
        val stoppedGenerations = mutableSetOf<Long>()
        var activeCommand: RemoteCommand? = null
        try {
            for (command in remoteCommands) {
                activeCommand = command
                if (!currentCoroutineContext().isActive) break
                when (command) {
                    is RemoteCommand.Start -> processRemoteStart(command, startedGenerations, stoppedGenerations)
                    is RemoteCommand.Progress -> processRemoteProgress(command, startedGenerations, stoppedGenerations)
                    is RemoteCommand.Stop -> processRemoteStop(command, startedGenerations, stoppedGenerations)
                    is RemoteCommand.TimeUpdate ->
                        processRemoteProgress(
                            consumeRemoteTimeUpdate(command.pending),
                            startedGenerations,
                            stoppedGenerations,
                        )
                    RemoteCommand.Close -> break
                }
                activeCommand = null
            }
        } finally {
            failPendingRemoteCommands(activeCommand)
            scope.cancel()
        }
    }

    private suspend fun processRemoteStart(
        command: RemoteCommand.Start,
        startedGenerations: MutableSet<Long>,
        stoppedGenerations: Set<Long>,
    ) {
        val generation = command.reportingSession.generation
        val succeeded =
            when {
                generation in stoppedGenerations -> false
                generation in startedGenerations -> true
                else ->
                    reportSafely {
                        command.reporter.reportStart(
                            session = command.reportingSession.session,
                            plan = command.reportingSession.plan,
                            playSessionId = command.reportingSession.playSessionId,
                            positionMs = command.positionMs,
                        )
                    }.also { success ->
                        if (success) startedGenerations += generation
                    }
            }
        if (command.result != null || command.optionalRemote || !succeeded) {
            logReportingResult(
                reportingSession = command.reportingSession,
                operation = PlaybackReportingOperation.Start,
                succeeded = succeeded,
                optionalRemote = command.optionalRemote,
            )
        }
        command.result?.complete(succeeded)
    }

    private suspend fun processRemoteProgress(
        command: RemoteCommand.Progress,
        startedGenerations: Set<Long>,
        stoppedGenerations: Set<Long>,
    ) {
        val generation = command.reportingSession.generation
        val succeeded =
            generation in startedGenerations &&
                generation !in stoppedGenerations &&
                reportSafely {
                    command.reporter.reportProgress(
                        session = command.reportingSession.session,
                        plan = command.reportingSession.plan,
                        playSessionId = command.reportingSession.playSessionId,
                        positionMs = command.positionMs,
                        isPaused = command.isPaused,
                        eventName = command.eventName,
                    )
                }
        if (!succeeded) {
            logReportingResult(
                reportingSession = command.reportingSession,
                operation = PlaybackReportingOperation.Progress,
                succeeded = false,
                optionalRemote = command.optionalRemote,
            )
        }
    }

    private suspend fun processRemoteStop(
        command: RemoteCommand.Stop,
        startedGenerations: Set<Long>,
        stoppedGenerations: MutableSet<Long>,
    ) {
        val generation = command.reportingSession.generation
        val attemptedStop = generation !in stoppedGenerations && generation in startedGenerations
        val succeeded =
            when {
                generation in stoppedGenerations -> true
                generation !in startedGenerations -> false
                else ->
                    reportSafely {
                        command.reporter.reportStopped(
                            session = command.reportingSession.session,
                            plan = command.reportingSession.plan,
                            playSessionId = command.reportingSession.playSessionId,
                            positionMs = command.positionMs,
                            completed = command.completed,
                        )
                    }.also { success ->
                        if (success) stoppedGenerations += generation
                    }
            }
        if (command.result != null || command.optionalRemote || !succeeded) {
            logReportingResult(
                reportingSession = command.reportingSession,
                operation = PlaybackReportingOperation.Stop,
                succeeded = succeeded,
                optionalRemote = command.optionalRemote,
            )
        }
        if (attemptedStop && command.publishSettlementOnAttempt) {
            publishSettlement(command.reportingSession)
        }
        command.result?.complete(succeeded)
    }

    private fun PlaybackReportingSession.usesSplitOfflineReporter(): Boolean =
        splitReporter != null &&
            plan.streamMode == StreamMode.Offline &&
            plan.offlineArtifactRef != null

    private suspend fun publishSettlement(reportingSession: PlaybackReportingSession) {
        settlementRegistry.publish(
            SettlementKey(
                serverId = reportingSession.session.serverId,
                userId = reportingSession.session.userId,
                itemId = reportingSession.plan.itemId,
            ),
        )
    }

    private fun failPendingFrontCommands(activeCommand: Command?) {
        frontIngressLock.withLock {
            activeCommand?.completeFailure()
            closeRequested = true
            pendingFrontTimeUpdate = null
            frontCommands.close()
            while (true) {
                val command = frontCommands.tryReceive().getOrNull() ?: break
                command.completeFailure()
            }
        }
    }

    private fun failPendingRemoteCommands(activeCommand: RemoteCommand?) {
        remoteIngressLock.withLock {
            activeCommand?.completeFailure()
            pendingRemoteTimeUpdate = null
            remoteCommands.close()
            while (true) {
                val command = remoteCommands.tryReceive().getOrNull() ?: break
                command.completeFailure()
            }
        }
    }

    private suspend fun reportSafely(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) throw exception
            false
        } catch (_: Throwable) {
            false
        }

    private fun logReportingResult(
        reportingSession: PlaybackReportingSession,
        operation: PlaybackReportingOperation,
        succeeded: Boolean,
        optionalRemote: Boolean = false,
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
            logger.i { diagnosticLine.withReportingRoute(optionalRemote) }
        } else {
            logger.w { diagnosticLine.withReportingRoute(optionalRemote) }
        }
    }

    private fun String.withReportingRoute(optionalRemote: Boolean): String {
        if (!optionalRemote) {
            return this
        }
        return "$this operation=optionalRemoteReporting"
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

        data class TimeUpdate(
            val pending: PendingFrontTimeUpdate,
        ) : Command

        data class Stop(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val completed: Boolean,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        fun completeFailure() {
            when (this) {
                is Start -> result.complete(false)
                is Progress, is TimeUpdate, Close -> Unit
                is Stop -> result.complete(false)
            }
        }

        data object Close : Command
    }

    private sealed interface RemoteCommand {
        data class Start(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val result: CompletableDeferred<Boolean>?,
            val reporter: PlaybackProgressReporter,
            val optionalRemote: Boolean,
        ) : RemoteCommand

        data class Progress(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val isPaused: Boolean,
            val eventName: PlaybackProgressEvent,
            val reporter: PlaybackProgressReporter,
            val optionalRemote: Boolean,
        ) : RemoteCommand

        data class TimeUpdate(
            val pending: PendingRemoteTimeUpdate,
        ) : RemoteCommand

        data class Stop(
            val reportingSession: PlaybackReportingSession,
            val positionMs: Long,
            val completed: Boolean,
            val result: CompletableDeferred<Boolean>?,
            val reporter: PlaybackProgressReporter,
            val publishSettlementOnAttempt: Boolean,
            val optionalRemote: Boolean,
        ) : RemoteCommand

        data object Close : RemoteCommand

        fun completeFailure() {
            when (this) {
                is Start -> result?.complete(false)
                is Progress, is TimeUpdate, Close -> Unit
                is Stop -> result?.complete(false)
            }
        }
    }

    private class PendingFrontTimeUpdate(
        var latest: Command.Progress,
    )

    private class PendingRemoteTimeUpdate(
        var latest: RemoteCommand.Progress,
    )
}
