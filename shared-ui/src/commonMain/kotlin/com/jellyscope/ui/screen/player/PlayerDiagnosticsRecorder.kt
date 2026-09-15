// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryPromptReason
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryResult
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryState
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryTrigger
import com.jellyscope.core.domain.playback.PlaybackBackendAvailability
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionResult
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import com.jellyscope.core.domain.playback.PlaybackBackendFallbackResult
import com.jellyscope.core.domain.playback.PlaybackChangeResult
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackActivation
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackState
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthSignal
import com.jellyscope.core.domain.playback.PlaybackHealthSummary
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.PlaybackPersistenceResult
import com.jellyscope.core.domain.playback.PlaybackPersistenceTarget
import com.jellyscope.core.domain.playback.PlaybackPlanningException
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.SoftwarePlaybackProgressEvidence
import com.jellyscope.core.domain.playback.SoftwarePlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleRenderInfo
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import com.jellyscope.core.domain.playback.diagnosticClass
import com.jellyscope.core.domain.playback.diagnosticName
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.thresholdClass

internal data class PlayerDiagnosticContext(
    val backend: PlayerBackend,
    val sessionSequence: Long,
    val prepareSequence: Long?,
    val streamMode: StreamMode?,
    val qualityCapOrigin: PlaybackQualityCapOrigin?,
    val qualityPolicyMode: PlaybackQualityMode?,
    val qualityPolicyOrigin: PlaybackQualityPolicyOrigin?,
    val requestCapBitrateBps: Long?,
    val effectiveTranscodeCapBitrateBps: Long?,
    val sourceBitrateBps: Long?,
    val runtimeDiagnostics: PlaybackRuntimeDiagnostics,
)

internal data class PlayerTrackDiagnosticFacts(
    val requestedAudioStreamIndex: Int?,
    val confirmedAudioStreamIndex: Int?,
    val audioActivation: AudioActivationState,
    val subtitleSelection: SubtitleSelectionIntent,
    val requestedLocalSubtitleAssetPresent: Boolean,
    val requestedSubtitleStreamIndex: Int?,
    val confirmedSubtitleStreamIndex: Int?,
    val subtitleActivation: SubtitleActivationState,
    val subtitleRenderInfo: SubtitleRenderInfo,
    val subtitleStyleable: Boolean,
)

internal data class PlayerTerminalDiagnosticFacts(
    val outcome: PlaybackTerminalOutcome,
    val error: PlaybackError?,
    val autoRecoveryTrigger: AutoPlaybackRecoveryTrigger?,
    val recoveryDecision: PlaybackRecoveryDecision?,
)

internal data class PlayerDiagnosticFacts(
    val stage: PlaybackDiagnosticStage,
    val event: PlaybackDiagnosticEvent,
    val exception: Throwable? = null,
    val streamMode: StreamMode? = null,
    val requestPolicy: PlaybackInfoRequestPolicy? = null,
    val trackKind: PlaybackDiagnosticTrackKind? = null,
)

internal data class PlayerPlannerFailureDiagnosticFacts(
    val exception: Throwable,
    val requestPolicy: PlaybackInfoRequestPolicy,
    val qualityPolicy: PlaybackQualityPolicy,
    val qualityCapOrigin: PlaybackQualityCapOrigin?,
    val requestCapBitrateBps: Long?,
)

internal data class PlayerPlaybackChangeDiagnosticFacts(
    val operation: PlayerPlaybackChangeOperation,
    val error: PlaybackError,
)

internal data class PlayerPersistenceDiagnosticFacts(
    val event: PlaybackDiagnosticEvent,
    val target: PlaybackPersistenceTarget,
    val result: PlaybackPersistenceResult,
    val exception: Throwable? = null,
    val policyOrigin: PlaybackQualityPolicyOrigin? = null,
)

internal data class PlayerBackendSelectionDiagnosticFacts(
    val requestedBackend: PlayerBackend,
    val activeBackend: PlayerBackend,
    val fallbackResult: PlaybackBackendFallbackResult,
)

