// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App-surface choice; controllers report measurements but never select this. */
enum class PlaybackHealthGuidancePolicy {
    Disabled,
    Advisory,
    Actionable,
}

enum class PlaybackHealthGuidanceReason {
    SlowStartup,
    LongBuffering,
    CumulativeBuffering,
    RepeatedStalls,
    DroppedFrames,
    NoVideoOutput,
    RecoveredPlaybackFailure,
}

/**
 * Sanitized, shared user-guidance contract. The session token is opaque and
 * generation-scoped; it deliberately carries no server, user, item, URL, or
 * native-error identity.
 */
data class PlaybackHealthGuidance(
    val sessionToken: Long,
    val reason: PlaybackHealthGuidanceReason,
    val streamMode: StreamMode? = null,
    val durationMs: Long? = null,
    val count: Int? = null,
    val nextLowerQualityRungBps: Long? = null,
    val canReduceQuality: Boolean = false,
    val canOpenPlaybackSettings: Boolean = false,
    val canDismiss: Boolean = true,
    val availableActions: Set<PlaybackAction> = setOf(PlaybackAction.Dismiss),
)

data class PlaybackHealthSessionContext(
    val sessionToken: Long,
    val streamMode: StreamMode?,
    val guidancePolicy: PlaybackHealthGuidancePolicy,
    val nextLowerQualityRungBps: Long? = null,
    val isOffline: Boolean = false,
    val videoExpected: Boolean = false,
    val backend: PlayerBackend = PlayerBackend.Auto,
)

/**
 * Lifecycle and scheduling wrapper around [PlaybackHealthEvaluator]. It owns
 * only bounded evaluator mechanics: generations, two deadline jobs, exclusions,
 * and the separate startup/post-start guidance slots. Player verbs, UI
 * navigation, persistence, and wording stay with the caller.
 */