internal data class PlayerOfflineBackendConstructionDiagnosticFacts(
    val requiredBackend: PlayerBackend,
    val availability: PlaybackBackendAvailability,
    val exception: Throwable?,
)

internal data class PlayerAutomaticRecoveryDiagnosticFacts(
    val trigger: AutoPlaybackRecoveryTrigger,
    val previousState: AutoPlaybackRecoveryState,
    val result: AutoPlaybackRecoveryResult,
)

internal data class PlayerFirstVideoOutputDiagnosticFacts(
    val state: PlaybackFirstVideoOutputState,
    val observed: Boolean,
    val evidence: VideoOutputEvidence,
)

internal data class PlayerPlaybackHealthSummaryDiagnosticFacts(
    val summary: PlaybackHealthSummary,
    val firstVideoOutputEvidence: VideoOutputEvidence,
    val launchToFirstFrameMs: Long?,
)

internal class PlayerDiagnosticsRecorder {
    private var lastAudioTrackDiagnostic: PlayerTrackDiagnosticSnapshot? = null
    private var lastSubtitleTrackDiagnostic: PlayerTrackDiagnosticSnapshot? = null
    private var lastTerminalDiagnostic: PlayerTerminalDiagnosticSnapshot? = null

    fun recordTrackStates(
        context: PlayerDiagnosticContext,
        facts: PlayerTrackDiagnosticFacts,
    ) {
        val audioSnapshot =
            PlayerTrackDiagnosticSnapshot(
                kind = PlaybackDiagnosticTrackKind.Audio,
                requestedState = facts.requestedAudioStreamIndex.toDiagnosticTrackState(),
                confirmedState = facts.confirmedAudioStreamIndex.toDiagnosticTrackState(),
                matchesRequest = facts.requestedAudioStreamIndex == facts.confirmedAudioStreamIndex,
                activation = facts.audioActivation.toDiagnosticTrackActivation(),
            )
        recordTrackStateIfChanged(context, audioSnapshot)

        val requestedSubtitle =
            when (facts.subtitleSelection) {
                SubtitleSelectionIntent.Off,
                SubtitleSelectionIntent.Unspecified,
                -> facts.requestedLocalSubtitleAssetPresent

                is SubtitleSelectionIntent.Track,
                is SubtitleSelectionIntent.LocalAsset,
                -> true
            }
        val confirmedSubtitleSelected =
            facts.confirmedSubtitleStreamIndex != null ||
                facts.subtitleRenderInfo.status == com.jellyscope.core.domain.playback.SubtitleRenderStatus.Active
        val subtitleMatches =
            when {
                !requestedSubtitle && !confirmedSubtitleSelected -> true
                !requestedSubtitle || !confirmedSubtitleSelected -> false
                facts.requestedSubtitleStreamIndex != null && facts.confirmedSubtitleStreamIndex != null ->
                    facts.requestedSubtitleStreamIndex == facts.confirmedSubtitleStreamIndex

                else -> facts.subtitleRenderInfo.status == com.jellyscope.core.domain.playback.SubtitleRenderStatus.Active
            }
        val subtitleSnapshot =
            PlayerTrackDiagnosticSnapshot(
                kind = PlaybackDiagnosticTrackKind.Subtitle,
                requestedState = requestedSubtitle.toDiagnosticTrackState(),
                confirmedState = confirmedSubtitleSelected.toDiagnosticTrackState(),
                matchesRequest = subtitleMatches,
                activation = facts.subtitleActivation.toDiagnosticTrackActivation(),
                subtitleRenderMode = facts.subtitleRenderInfo.mode,
                subtitleRenderStatus = facts.subtitleRenderInfo.status,
                subtitleStyleable = facts.subtitleStyleable,
            )
        recordTrackStateIfChanged(context, subtitleSnapshot)
    }

    fun recordTerminalOutcome(
        context: PlayerDiagnosticContext,
        facts: PlayerTerminalDiagnosticFacts,
    ) {
        val snapshot =
            PlayerTerminalDiagnosticSnapshot(
                sessionSequence = context.sessionSequence,
                prepareSequence = context.prepareSequence,
                outcome = facts.outcome,
                errorName = facts.error?.diagnosticName(),
                autoRecoveryTrigger = facts.autoRecoveryTrigger,
                recoveryDecision = facts.recoveryDecision,
            )
        if (lastTerminalDiagnostic == snapshot) return
        lastTerminalDiagnostic = snapshot
        val diagnostics = context.runtimeDiagnostics
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.TerminalError,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    prepareSequence = context.prepareSequence,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    errorCategory = facts.error,
                    terminalOutcome = facts.outcome,
                    allocatedBufferBytes = diagnostics.allocatedBufferBytes,
                    bufferedAheadMs = diagnostics.bufferedAheadMs,
                    libVlcCachePercent = diagnostics.libVlcCachePercent,
                    videoDecoderName = diagnostics.videoDecoderName,
                    videoDecodingMode = diagnostics.videoDecodingMode,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    runtimeVideoFrameRate = diagnostics.videoFrameRate,
                ),
            )
        }
    }

    fun recordDiagnostic(
        context: PlayerDiagnosticContext,
        facts: PlayerDiagnosticFacts,
    ) {
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = facts.stage,
                    event = facts.event,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    exceptionType = facts.exception?.playbackExceptionType(),
                    streamMode = facts.streamMode,
                    requestPolicy = facts.requestPolicy?.diagnosticClass(),
                    clientTrigger = facts.requestPolicy?.clientTrigger,
                    qualityCapOrigin = context.qualityCapOrigin,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                    trackKind = facts.trackKind,
                ),
            )
        }
    }

    fun recordPlannerAttemptFailure(
        context: PlayerDiagnosticContext,
        facts: PlayerPlannerFailureDiagnosticFacts,
    ) {
        val attributedFailure = facts.exception as? PlaybackPlanningException.SourceVideoCopyUnsupported
        val attemptedRequestPolicy = attributedFailure?.attemptedRequestPolicy ?: facts.requestPolicy
        val attemptedQualityPolicy = attributedFailure?.attemptedQualityPolicy ?: facts.qualityPolicy
        val attemptedQualityCapOrigin = attributedFailure?.attemptedQualityCapOrigin ?: facts.qualityCapOrigin
        val attemptedRequestCap = attributedFailure?.attemptedMaxStreamingBitrate ?: facts.requestCapBitrateBps
        val diagnosticException = attributedFailure?.cause ?: facts.exception
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Planner,
                    event = PlaybackDiagnosticEvent.Failed,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = attemptedRequestPolicy.backend,
                    sessionSequence = attemptedRequestPolicy.diagnosticSessionSequence,
                    exceptionType = diagnosticException.playbackExceptionType(),
                    requestPolicy = attemptedRequestPolicy.diagnosticClass(),
                    clientTrigger = attemptedRequestPolicy.clientTrigger,
                    qualityCapOrigin = attemptedQualityCapOrigin,
                    qualityPolicyMode = attemptedQualityPolicy.mode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = attemptedRequestCap,
                    recoveryIntent = attemptedRequestPolicy.recoveryIntent,
                ),
            )
        }
    }

    fun recordPlaybackChangeRejected(
        context: PlayerDiagnosticContext,
        facts: PlayerPlaybackChangeDiagnosticFacts,
    ) {
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Planner,
                    event = PlaybackDiagnosticEvent.Rejected,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    errorCategory = facts.error,
                    operation =
                        when (facts.operation) {
                            PlayerPlaybackChangeOperation.Quality -> PlayerOperation.QualityChange
                            PlayerPlaybackChangeOperation.Backend -> PlayerOperation.BackendSwitch
                        },
                    playbackChangeResult = PlaybackChangeResult.CurrentPlaybackKept,
                    qualityCapOrigin = context.qualityCapOrigin,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                ),
            )
        }
    }

    fun recordPersistence(
        context: PlayerDiagnosticContext,
        facts: PlayerPersistenceDiagnosticFacts,
    ) {
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Persistence,
                    event = facts.event,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    exceptionType = facts.exception?.playbackExceptionType(),
                    qualityPolicyOrigin = facts.policyOrigin,
                    persistenceTarget = facts.target,
                    persistenceResult = facts.result,
                ),
            )
        }
    }

    fun recordPrepareRequested(context: PlayerDiagnosticContext) {
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.PrepareRequested,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                ),
            )
        }
    }

    fun recordPrepareDispatched(context: PlayerDiagnosticContext) {
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.PrepareDispatched,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    prepareSequence = context.prepareSequence,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                ),
            )
        }
    }

    fun recordBackendSelection(
        context: PlayerDiagnosticContext,
        facts: PlayerBackendSelectionDiagnosticFacts,
    ) {
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.BackendSelection,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = facts.activeBackend,
                    requestedBackend = facts.requestedBackend,
                    backendFallbackResult = facts.fallbackResult,
                    sessionSequence = context.sessionSequence,
                ),
            )
        }
    }

    fun recordOfflineBackendConstructionFailure(
        context: PlayerDiagnosticContext,
        facts: PlayerOfflineBackendConstructionDiagnosticFacts,
    ) {
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.BackendConstruction,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = facts.requiredBackend,
                    requestedBackend = facts.requiredBackend,
                    backendAvailability = facts.availability,
                    backendConstructionStage = PlaybackBackendConstructionStage.CreateController,
                    backendConstructionResult = PlaybackBackendConstructionResult.Failed,
                    exceptionType = facts.exception?.playbackExceptionType(),
                    streamMode = StreamMode.Offline,
                    sessionSequence = context.sessionSequence,
                ),
            )
        }
    }

    fun recordAutomaticRecoveryDecision(
        context: PlayerDiagnosticContext,
        facts: PlayerAutomaticRecoveryDiagnosticFacts,
    ) {
        val decision = facts.result.decision
        val decisionType =
            when (decision) {
                AutoPlaybackRecoveryDecision.NoAction -> PlaybackRecoveryDecision.NoAction
                is AutoPlaybackRecoveryDecision.CompatibilityReplan -> PlaybackRecoveryDecision.CompatibilityReplan
                is AutoPlaybackRecoveryDecision.LowerTo -> PlaybackRecoveryDecision.LowerQuality
                is AutoPlaybackRecoveryDecision.PromptUser -> PlaybackRecoveryDecision.PromptUser
            }
        val budgetExhausted =
            decision is AutoPlaybackRecoveryDecision.PromptUser &&
                decision.reason in
                setOf(
                    AutoPlaybackRecoveryPromptReason.CompatibilityRecoveryExhausted,
                    AutoPlaybackRecoveryPromptReason.QualityRecoveryExhausted,
                    AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
                )
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.RecoveryDecision,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityCapOrigin = context.qualityCapOrigin,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                    autoRecoveryTrigger = facts.trigger,
                    recoveryDecision = decisionType,
                    recoveryPromptReason = (decision as? AutoPlaybackRecoveryDecision.PromptUser)?.reason,
                    recoveryCompatibilityAttempted = facts.result.state.compatibilityAttempted,
                    recoveryQualityAttempted = facts.result.state.qualityAttempted,
                    recoveryBudgetExhausted = budgetExhausted,
                    recoveryFromQualityBudgetBps =
                        facts.previousState.runtimeQualityCapBps ?: context.requestCapBitrateBps,
                    recoveryToQualityBudgetBps =
                        (decision as? AutoPlaybackRecoveryDecision.LowerTo)?.maxBitrateBps,
                ),
            )
        }
    }

    fun recordFirstVideoOutput(
        context: PlayerDiagnosticContext,
        facts: PlayerFirstVideoOutputDiagnosticFacts,
    ) {
        val diagnostics = context.runtimeDiagnostics
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.VideoOutput,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    prepareSequence = context.prepareSequence,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    sourceBitrateBps = context.sourceBitrateBps,
                    videoDecoderName = diagnostics.videoDecoderName,
                    videoDecodingMode = diagnostics.videoDecodingMode,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    runtimeVideoFrameRate = diagnostics.videoFrameRate,
                    bandwidthEstimateBps = diagnostics.bandwidthEstimateBps,
                    recentVideoRenderP95Ms = diagnostics.recentVideoRenderP95Ms,
                    recentPresentedFrameRate = diagnostics.recentPresentedFrameRate,
                    presentationGapCount = diagnostics.presentationGapCount,
                    firstVideoOutputAvailable = facts.state != PlaybackFirstVideoOutputState.Unsupported,
                    firstVideoOutputObserved = facts.observed,
                    firstVideoOutputEvidence = facts.evidence,
                ),
            )
        }
    }

    fun recordSoftwarePlaybackRecovery(
        context: PlayerDiagnosticContext,
        decision: SoftwarePlaybackRecoveryDecision,
    ) {
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.SoftwareRecovery,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    prepareSequence = context.runtimeDiagnostics.prepareEpoch,
                    streamMode = context.streamMode,
                    qualityPolicyMode = context.qualityPolicyMode,
                    softwareRecoveryDecision = decision,
                ),
            )
        }
    }

    fun recordSoftwarePlaybackProgress(
        context: PlayerDiagnosticContext,
        evidence: SoftwarePlaybackProgressEvidence,
    ) {
        val diagnostics = context.runtimeDiagnostics
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.SoftwareProgress,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    prepareSequence = diagnostics.prepareEpoch,
                    streamMode = context.streamMode,
                    qualityPolicyMode = context.qualityPolicyMode,
                    videoDecodingMode = diagnostics.videoDecodingMode,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    bufferedAheadMs = diagnostics.bufferedAheadMs,
                    softwareProgress = evidence,
                ),
            )
        }
    }

    fun recordPlaybackHealthSignal(
        context: PlayerDiagnosticContext,
        signal: PlaybackHealthSignal,
    ) {
        val diagnostics = context.runtimeDiagnostics
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSignal,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityCapOrigin = context.qualityCapOrigin,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                    allocatedBufferBytes = diagnostics.allocatedBufferBytes,
                    bufferedAheadMs = diagnostics.bufferedAheadMs,
                    libVlcCachePercent = diagnostics.libVlcCachePercent,
                    healthSignal = signal.kind,
                    healthThresholdClass = signal.thresholdClass(),
                    healthDurationMs = signal.durationMs,
                    healthCount = signal.count,
                ),
            )
        }
    }

    fun recordPlaybackHealthSummary(
        context: PlayerDiagnosticContext,
        facts: PlayerPlaybackHealthSummaryDiagnosticFacts,
    ) {
        val diagnostics = context.runtimeDiagnostics
        val summary = facts.summary
        playerViewModelLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSummary,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    qualityCapOrigin = context.qualityCapOrigin,
                    qualityPolicyMode = context.qualityPolicyMode,
                    qualityPolicyOrigin = context.qualityPolicyOrigin,
                    requestCapBitrateBps = context.requestCapBitrateBps,
                    effectiveTranscodeCapBitrateBps = context.effectiveTranscodeCapBitrateBps,
                    sourceBitrateBps = context.sourceBitrateBps,
                    allocatedBufferBytes = diagnostics.allocatedBufferBytes,
                    bufferedAheadMs = diagnostics.bufferedAheadMs,
                    libVlcCachePercent = diagnostics.libVlcCachePercent,
                    videoDecoderName = diagnostics.videoDecoderName,
                    videoDecodingMode = diagnostics.videoDecodingMode,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    runtimeVideoFrameRate = diagnostics.videoFrameRate,
                    bandwidthEstimateBps = diagnostics.bandwidthEstimateBps,
                    recentVideoRenderP95Ms = diagnostics.recentVideoRenderP95Ms,
                    recentPresentedFrameRate = diagnostics.recentPresentedFrameRate,
                    presentationGapCount = diagnostics.presentationGapCount,
                    nativePrepareToFirstFrameMs = diagnostics.nativePrepareToFirstFrameMs,
                    launchToFirstFrameMs = facts.launchToFirstFrameMs,
                    rebufferCount = diagnostics.rebufferCount,
                    totalRebufferMs = diagnostics.totalRebufferMs,
                    maxRebufferMs = diagnostics.maxRebufferMs,
                    audioUnderrunCount = diagnostics.audioUnderrunCount,
                    maxAudioFeedGapMs = diagnostics.maxAudioFeedGapMs,
                    healthFirstPlayingObserved = summary.firstPlayingObserved,
                    healthBufferingDurationMs = summary.bufferingDurationMs,
                    healthBufferingIntervalCount = summary.bufferingIntervalCount,
                    healthStallCount = summary.stallCount,
                    healthDroppedFrameDurationMs = summary.droppedFrameDurationMs,
                    healthDroppedFrameSampleCount = summary.droppedFrameSampleCount,
                    healthPostStartGuidanceShown = summary.postStartGuidanceShown,
                    healthNoVideoOutputGuidanceShown = summary.noVideoOutputGuidanceShown,
                    healthGuidancePolicy = summary.guidancePolicy,
                    healthGuidancePublishable = summary.guidancePublishable,
                    healthBufferingIntervalOpenedSinceEvidenceRestart =
                        summary.bufferingIntervalOpenedSinceEvidenceRestart,
                    backendDroppedVideoFrames = diagnostics.droppedVideoFrames,
                    backendDroppedVideoFramesPerSecond = diagnostics.droppedVideoFramesPerSecond,
                    decoderDroppedVideoFrames = diagnostics.decoderDroppedVideoFrames,
                    outputDroppedVideoFrames = diagnostics.outputDroppedVideoFrames,
                    healthEmittedSignalCount = summary.emittedSignals.size,
                    firstVideoOutputAvailable = summary.firstVideoOutputMeasurementAvailable,
                    firstVideoOutputObserved = summary.firstVideoOutputObserved,
                    firstVideoOutputEvidence = facts.firstVideoOutputEvidence,
                ),
            )
        }
    }

    fun recordQualityResolved(
        context: PlayerDiagnosticContext,
        maxStreamingBitrateBps: Long?,
        usesVlcDefault: Boolean,
        settingsDefaultBitrateBps: Long?,
        vlcDefaultBitrateBps: Long?,
    ) {
        playerViewModelLogger.i {
            val source = if (usesVlcDefault) "vlcSettingsDefault" else "settingsDefault"
            "Quality resolved maxStreamingBitrate=$maxStreamingBitrateBps source=$source " +
                "settingsDefault=$settingsDefaultBitrateBps vlcDefault=$vlcDefaultBitrateBps"
        }
    }

    private fun recordTrackStateIfChanged(
        context: PlayerDiagnosticContext,
        snapshot: PlayerTrackDiagnosticSnapshot,
    ) {
        val correlatedSnapshot =
            snapshot.copy(
                sessionSequence = context.sessionSequence,
                prepareSequence = context.prepareSequence,
            )
        val previous =
            when (correlatedSnapshot.kind) {
                PlaybackDiagnosticTrackKind.Audio -> lastAudioTrackDiagnostic
                PlaybackDiagnosticTrackKind.Subtitle -> lastSubtitleTrackDiagnostic
            }
        if (previous == correlatedSnapshot) return
        when (correlatedSnapshot.kind) {
            PlaybackDiagnosticTrackKind.Audio -> lastAudioTrackDiagnostic = correlatedSnapshot
            PlaybackDiagnosticTrackKind.Subtitle -> lastSubtitleTrackDiagnostic = correlatedSnapshot
        }
        playerViewModelLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Render,
                    event = PlaybackDiagnosticEvent.TrackState,
                    platform = PlaybackDiagnosticPlatform.Shared,
                    backend = context.backend,
                    prepareSequence = context.prepareSequence,
                    sessionSequence = context.sessionSequence,
                    streamMode = context.streamMode,
                    trackKind = correlatedSnapshot.kind,
                    trackRequestedState = correlatedSnapshot.requestedState,
                    trackConfirmedState = correlatedSnapshot.confirmedState,
                    trackMatchesRequest = correlatedSnapshot.matchesRequest,
                    trackActivation = correlatedSnapshot.activation,
                    subtitleRenderMode = correlatedSnapshot.subtitleRenderMode,
                    subtitleRenderStatus = correlatedSnapshot.subtitleRenderStatus,
                    subtitleStyleable = correlatedSnapshot.subtitleStyleable,
                ),
            )
        }
    }
}