class PlaybackHealthSessionCoordinator(
    private val scope: CoroutineScope,
    private val monotonicTimeMs: () -> Long,
    private val measurementCapabilities: () -> PlaybackHealthMeasurementCapabilities,
    private val onSignal: (PlaybackHealthSignal) -> Unit = {},
    private val onGuidanceChanged: (PlaybackHealthGuidance?) -> Unit = {},
    private val onSessionEnded: (PlaybackHealthSummary) -> Unit = {},
    private val evaluator: PlaybackHealthEvaluator = PlaybackHealthEvaluator(),
) {
    private var context: PlaybackHealthSessionContext? = null
    private var activeGeneration = 0L
    private var active = false
    private var startupGuidanceShown = false
    private var postStartGuidanceShown = false
    private var currentGuidance: PlaybackHealthGuidance? = null
    private var startupJob: Job? = null
    private var bufferingJob: Job? = null
    private var noVideoOutputJob: Job? = null
    private var activeVideoOutputGeneration: Long? = null
    private var videoOutputWaitStartedAtMs: Long? = null
    private var videoOutputWaitPositionMs: Long? = null
    private var latestPlaybackPositionMs = 0L
    private var firstVideoOutputObserved = false
    private var noVideoOutputGuidanceShown = false
    private var suppressedUntilMs = 0L
    private var activeTransitionIdentity: PlaybackTransitionIdentity? = null
    private var lastTransitionSequence = -1L
    private var transitionSettled = false

    val guidance: PlaybackHealthGuidance?
        get() = currentGuidance

    fun start(
        generation: Long,
        context: PlaybackHealthSessionContext,
        startedAtMs: Long = monotonicTimeMs(),
    ) {
        if (active) {
            end()
        }
        cancelTimers()
        activeGeneration = generation
        this.context = context
        active = true
        startupGuidanceShown = false
        postStartGuidanceShown = false
        activeVideoOutputGeneration = null
        videoOutputWaitStartedAtMs = null
        videoOutputWaitPositionMs = null
        latestPlaybackPositionMs = 0L
        firstVideoOutputObserved = false
        noVideoOutputGuidanceShown = false
        suppressedUntilMs = startedAtMs
        activeTransitionIdentity = null
        lastTransitionSequence = -1L
        transitionSettled = false
        setGuidance(null)
        evaluator.reset(generation = generation, launchAtMs = startedAtMs)
    }

    fun updateContext(context: PlaybackHealthSessionContext) {
        if (!active) return
        this.context = context
    }

    fun observePlaybackState(playbackState: PlaybackState) {
        if (!active) return
        latestPlaybackPositionMs = playbackState.positionMs.coerceAtLeast(0L)
        val signals = evaluator.onStatusChanged(playbackState.status, monotonicTimeMs())
        handleSignals(signals)
        syncTimers()
    }

    fun observeVideoOutput(observation: VideoOutputObservation) {
        if (!active || observation.generation != activeVideoOutputGeneration || !observation.presented) return
        if (observation.observedAtMs < 0L) return
        firstVideoOutputObserved = true
        videoOutputWaitStartedAtMs = null
        noVideoOutputJob?.cancel()
        noVideoOutputJob = null
    }

    fun observePlaybackTransition(observation: PlaybackTransitionObservation) {
        if (
            !active ||
            observation.prepareEpoch != activeVideoOutputGeneration ||
            observation.transitionSequence < 0L ||
            observation.observedAtMs < 0L
        ) {
            return
        }
        val identity =
            PlaybackTransitionIdentity(
                prepareEpoch = observation.prepareEpoch,
                transitionSequence = observation.transitionSequence,
            )
        when (observation.outcome) {
            PlaybackTransitionOutcome.Started -> {
                if (observation.transitionSequence <= lastTransitionSequence) return
                lastTransitionSequence = observation.transitionSequence
                activeTransitionIdentity = identity
                transitionSettled = false
            }
            PlaybackTransitionOutcome.Presented -> {
                if (identity != activeTransitionIdentity || transitionSettled) return
                transitionSettled = true
            }
            PlaybackTransitionOutcome.TimedOut -> {
                if (
                    identity != activeTransitionIdentity ||
                    transitionSettled ||
                    context?.videoExpected != true ||
                    !measurementCapabilities().hasReliableFirstVideoOutput
                ) {
                    return
                }
                transitionSettled = true
                noVideoOutputGuidanceShown = true
                handleSignals(
                    listOf(
                        PlaybackHealthSignal.NoVideoOutput(
                            observedAtMs = observation.observedAtMs,
                            thresholdMs =
                                playbackHealthNoVideoOutputThresholdMs(
                                    context?.backend ?: PlayerBackend.Auto,
                                ),
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Arms first-video-output measurement for the controller's current prepare
     * epoch. The prepare epoch is deliberately separate from the item-session
     * generation: a position-preserving replan keeps Auto recovery budgets but
     * must reject a late frame from the superseded native prepare.
     */
    fun expectVideoOutput(generation: Long) {
        if (!active || generation < 0L) return
        activeVideoOutputGeneration = generation
        // Prepare epochs are controller-local. A replacement controller may
        // legitimately restart its epoch and transition counters at values the
        // outgoing controller already used, so every explicit prepare admission
        // resets the local transition watermark.
        lastTransitionSequence = -1L
        activeTransitionIdentity = null
        transitionSettled = false
        videoOutputWaitStartedAtMs = null
        videoOutputWaitPositionMs = null
        firstVideoOutputObserved = false
        noVideoOutputJob?.cancel()
        noVideoOutputJob = null
        syncTimers()
    }

    fun recordDroppedFrameMeasurement(measurement: DroppedFrameMeasurement) {
        if (!active || !measurementCapabilities().hasDroppedFrameMeasurements) return
        handleSignals(evaluator.recordDroppedFrameMeasurement(measurement, monotonicTimeMs()))
    }

    fun markExclusion(reason: PlaybackHealthExclusionReason) {
        if (!active) return
        evaluator.markExclusion(monotonicTimeMs(), reason)
        suppressedUntilMs = monotonicTimeMs() + PLAYBACK_HEALTH_EXCLUSION_MS
        videoOutputWaitStartedAtMs = null
        videoOutputWaitPositionMs = null
        noVideoOutputJob?.cancel()
        noVideoOutputJob = null
        syncTimers()
    }

    /**
     * Restarts only the current evidence window. The exclusion and timer
     * suppression remain the same as [markExclusion], while the evaluator
     * preserves session timing, status, startup state, and emitted latches.
     */
    fun restartEvidenceWindow(reason: PlaybackHealthExclusionReason = PlaybackHealthExclusionReason.Resume) {
        if (!active) return
        val now = monotonicTimeMs()
        evaluator.markExclusion(now, reason)
        evaluator.restartEvidenceWindow()
        suppressedUntilMs = now + PLAYBACK_HEALTH_EXCLUSION_MS
        videoOutputWaitStartedAtMs = null
        videoOutputWaitPositionMs = null
        noVideoOutputJob?.cancel()
        noVideoOutputJob = null
        syncTimers()
    }

    /** Records a recovered native fallback through the same post-start slot. */
    fun recordRecoveredPlaybackFailure() {
        val sessionContext = context ?: return
        if (!active || postStartGuidanceShown || !sessionContext.canPublishGuidance()) return
        postStartGuidanceShown = true
        setGuidance(sessionContext.guidanceFor(PlaybackHealthGuidanceReason.RecoveredPlaybackFailure))
    }

    fun dismissGuidance() {
        setGuidance(null)
    }

    fun end() {
        if (!active) return
        val summary =
            evaluator.summary(monotonicTimeMs()).copy(
                firstVideoOutputMeasurementAvailable = measurementCapabilities().hasReliableFirstVideoOutput,
                firstVideoOutputObserved = firstVideoOutputObserved,
                postStartGuidanceShown = postStartGuidanceShown,
                noVideoOutputGuidanceShown = noVideoOutputGuidanceShown,
                guidancePolicy = context?.guidancePolicy ?: PlaybackHealthGuidancePolicy.Disabled,
                guidancePublishable = context?.canPublishGuidance() == true,
            )
        active = false
        cancelTimers()
        onSessionEnded(summary)
        setGuidance(null)
        context = null
    }

    fun close() = end()

    private fun handleSignals(signals: List<PlaybackHealthSignal>) {
        signals.forEach { signal ->
            val sessionContext = context ?: return@forEach
            if (!isMeasurementAvailable(signal)) return@forEach
            onSignal(signal)
            if (!sessionContext.canPublishGuidance()) return@forEach
            if (signal.kind == PlaybackHealthSignalKind.SlowStartup) {
                if (startupGuidanceShown) return@forEach
                startupGuidanceShown = true
                setGuidance(sessionContext.guidanceFor(signal.guidanceReason(), signal))
                return@forEach
            }
            if (postStartGuidanceShown) return@forEach
            postStartGuidanceShown = true
            setGuidance(sessionContext.guidanceFor(signal.guidanceReason(), signal))
        }
    }

    private fun isMeasurementAvailable(signal: PlaybackHealthSignal): Boolean =
        when (signal.kind) {
            PlaybackHealthSignalKind.SlowStartup -> true
            PlaybackHealthSignalKind.DroppedFrames -> measurementCapabilities().hasDroppedFrameMeasurements
            PlaybackHealthSignalKind.NoVideoOutput -> measurementCapabilities().hasReliableFirstVideoOutput
            PlaybackHealthSignalKind.LongBuffering,
            PlaybackHealthSignalKind.CumulativeBuffering,
            PlaybackHealthSignalKind.RepeatedStalls,
            -> measurementCapabilities().hasReliableBufferingTransitions
        }

    private fun syncTimers() {
        cancelTimers()
        if (!active) return
        if (evaluator.currentStatus != PlaybackStatus.Playing) {
            videoOutputWaitStartedAtMs = null
            videoOutputWaitPositionMs = null
        }
        val generation = activeGeneration
        if (
            !evaluator.hasStartedPlaying &&
            !evaluator.hasEmitted(PlaybackHealthSignalKind.SlowStartup) &&
            evaluator.currentStatus in setOf(PlaybackStatus.Loading, PlaybackStatus.Buffering)
        ) {
            val elapsed = monotonicTimeMs() - evaluator.startedAtMs
            val remaining = (PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS - elapsed).coerceAtLeast(1L)
            startupJob =
                scope.launch {
                    delay(remaining)
                    if (!isCurrentGeneration(generation)) return@launch
                    handleSignals(evaluator.evaluateAt(monotonicTimeMs()))
                    syncTimers()
                }
        }
        if (evaluator.hasStartedPlaying && evaluator.currentStatus == PlaybackStatus.Buffering) {
            evaluator.nextBufferingEvaluationDelayMs(monotonicTimeMs())?.let { delayMs ->
                bufferingJob =
                    scope.launch {
                        delay(delayMs.coerceAtLeast(1L))
                        if (!isCurrentGeneration(generation)) return@launch
                        handleSignals(evaluator.evaluateAt(monotonicTimeMs()))
                        syncTimers()
                    }
            }
        }
        if (
            context?.videoExpected == true &&
            measurementCapabilities().hasReliableFirstVideoOutput &&
            activeVideoOutputGeneration != null &&
            !firstVideoOutputObserved &&
            !noVideoOutputGuidanceShown &&
            evaluator.currentStatus == PlaybackStatus.Playing
        ) {
            val now = monotonicTimeMs()
            val waitStartedAtMs = videoOutputWaitStartedAtMs ?: now.also { videoOutputWaitStartedAtMs = it }
            val waitPositionMs =
                videoOutputWaitPositionMs
                    ?: latestPlaybackPositionMs.also { videoOutputWaitPositionMs = it }
            val thresholdMs =
                playbackHealthNoVideoOutputThresholdMs(
                    context?.backend ?: PlayerBackend.Auto,
                )
            val elapsed = now - waitStartedAtMs
            val playbackClockAdvanced = latestPlaybackPositionMs > waitPositionMs
            if (elapsed < thresholdMs || playbackClockAdvanced) {
                val remaining =
                    (thresholdMs - elapsed).coerceAtLeast(0L) +
                        (suppressedUntilMs - now).coerceAtLeast(0L)
                noVideoOutputJob =
                    scope.launch {
                        delay(remaining)
                        if (
                            !isCurrentGeneration(generation) ||
                            firstVideoOutputObserved ||
                            !measurementCapabilities().hasReliableFirstVideoOutput
                        ) {
                            return@launch
                        }
                        val firedAtMs = monotonicTimeMs()
                        if (
                            firedAtMs - waitStartedAtMs < thresholdMs ||
                            latestPlaybackPositionMs <= waitPositionMs
                        ) {
                            // A stationary playback clock is slow startup, not
                            // black video. Wait for the next state observation to
                            // report movement instead of polling in a tight loop.
                            noVideoOutputJob = null
                            return@launch
                        }
                        noVideoOutputGuidanceShown = true
                        handleSignals(
                            listOf(
                                PlaybackHealthSignal.NoVideoOutput(
                                    observedAtMs = firedAtMs,
                                    thresholdMs = thresholdMs,
                                ),
                            ),
                        )
                        syncTimers()
                    }
            }
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        active && generation == activeGeneration && generation == evaluator.generation

    private fun cancelTimers() {
        startupJob?.cancel()
        startupJob = null
        bufferingJob?.cancel()
        bufferingJob = null
        noVideoOutputJob?.cancel()
        noVideoOutputJob = null
    }

    private fun setGuidance(guidance: PlaybackHealthGuidance?) {
        if (currentGuidance == guidance) return
        currentGuidance = guidance
        onGuidanceChanged(guidance)
    }
}

private data class PlaybackTransitionIdentity(
    val prepareEpoch: Long,
    val transitionSequence: Long,
)

private fun PlaybackHealthSessionContext.canPublishGuidance(): Boolean =
    !isOffline && guidancePolicy != PlaybackHealthGuidancePolicy.Disabled

private fun PlaybackHealthSessionContext.guidanceFor(
    reason: PlaybackHealthGuidanceReason,
    signal: PlaybackHealthSignal? = null,
): PlaybackHealthGuidance {
    val actionablePostStart =
        guidancePolicy == PlaybackHealthGuidancePolicy.Actionable && reason != PlaybackHealthGuidanceReason.SlowStartup
    val nextLower = nextLowerQualityRungBps.takeIf { actionablePostStart }
    return PlaybackHealthGuidance(
        sessionToken = sessionToken,
        reason = reason,
        streamMode = streamMode,
        durationMs = signal?.durationMs,
        count = signal?.count,
        nextLowerQualityRungBps = nextLower,
        canReduceQuality = nextLower != null,
        canOpenPlaybackSettings = actionablePostStart,
        availableActions =
            if (actionablePostStart) {
                setOf(PlaybackAction.OpenPlaybackSettings, PlaybackAction.Dismiss)
            } else {
                setOf(PlaybackAction.Dismiss)
            },
    )
}

private fun PlaybackHealthSignal.guidanceReason(): PlaybackHealthGuidanceReason =
    when (kind) {
        PlaybackHealthSignalKind.SlowStartup -> PlaybackHealthGuidanceReason.SlowStartup
        PlaybackHealthSignalKind.LongBuffering -> PlaybackHealthGuidanceReason.LongBuffering
        PlaybackHealthSignalKind.CumulativeBuffering -> PlaybackHealthGuidanceReason.CumulativeBuffering
        PlaybackHealthSignalKind.RepeatedStalls -> PlaybackHealthGuidanceReason.RepeatedStalls
        PlaybackHealthSignalKind.DroppedFrames -> PlaybackHealthGuidanceReason.DroppedFrames
        PlaybackHealthSignalKind.NoVideoOutput -> PlaybackHealthGuidanceReason.NoVideoOutput
    }