private data class PlayerTrackDiagnosticSnapshot(
    val kind: PlaybackDiagnosticTrackKind,
    val requestedState: PlaybackDiagnosticTrackState,
    val confirmedState: PlaybackDiagnosticTrackState,
    val matchesRequest: Boolean,
    val activation: PlaybackDiagnosticTrackActivation,
    val subtitleRenderMode: com.jellyscope.core.domain.playback.SubtitleRenderMode? = null,
    val subtitleRenderStatus: com.jellyscope.core.domain.playback.SubtitleRenderStatus? = null,
    val subtitleStyleable: Boolean? = null,
    val sessionSequence: Long = 0L,
    val prepareSequence: Long? = null,
)

private data class PlayerTerminalDiagnosticSnapshot(
    val sessionSequence: Long,
    val prepareSequence: Long?,
    val outcome: PlaybackTerminalOutcome,
    val errorName: String?,
    val autoRecoveryTrigger: AutoPlaybackRecoveryTrigger?,
    val recoveryDecision: PlaybackRecoveryDecision?,
)

internal fun PlaybackLaunchReadOutcome.toPersistenceResult(): PlaybackPersistenceResult =
    when (this) {
        PlaybackLaunchReadOutcome.Present -> PlaybackPersistenceResult.Present
        PlaybackLaunchReadOutcome.Missing -> PlaybackPersistenceResult.Missing
        PlaybackLaunchReadOutcome.Failed -> PlaybackPersistenceResult.Failed
        PlaybackLaunchReadOutcome.Unavailable -> PlaybackPersistenceResult.Unavailable
    }

private fun Int?.toDiagnosticTrackState(): PlaybackDiagnosticTrackState =
    if (this == null) PlaybackDiagnosticTrackState.Off else PlaybackDiagnosticTrackState.Selected

private fun Boolean.toDiagnosticTrackState(): PlaybackDiagnosticTrackState =
    if (this) PlaybackDiagnosticTrackState.Selected else PlaybackDiagnosticTrackState.Off

private fun AudioActivationState.toDiagnosticTrackActivation(): PlaybackDiagnosticTrackActivation =
    when (this) {
        AudioActivationState.None -> PlaybackDiagnosticTrackActivation.None
        is AudioActivationState.Pending -> PlaybackDiagnosticTrackActivation.Pending
        is AudioActivationState.Active -> PlaybackDiagnosticTrackActivation.Active
        is AudioActivationState.Unavailable -> PlaybackDiagnosticTrackActivation.Unavailable
    }

private fun SubtitleActivationState.toDiagnosticTrackActivation(): PlaybackDiagnosticTrackActivation =
    when (this) {
        SubtitleActivationState.None -> PlaybackDiagnosticTrackActivation.None
        is SubtitleActivationState.Pending -> PlaybackDiagnosticTrackActivation.Pending
        is SubtitleActivationState.Active -> PlaybackDiagnosticTrackActivation.Active
        is SubtitleActivationState.Unavailable -> PlaybackDiagnosticTrackActivation.Unavailable
    }
