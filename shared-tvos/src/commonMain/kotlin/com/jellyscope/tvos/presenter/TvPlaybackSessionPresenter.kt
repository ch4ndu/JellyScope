// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.AutoPlaybackQualityRecoveryAuthorization
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryCoordinator
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryInput
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryPromptReason
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryState
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryTrigger
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackActivation
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackState
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthExclusionReason
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlaybackHealthSessionContext
import com.jellyscope.core.domain.playback.PlaybackHealthSessionCoordinator
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthSummary
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackRecoveryIntent
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryInput
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryPolicy
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryState
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.audioOptions
import com.jellyscope.core.domain.playback.defaultSubtitleStreamIndex
import com.jellyscope.core.domain.playback.diagnosticClass
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.millisecondsToTicks
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.playerQualityOptions
import com.jellyscope.core.domain.playback.preferredAudioStreamIndex
import com.jellyscope.core.domain.playback.preferredSubtitleStreamIndex
import com.jellyscope.core.domain.playback.qualityOptions
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.core.domain.playback.subtitleRenderInfo
import com.jellyscope.core.domain.playback.toBitrateConstraint
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingCoordinator
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.core.playback.toDiagnosticsSourceDescriptor
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

enum class TvPlaybackPhase {
    Loading,
    Active,
    Failed,
    Completed,
}

data class TvPlaybackUiState(
    val playerInstalled: Boolean = false,
    val phase: TvPlaybackPhase = TvPlaybackPhase.Loading,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    // The system player must disable free scrubbing for transcodes: its seeks
    // bypass the Kotlin controller, so the transcode-restart path can't
    // intercept an out-of-window seek (AVPlayer wedges on those).
    val isTranscode: Boolean = false,
    val itemId: String = "",
    // Increments on every prepare (initial, replan, queue advance) — Swift
    // re-applies item-scoped AVKit decorations (markers, metadata, menus).
    val planEpoch: Long = 0L,
    val prepareEpoch: Long = 0L,
    val title: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val artworkUrl: String? = null,
    val chapters: List<TvChapter> = emptyList(),
    // Track menus render confirmed truth: `selected` reflects platform-
    // confirmed or server-rendered activation, never a pending request.
    val audioTracks: List<TvTrackChoice> = emptyList(),
    val subtitleTracks: List<TvTrackChoice> = emptyList(),
    val qualityChoices: List<TvQualityChoice> = emptyList(),
    val inheritedQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
    val inheritedQualityResolutionHeight: Int? = null,
    val inheritedQualityUsesVlcSetting: Boolean = false,
    val activeSegment: TvActiveSegment? = null,
    val nextEpisode: TvMediaCard? = null,
    val upNextVisible: Boolean = false,
    val playbackGuidance: PlaybackHealthGuidance? = null,
    val playbackActionNotice: PlaybackActionNotice? = null,
    val playbackActions: List<PlaybackAction> = emptyList(),
    val error: PlaybackError? = null,
)

private fun TvPlaybackUiState.hasSwiftVisibleChange(current: TvPlaybackUiState): Boolean =
    copy(
        positionMs = current.positionMs,
        bufferedPositionMs = current.bufferedPositionMs,
    ) != current

/**
 * Playback session for the tvOS system player: plan -> prepare -> play with
 * shared recovery, ordered reporting, track and quality selection, subtitle
 * intent, segment skipping, and chronological episode advancement. Completed
 * publishes only for terminal completion; mid-queue advancement transitions
 * straight to Loading.
 */
class TvPlaybackSessionPresenter(
    private val session: Session,
    initialItemId: String,
    private val requestedMediaSourceId: String?,
    private val initialStartPositionTicks: Long,
    private val playerControllerFactory: () -> PlayerController,
    private val playbackInfoPlanner: PlaybackInfoPlanner,
    private val reportingQueue: PlaybackReportingQueue,
    private val getItemDetail: GetItemDetailUseCase,
    private val getMediaSegments: GetMediaSegmentsUseCase,
    private val getChronologicalEpisodeQueue: GetChronologicalEpisodeQueueUseCase,
    private val getPlaybackLaunchContext: GetPlaybackLaunchContextUseCase,
    private val saveSubtitleSelection: SaveSubtitleSelectionAction,
    private val savePlaybackSelection: com.jellyscope.core.domain.action.SavePlaybackSelectionAction? = null,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val dispatchers: TvosDispatchers,
    private val playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
    private val deviceProfileProvider: DeviceProfileProvider? = null,
    private val playbackHealthGuidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Advisory,
    private val monotonicTimeMs: () -> Long = ::tvosMonotonicTimeMs,
) : TvPresenter(dispatchers) {
    private val tvPlaybackDiagnosticLogger = diagnosticLogger(DiagnosticTag.TvPlaybackSessionPresenter)
    private val _state = MutableStateFlow(TvPlaybackUiState(itemId = initialItemId))
    val state: StateFlow<TvPlaybackUiState> = _state.asStateFlow()

    /** The AVPlayer for the Swift host; cast with `as? AVPlayer`, never trap. */
    val platformPlayer: Any? get() = installedPlayerController?.platformPlayer

    private var installedPlayerController: PlayerController? = null
    private val playerController: PlayerController
        get() = checkNotNull(installedPlayerController) { "Player controller is not installed." }
    private var installedPlaybackReportingCoordinator: PlaybackReportingCoordinator? = null
    private val playbackReportingCoordinator: PlaybackReportingCoordinator
        get() =
            checkNotNull(installedPlaybackReportingCoordinator) {
                "Playback reporting coordinator is not installed."
            }

    private var currentItemId: String = initialItemId
    private var plan: PlaybackPlan? = null
    private var planEpoch = 0L
    private var selectedMediaSourceId: String? = null
    private var selectedSourceContainer: String? = null
    private var mediaStreams: List<PlaybackMediaStream> = emptyList()
    private var detailItem: MediaItem? = null
    private var title: String? = null
    private var seriesName: String? = null
    private var episodeLabel: String? = null
    private var artworkUrl: String? = null
    private var preferences = PlaybackPreferences()

    private var requestedAudioStreamIndex: Int? = null

    // Keep user intent distinct from the player/server's effective selection.
    // A quality write must never turn an automatically resolved or substituted
    // audio stream into a durable per-title override.
    private var explicitAudioStreamIndex: Int? = null
    private var requestedSubtitleStreamIndex: Int? = null
    private var requestedSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified
    private var selectedQualityMaxBitrate: Long? = null
    private var selectedQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto
    private var selectedQualityCapOrigin: PlaybackQualityCapOrigin? = null
    private var rememberedPlaybackSelection: PlaybackSelection? = null
    private var qualityInitialized = false
    private var qualityOverrideExplicit = false
    private var audioActivationRequestId = 0L
    private var subtitleActivationRequestId = 0L
    private var audioRecoveryTarget: AudioActivationTarget? = null
    private var subtitleFallbackTarget: SubtitleActivationTarget? = null
    private var subtitleFallbackGeneration = 0L

    private var playSessionId: String = deviceInfoProvider.newDeviceId()
    private var lastStatus: PlaybackStatus = PlaybackStatus.Idle
    private val autoSkippedSegmentKeys = mutableSetOf<String>()
    private var playbackHealthGeneration = 0L
    private var lastAudioTrackDiagnostic: TvTrackDiagnosticSnapshot? = null
    private var lastSubtitleTrackDiagnostic: TvTrackDiagnosticSnapshot? = null
    private var lastTerminalDiagnostic: TvTerminalDiagnosticSnapshot? = null
    private var controllerFailureRecordedKey: TvControllerFailureKey? = null
    private var videoOutputLoggedPrepareEpoch: Long? = null
    private var pendingRecoveredPlaybackGuidance = false
    private var pendingRecoveredAutoQualityBps: Long? = null
    private var playbackActionNotice: PlaybackActionNotice? = null

    private fun setPlaybackActionNotice(notice: PlaybackActionNotice?) {
        playbackActionNotice = notice?.takeIf { preferences.playbackWarningsEnabled }
    }

    private val autoRecoveryCoordinator = AutoPlaybackRecoveryCoordinator()
    private var autoRecoveryState = AutoPlaybackRecoveryState()
    private val playbackSessionRecoveryPolicy = PlaybackSessionRecoveryPolicy()
    private var playbackSessionRecoveryState = PlaybackSessionRecoveryState()
    private val playbackHealthCoordinator =
        PlaybackHealthSessionCoordinator(
            scope = scope,
            monotonicTimeMs = monotonicTimeMs,
            measurementCapabilities = {
                installedPlayerController?.playbackHealthMeasurementCapabilities
                    ?: com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities.None
            },
            onSignal = { signal ->
                logTvHealthSignal(signal.kind)
                // The warnings preference suppresses PRESENTATION only. Automatic
                // recovery must still run silently, so it is deliberately not
                // gated here — the gate lives in the guidance policy and the
                // notice setter.
                if (playbackHealthGuidancePolicy == PlaybackHealthGuidancePolicy.Actionable) {
                    handleAutomaticRecoverySignal(signal.kind)
                }
            },
            onGuidanceChanged = { guidance ->
                _state.update { current -> current.copy(playbackGuidance = guidance) }
            },
            onSessionEnded = { summary -> logTvHealthSummary(summary) },
        )

    private var episodeQueue: List<MediaItem>? = null
    private var nextEpisodeItem: MediaItem? = null
    private var advanceInFlight = false

    private var started = false
    private var startInFlight = false
    private var closed = false
    private var startupGeneration = 0L
    private var startupJob: Job? = null
    private var observeJob: Job? = null
    private var videoOutputJob: Job? = null
    private var replanJob: Job? = null
    private var queueJob: Job? = null

    fun watchState(onChange: (TvPlaybackUiState) -> Unit): WatchHandle {
        var previousPublished: TvPlaybackUiState? = null
        return state.watchIn(scope) { current ->
            val previous = previousPublished
            if (previous == null || previous.hasSwiftVisibleChange(current)) {
                previousPublished = current
                onChange(current)
            }
        }
    }

    fun start() {
        if (started || closed) {
            return
        }
        started = true
        val expectedGeneration = ++startupGeneration
        startupJob =
            scope.launch {
                val candidateOwner = TvControllerCandidateOwner()
                try {
                    withContext(dispatchers.work) {
                        playerControllerFactory().also(candidateOwner::acquire)
                    }
                    currentCoroutineContext().ensureActive()
                    if (closed || expectedGeneration != startupGeneration) {
                        return@launch
                    }
                    candidateOwner.transferTo { installed ->
                        installedPlayerController = installed
                        try {
                            installedPlaybackReportingCoordinator =
                                PlaybackReportingCoordinator(
                                    queue = reportingQueue,
                                    scope = scope,
                                    playbackState = installed.playbackState,
                                )
                            observeControllerOutput()
                            _state.update { current -> current.copy(playerInstalled = true) }
                        } catch (exception: Throwable) {
                            videoOutputJob?.cancel()
                            videoOutputJob = null
                            installedPlaybackReportingCoordinator = null
                            installedPlayerController = null
                            throw exception
                        }
                    }
                    if (startItem(currentItemId, requestedMediaSourceId, initialStartPositionTicks, resetReporting = false)) {
                        observePlaybackState()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    if (!closed && expectedGeneration == startupGeneration) {
                        _state.update { current ->
                            current.copy(
                                playerInstalled = installedPlayerController != null,
                                phase = TvPlaybackPhase.Failed,
                                error = PlaybackError.Unknown,
                            )
                        }
                    }
                } finally {
                    candidateOwner.releaseUntransferred()
                }
            }
    }

    private fun observeControllerOutput() {
        videoOutputJob =
            scope.launch {
                playerController.videoOutputObservations.collect { observation ->
                    playbackHealthCoordinator.observeVideoOutput(observation)
                    if (observation.presented && videoOutputLoggedPrepareEpoch != playerController.runtimeDiagnostics.value.prepareEpoch) {
                        videoOutputLoggedPrepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch
                        logTvVideoOutput(observation.observedAtMs >= 0L)
                    }
                }
            }
    }

    fun play() {
        playbackHealthCoordinator.restartEvidenceWindow()
        playerController.play()
    }

    fun pause() {
        playbackHealthCoordinator.restartEvidenceWindow()
        playerController.pause()
    }

    fun seekTo(positionMs: Long) {
        if (startInFlight) return
        val target = positionMs.coerceAtLeast(0L)
        playbackHealthCoordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
        val currentPlan = plan
        if (
            playerController.transcodeSeekRestartsStream &&
            currentPlan?.streamMode == StreamMode.Transcode &&
            !isWithinTranscodedWindow(target, currentPlan)
        ) {
            // Out-of-window transcode seeks wedge AVPlayer; restart the
            // transcode at the target instead (same contract as PlayerViewModel).
            replanAtPosition(target)
            return
        }
        playerController.seekTo(target)
        playbackReportingCoordinator.progress(
            positionMs = target,
            isPaused = playerController.playbackState.value.status == PlaybackStatus.Paused,
            eventName = PlaybackProgressEvent.TimeUpdate,
        )
    }

    fun stop() {
        cancelAndInvalidateSubtitleFallback()
        playbackHealthCoordinator.end()
        playbackReportingCoordinator.stop(playerController.playbackState.value.positionMs)
        playerController.stop()
    }

    fun skipActiveSegment() {
        val segment = state.value.activeSegment ?: return
        seekTo(segment.endMs)
    }

    fun playNextEpisode() {
        val next = nextEpisodeItem ?: return
        if (advanceInFlight || startInFlight) {
            return
        }
        advanceInFlight = true
        invalidateSubtitleFallback()
        playbackReportingCoordinator.stop(playerController.playbackState.value.positionMs)
        scope.launch {
            startItem(
                itemId = next.id,
                mediaSourceId = null,
                startPositionTicks = next.playbackPositionTicks ?: 0L,
                resetReporting = true,
            )
            advanceInFlight = false
        }
    }

    fun selectAudio(streamIndex: Int) {
        if (startInFlight) return
        audioOptions(mediaStreams).firstOrNull { track -> track.streamIndex == streamIndex } ?: return
        cancelAndInvalidateSubtitleFallback()
        audioRecoveryTarget = null
        requestedAudioStreamIndex = streamIndex
        explicitAudioStreamIndex = streamIndex
        saveAudioSelection()

        val currentPlan = plan ?: return
        if (currentPlan.streamMode == StreamMode.DirectPlay) {
            val descriptor =
                currentPlan.embeddedAudioTracks.firstOrNull { track -> track.jellyfinStreamIndex == streamIndex }
            val target = newAudioActivationTarget(streamIndex)
            plan =
                currentPlan.copy(
                    selectedAudioStreamIndex = streamIndex,
                    audioActivationTarget = target,
                )
            // Matches the shared player: an inadmissible codec would select
            // natively and then play silently, so it takes the same recovery.
            if (descriptor == null || !descriptor.directPlayAdmissible) {
                maybeHandlePlaybackSessionRecovery(
                    playerController.playbackState.value.copy(
                        audioActivation = AudioActivationState.Unavailable(target),
                    ),
                )
            } else {
                playerController.selectEmbeddedAudio(
                    EmbeddedAudioSelection(target = target, descriptor = descriptor),
                )
            }
            publish(playerController.playbackState.value)
        } else {
            replanAtPosition(playerController.playbackState.value.positionMs)
        }
    }

    fun selectSubtitle(streamIndex: Int?) {
        if (startInFlight) return
        val option =
            streamIndex?.let { selectedIndex ->
                subtitleOptions(mediaStreams).firstOrNull { track -> track.streamIndex == selectedIndex }
                    ?: return
            }
        replanJob?.cancel()
        replanJob = null
        invalidateSubtitleFallback()
        requestedSubtitleStreamIndex = streamIndex
        requestedSubtitleSelection =
            streamIndex?.let(SubtitleSelectionIntent::Track) ?: SubtitleSelectionIntent.Off
        persistSubtitleSelection(requestedSubtitleSelection)

        val currentPlan = plan ?: return
        val installedTrack = currentPlan.plannedSubtitle as? PlannedSubtitle.Track
        val canSwitchInPlayer =
            (option == null && installedTrack?.deliveryMethod in localEmbeddedDeliveryMethods) ||
                (
                    option != null &&
                        installedTrack?.streamIndex == option.streamIndex &&
                        installedTrack.deliveryMethod in localEmbeddedDeliveryMethods
                )
        if (canSwitchInPlayer) {
            val target =
                option?.let { track ->
                    newSubtitleActivationTarget(
                        streamIndex = track.streamIndex,
                        kind = installedTrack?.localKind() ?: LocalSubtitleKind.EmbeddedText,
                    )
                }
            val selection =
                option?.let {
                    EmbeddedSubtitleSelection(
                        target = requireNotNull(target),
                        descriptor = installedTrack?.embeddedTrack ?: return,
                    )
                }
            plan =
                currentPlan.copy(
                    selectedSubtitleStreamIndex = streamIndex,
                    subtitleAsset = null,
                    subtitleActivationTarget = target,
                    plannedSubtitle =
                        if (option == null) {
                            PlannedSubtitle.Off
                        } else {
                            installedTrack?.copy(activationTarget = target) ?: PlannedSubtitle.Off
                        },
                )
            playerController.selectEmbeddedSubtitle(selection)
            publish(playerController.playbackState.value)
        } else {
            if (option == null) {
                playerController.selectEmbeddedSubtitle(null)
            }
            replanAtPosition(playerController.playbackState.value.positionMs)
        }
    }

    fun selectQuality(maxBitrateBps: Long?) {
        if (startInFlight) return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy =
            maxBitrateBps
                ?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
                ?: PlaybackQualityPolicy.Auto
        selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
        selectedQualityCapOrigin = maxBitrateBps?.let { PlaybackQualityCapOrigin.ExplicitSessionChoice }
        qualityInitialized = true
        qualityOverrideExplicit = true
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, playerController.activeBackend)
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    fun selectQuality(policy: PlaybackQualityPolicy) {
        if (startInFlight) return
        pendingRecoveredAutoQualityBps = null
        val normalized = policy.normalized()
        selectedQualityPolicy = normalized
        selectedQualityMaxBitrate = normalized.maxBitrateBps
        selectedQualityCapOrigin =
            PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { normalized.mode == PlaybackQualityMode.Fixed }
        qualityInitialized = true
        qualityOverrideExplicit = true
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, playerController.activeBackend)
        playbackActionNotice = null
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    fun selectQualityChoice(
        modeName: String,
        maxBitrateBps: Long?,
    ) {
        val policy =
            when (modeName) {
                PlaybackQualityMode.Original.name -> PlaybackQualityPolicy.Original
                PlaybackQualityMode.Fixed.name -> maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
                else -> PlaybackQualityPolicy.Auto
            }
        selectQuality(policy)
    }

    fun recordVideoOutputReady(generation: Long = state.value.prepareEpoch) {
        playerController.recordVideoOutputObservation(generation = generation)
    }

    fun handlePlaybackAction(action: PlaybackAction) {
        when (action) {
            PlaybackAction.AcceptAuto -> selectQuality(PlaybackQualityPolicy.Auto)
            PlaybackAction.ClearQualityOverride -> clearQualityOverride()
            PlaybackAction.TryHigherQuality -> tryHigherQuality()
            PlaybackAction.TryOriginal -> selectQuality(PlaybackQualityPolicy.Original)
            PlaybackAction.KeepCurrentQuality -> {
                autoRecoveryState.runtimeQualityCapBps?.let { bitrate ->
                    selectQuality(PlaybackQualityPolicy.fixed(bitrate))
                }
            }
            PlaybackAction.ChooseLowerQuality -> {
                playbackActionNotice = null
                publish(playerController.playbackState.value)
            }
            PlaybackAction.Retry -> {
                playbackSessionRecoveryState =
                    playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, currentItemId)
                playSessionId = deviceInfoProvider.newDeviceId()
                lastStatus = PlaybackStatus.Idle
                plan?.let { currentPlan ->
                    playbackReportingCoordinator.install(
                        session = session,
                        plan = currentPlan,
                        playSessionId = currentPlan.playSessionId ?: playSessionId,
                    )
                }
                playerController.retry()
            }
            PlaybackAction.Dismiss -> {
                pendingRecoveredAutoQualityBps = null
                playbackActionNotice = null
                publish(playerController.playbackState.value)
            }
            PlaybackAction.OpenPlaybackSettings,
            PlaybackAction.Close,
            -> Unit
        }
    }

    private fun clearQualityOverride() {
        if (startInFlight) return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy = preferences.effectiveDefaultQualityPolicy(playerController.activeBackend)
        selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
        selectedQualityCapOrigin =
            PlaybackQualityCapOrigin.SettingsDefault.takeIf { selectedQualityPolicy.mode == PlaybackQualityMode.Fixed }
        qualityInitialized = true
        qualityOverrideExplicit = false
        playbackActionNotice = null
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, playerController.activeBackend)
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    private fun tryHigherQuality() {
        if (startInFlight) return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy = PlaybackQualityPolicy.Auto
        selectedQualityMaxBitrate = null
        selectedQualityCapOrigin = null
        qualityInitialized = true
        qualityOverrideExplicit = true
        playbackActionNotice = null
        autoRecoveryState = autoRecoveryCoordinator.clearRuntimeQualityCap(autoRecoveryState)
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    private fun saveAudioSelection() {
        val sourceId = selectedMediaSourceId ?: return
        savePlaybackSelection?.save(
            PlaybackSelectionKey(session.serverId, session.userId, currentItemId, sourceId),
            PlaybackSelection(
                audioStreamIndex = explicitAudioStreamIndex,
            ),
        )
    }

    override fun close() {
        if (closed) {
            reportingQueue.closeAfterDrain()
            return
        }
        closed = true
        startupGeneration += 1L
        startupJob?.cancel()
        startupJob = null
        val reportingCoordinator = installedPlaybackReportingCoordinator
        installedPlaybackReportingCoordinator = null
        val controller = installedPlayerController
        installedPlayerController = null
        cancelAndInvalidateSubtitleFallback()
        observeJob?.cancel()
        observeJob = null
        videoOutputJob?.cancel()
        videoOutputJob = null
        queueJob?.cancel()
        queueJob = null
        playbackHealthCoordinator.end()
        // The final Stop drains on the queue's own scope, so it survives this
        // presenter's cancellation. A never-started session enqueues nothing.
        if (reportingCoordinator != null && controller != null) {
            reportingCoordinator.dispose(controller.playbackState.value.positionMs)
            controller.release()
        }
        reportingQueue.closeAfterDrain()
        super.close()
    }

    // Loads detail + segments, resolves selections, plans, decorates with
    // activation targets, prepares, and starts. Shared by the initial start,
    // and queue advancement. Returns false when the item cannot start.
    private suspend fun startItem(
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        resetReporting: Boolean,
    ): Boolean {
        // A stale replan finishing after the item identity changes would
        // install the previous episode's plan over the new one.
        cancelAndInvalidateSubtitleFallback()
        queueJob?.cancel()
        startInFlight = true
        val startedOk: Boolean
        try {
            startedOk = startItemLocked(itemId, mediaSourceId, startPositionTicks, resetReporting)
        } finally {
            startInFlight = false
        }
        // No explicit replay: prepare always pushes a fresh state synchronously
        // (Apple controller contract, mirrored by the test fake), so the
        // collector — which cannot resume on the main dispatcher until this
        // gate is already down — always receives the new plan's latest sample
        // exactly once, including an immediate synchronous Failed from play().
        // Replaying here as well would process that sample twice and
        // double-advance the recovery ladder.
        if (startedOk) {
            plan?.let(::maybeStartInstalledUnavailableFallback)
        }
        return startedOk
    }

    private suspend fun startItemLocked(
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        resetReporting: Boolean,
    ): Boolean {
        autoSkippedSegmentKeys.clear()
        audioRecoveryTarget = null
        pendingRecoveredPlaybackGuidance = false
        pendingRecoveredAutoQualityBps = null
        nextEpisodeItem = null
        episodeQueue = null
        currentItemId = itemId
        explicitAudioStreamIndex = null
        qualityInitialized = false
        qualityOverrideExplicit = false
        rememberedPlaybackSelection = null
        playbackHealthCoordinator.end()
        playbackHealthGeneration += 1L
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, itemId, playerController.activeBackend)
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, itemId)
        playbackActionNotice = null
        playbackHealthCoordinator.start(
            generation = playbackHealthGeneration,
            context = playbackHealthSessionContext(),
        )
        // Stale menus must not act on the outgoing item mid-switch.
        mediaStreams = emptyList()
        _state.update { current ->
            current.copy(
                phase = TvPlaybackPhase.Loading,
                itemId = itemId,
                activeSegment = null,
                nextEpisode = null,
                upNextVisible = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                qualityChoices = emptyList(),
                chapters = emptyList(),
            )
        }

        val detail =
            withContext(dispatchers.work) {
                getItemDetail(itemId, includePlaybackFields = true)
            }.getOrElse {
                // A failed detail fetch is a transport problem, not proof the
                // title is unplayable.
                publishFailure(PlaybackError.Network)
                return false
            }
        val version = detail.selectedVersion(mediaSourceId)
        if (version == null) {
            publishFailure(PlaybackError.UnsupportedMedia)
            return false
        }
        detailItem = detail.item
        title = detail.item.name
        seriesName = detail.item.seriesName
        episodeLabel = detail.item.episodeLabel
        artworkUrl =
            detail.item.imageRefs.primaryTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = detail.item.id,
                    type = com.jellyscope.core.domain.model.JellyfinImageType.Primary,
                    tag = tag,
                    maxWidth = CARD_IMAGE_MAX_WIDTH,
                )
            }
        selectedMediaSourceId = version.id
        selectedSourceContainer = version.container
        mediaStreams = version.mediaStreams

        val launchContext =
            withContext(dispatchers.work) {
                getPlaybackLaunchContext(session, itemId, version.id)
            }
        preferences = launchContext.playbackPreferences
        val effectiveStartTicks = startPositionTicks
        if (!qualityInitialized) {
            val rememberedSelection = launchContext.playbackSelection
            rememberedPlaybackSelection = rememberedSelection
            qualityOverrideExplicit = false
            selectedQualityPolicy = preferences.effectiveDefaultQualityPolicy(playerController.activeBackend)
            selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
            selectedQualityCapOrigin =
                when {
                    selectedQualityMaxBitrate != null -> PlaybackQualityCapOrigin.SettingsDefault
                    else -> null
                }
            qualityInitialized = true
        }

        // Segments only feed the skip UI; fetch them concurrently with the
        // PlaybackInfo round-trip, parented to this start job.
        val segmentsDeferred =
            CoroutineScope(currentCoroutineContext()).async(dispatchers.work) {
                getMediaSegments(itemId).getOrDefault(emptyList())
            }

        val audioTrackOptions = audioOptions(mediaStreams)
        explicitAudioStreamIndex =
            rememberedPlaybackSelection?.audioStreamIndex?.takeIf { index ->
                audioTrackOptions.any { option -> option.streamIndex == index }
            }
        requestedAudioStreamIndex =
            explicitAudioStreamIndex
                ?: audioTrackOptions.preferredAudioStreamIndex(preferences.preferredAudioLanguage)
                ?: audioTrackOptions.firstOrNull { option -> option.isDefault }?.streamIndex
                ?: audioTrackOptions.firstOrNull()?.streamIndex
        requestedSubtitleSelection =
            resolveSubtitleSelection(
                stored = launchContext.subtitleSelection,
                key = subtitleSelectionKey(itemId, version.id),
                options = subtitleOptions(mediaStreams),
            )
        requestedSubtitleStreamIndex =
            (requestedSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex

        val playbackPlan =
            try {
                withContext(dispatchers.work) {
                    playbackInfoPlanner.plan(
                        session = session,
                        itemId = itemId,
                        mediaSourceId = version.id,
                        startPositionTicks = effectiveStartTicks,
                        audioStreamIndex = requestedAudioStreamIndex,
                        detailMediaStreams = mediaStreams,
                        subtitleSelection = requestedSubtitleSelection,
                        maxStreamingBitrate = selectedQualityMaxBitrate,
                        qualityPolicy = selectedQualityPolicy,
                        qualityCapOrigin = selectedQualityCapOrigin,
                        sourceContainer = selectedSourceContainer,
                        requestPolicy =
                            PlaybackInfoRequestPolicy(
                                diagnosticSessionSequence = playbackHealthGeneration,
                            ),
                    )
                }
            } catch (exception: CancellationException) {
                segmentsDeferred.cancel()
                throw exception
            } catch (exception: Throwable) {
                segmentsDeferred.cancel()
                logTvDiagnostic(
                    stage = PlaybackDiagnosticStage.Planner,
                    event = PlaybackDiagnosticEvent.Failed,
                    exception = exception,
                )
                publishFailure(PlaybackError.Network)
                return false
            }
        val segments = segmentsDeferred.await()
        val enrichedPlan =
            playbackPlan
                .withAudioActivationTarget()
                .withSubtitleActivationTarget(
                    requestId = nextSubtitleActivationRequestId(),
                    selectedSubtitle = selectedSubtitleMediaStream(),
                ).copy(
                    chapters = detail.chapters,
                    mediaSegments = segments,
                )
        currentCoroutineContext().ensureActive()
        installPlan(enrichedPlan, resetReporting = resetReporting)
        logTvDiagnostic(
            stage = PlaybackDiagnosticStage.Prepare,
            event = PlaybackDiagnosticEvent.PrepareRequested,
        )
        playerController.prepare(enrichedPlan)
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        logTvDiagnostic(
            stage = PlaybackDiagnosticStage.Prepare,
            event = PlaybackDiagnosticEvent.PrepareDispatched,
        )
        applyInitialEmbeddedSelections(enrichedPlan)
        playerController.play()
        deriveEpisodeQueueIfNeeded(detail.item)
        return true
    }

    private fun deriveEpisodeQueueIfNeeded(item: MediaItem) {
        if (item.kind != MediaKind.Episode) {
            return
        }
        queueJob =
            scope.launch {
                val queue =
                    withContext(dispatchers.work) {
                        getChronologicalEpisodeQueue(item)
                    }.getOrNull() ?: return@launch
                episodeQueue = queue
                val currentIndex = queue.indexOfFirst { episode -> episode.id == currentItemId }
                nextEpisodeItem = queue.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                publish(playerController.playbackState.value)
            }
    }

    private fun observePlaybackState() {
        if (observeJob != null) {
            return
        }
        observeJob =
            scope.launch {
                playerController.playbackState.collect { playbackState ->
                    onPlaybackState(playbackState)
                }
            }
    }

    private fun onPlaybackState(playbackState: PlaybackState) {
        // The outgoing item keeps publishing (Apple controller polls on a
        // timer) while the next item is planning; processing those samples
        // would republish stale state or spawn fallback replans against the
        // new identity. The new plan publishes explicitly at startItem's end.
        if (startInFlight) {
            return
        }
        playbackHealthCoordinator.observePlaybackState(playbackState)
        if (playbackState.status == PlaybackStatus.Failed) {
            recordControllerFailure()
        }
        if (playbackState.status == PlaybackStatus.Playing && pendingRecoveredPlaybackGuidance) {
            pendingRecoveredPlaybackGuidance = false
            playbackHealthCoordinator.recordRecoveredPlaybackFailure()
        }
        if (playbackState.status == PlaybackStatus.Playing) {
            pendingRecoveredAutoQualityBps?.let { bitrate ->
                pendingRecoveredAutoQualityBps = null
                setPlaybackActionNotice(
                    PlaybackActionNotice(
                        reason = PlaybackActionNoticeReason.QualityRecoveryApplied,
                        runtimeQualityCapBps = bitrate,
                        actions =
                            setOf(
                                PlaybackAction.KeepCurrentQuality,
                                PlaybackAction.TryHigherQuality,
                                PlaybackAction.ChooseLowerQuality,
                                PlaybackAction.Dismiss,
                            ),
                    ),
                )
            }
        }
        if (playbackState.status == PlaybackStatus.Completed) {
            handleCompleted(playbackState)
            lastStatus = playbackState.status
            return
        }
        val recoveryResult = maybeHandlePlaybackSessionRecovery(playbackState)
        if (recoveryResult != TvSessionRecoveryResult.NotHandled) {
            val terminalOutcome =
                when (recoveryResult) {
                    TvSessionRecoveryResult.RetryScheduled -> PlaybackTerminalOutcome.RetryScheduled
                    TvSessionRecoveryResult.Failed -> PlaybackTerminalOutcome.Failed
                    TvSessionRecoveryResult.NonTerminal,
                    TvSessionRecoveryResult.NotHandled,
                    -> null
                }
            if (playbackState.status == PlaybackStatus.Failed && terminalOutcome != null) {
                logTvDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.TerminalError,
                    terminalOutcome = terminalOutcome,
                    error = playbackState.error,
                )
            }
            lastStatus = playbackState.status
            return
        }
        playbackReportingCoordinator.onPlaybackState(playbackState, lastStatus)
        maybeAutoSkipSegment(playbackState)
        if (playbackState.status == PlaybackStatus.Failed) {
            logTvDiagnostic(
                stage = PlaybackDiagnosticStage.NativePlayer,
                event = PlaybackDiagnosticEvent.TerminalError,
                terminalOutcome = PlaybackTerminalOutcome.Failed,
                error = playbackState.error,
            )
            playbackReportingCoordinator.stop(playbackState.positionMs)
        }
        publish(playbackState)
        lastStatus = playbackState.status
    }

    // Completed publishes only for terminal completion: a verified natural end
    // with a next episode advances in place (Completed-free), everything else
    // reports Stopped and surfaces the terminal phase.
    private fun handleCompleted(playbackState: PlaybackState) {
        val isNewCompletion = lastStatus != PlaybackStatus.Completed
        val next = nextEpisodeItem
        val duration = playbackState.durationMs
        val verifiedEnd = duration != null && playbackState.positionMs >= duration - VERIFIED_END_TOLERANCE_MS
        if (isNewCompletion && next != null && verifiedEnd && !advanceInFlight) {
            advanceInFlight = true
            invalidateSubtitleFallback()
            playbackReportingCoordinator.stop(playbackState.positionMs)
            scope.launch {
                startItem(
                    itemId = next.id,
                    mediaSourceId = null,
                    startPositionTicks = next.playbackPositionTicks ?: 0L,
                    resetReporting = true,
                )
                advanceInFlight = false
            }
            return
        }
        if (isNewCompletion) {
            playbackReportingCoordinator.stop(playbackState.positionMs)
        }
        if (!advanceInFlight) {
            publish(playbackState, terminalCompleted = true)
        }
    }

    private fun maybeHandlePlaybackSessionRecovery(playbackState: PlaybackState): TvSessionRecoveryResult {
        val result =
            playbackSessionRecoveryPolicy.decide(
                PlaybackSessionRecoveryInput(
                    generation = playbackHealthGeneration,
                    itemId = currentItemId,
                    plan = plan,
                    playbackState = playbackState,
                    state = playbackSessionRecoveryState,
                ),
            )
        playbackSessionRecoveryState = result.state
        if (
            result.decision !is PlaybackSessionRecoveryDecision.AutomaticRecovery &&
            result.decision !is PlaybackSessionRecoveryDecision.NoAction
        ) {
            logTvSessionRecoveryDecision(result.decision)
        }
        return when (val decision = result.decision) {
            PlaybackSessionRecoveryDecision.NoAction -> TvSessionRecoveryResult.NotHandled

            is PlaybackSessionRecoveryDecision.AutomaticRecovery -> {
                if (handleAutomaticRecoveryTrigger(decision.trigger)) {
                    TvSessionRecoveryResult.RetryScheduled
                } else {
                    TvSessionRecoveryResult.Failed
                }
            }

            is PlaybackSessionRecoveryDecision.NetworkRetry -> {
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                    forcePlay = true,
                )
                TvSessionRecoveryResult.RetryScheduled
            }

            is PlaybackSessionRecoveryDecision.AudioActivationRecovery -> {
                audioRecoveryTarget = decision.target
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                )
                TvSessionRecoveryResult.RetryScheduled
            }

            is PlaybackSessionRecoveryDecision.SubtitleEncodeRecovery -> {
                playerController.selectEmbeddedSubtitle(null)
                subtitleFallbackTarget = decision.target
                val fallbackGeneration = subtitleFallbackGeneration
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                    nonFatalSubtitleFallback = decision.target,
                    subtitleFallbackGeneration = fallbackGeneration,
                )
                TvSessionRecoveryResult.RetryScheduled
            }

            is PlaybackSessionRecoveryDecision.SubtitleUnavailable -> {
                markSubtitleFallbackUnavailable(decision.target)
                TvSessionRecoveryResult.NonTerminal
            }
        }
    }

    private fun maybeStartInstalledUnavailableFallback(playbackPlan: PlaybackPlan) {
        val unavailable = playbackPlan.plannedSubtitle as? PlannedSubtitle.Unavailable ?: return
        if (!unavailable.allowEncodeFallback || unavailable.normalizedFormat == null) {
            return
        }
        val target = unavailable.activationTarget ?: return
        val unavailableState =
            playerController.playbackState.value.copy(
                subtitleActivation = SubtitleActivationState.Unavailable(target),
            )
        maybeHandlePlaybackSessionRecovery(unavailableState)
    }

    private fun markSubtitleFallbackUnavailable(target: SubtitleActivationTarget) {
        val currentPlan = plan ?: return
        if (currentPlan.subtitleActivationTarget != target) {
            return
        }
        val unavailable =
            when (val plannedSubtitle = currentPlan.plannedSubtitle) {
                is PlannedSubtitle.Track ->
                    PlannedSubtitle.Unavailable(
                        streamIndex = plannedSubtitle.streamIndex,
                        kind = plannedSubtitle.kind,
                        normalizedFormat = plannedSubtitle.normalizedFormat,
                        reason = "Server-rendered subtitle fallback failed",
                    )
                is PlannedSubtitle.Unavailable ->
                    plannedSubtitle.copy(
                        allowEncodeFallback = false,
                        activationTarget = null,
                    )
                PlannedSubtitle.Off,
                is PlannedSubtitle.LocalAsset,
                is PlannedSubtitle.OfflineSidecar,
                -> return
            }
        playerController.selectEmbeddedSubtitle(null)
        plan =
            currentPlan.copy(
                selectedSubtitleStreamIndex = null,
                subtitleAsset = null,
                subtitleActivationTarget = null,
                plannedSubtitle = unavailable,
            )
        subtitleFallbackTarget = null
        planEpoch += 1
        publish(playerController.playbackState.value)
    }

    private fun replanAtPosition(
        targetPositionMs: Long,
        requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
        forcePlay: Boolean = false,
        nonFatalSubtitleFallback: SubtitleActivationTarget? = null,
        subtitleFallbackGeneration: Long? = null,
    ) {
        if (startInFlight) return
        val mediaSource = selectedMediaSourceId ?: return
        val target = targetPositionMs.coerceAtLeast(0L)
        playbackHealthCoordinator.markExclusion(PlaybackHealthExclusionReason.Replan)
        val replanItemId = currentItemId
        if (nonFatalSubtitleFallback == null) {
            invalidateSubtitleFallback()
        }
        replanJob?.cancel()
        replanJob =
            scope.launch {
                val wasPaused = playerController.playbackState.value.status == PlaybackStatus.Paused
                _state.update { current -> current.copy(phase = TvPlaybackPhase.Loading) }
                val playbackPlan =
                    try {
                        withContext(dispatchers.work) {
                            playbackInfoPlanner.plan(
                                session = session,
                                itemId = replanItemId,
                                mediaSourceId = mediaSource,
                                startPositionTicks = millisecondsToTicks(target),
                                audioStreamIndex = requestedAudioStreamIndex,
                                detailMediaStreams = mediaStreams,
                                subtitleSelection = requestedSubtitleSelection,
                                maxStreamingBitrate = selectedQualityMaxBitrate,
                                qualityPolicy = selectedQualityPolicy,
                                qualityCapOrigin = selectedQualityCapOrigin,
                                sourceContainer = selectedSourceContainer,
                                requestPolicy =
                                    requestPolicy.copy(
                                        diagnosticSessionSequence = playbackHealthGeneration,
                                        bitrateConstraint =
                                            requestPolicy.bitrateConstraint.takeUnless { constraint ->
                                                constraint == PlaybackBitrateConstraint.NoClientLimit
                                            } ?: activePlaybackBitrateConstraint(),
                                    ),
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logTvDiagnostic(
                            stage = PlaybackDiagnosticStage.Planner,
                            event = PlaybackDiagnosticEvent.Failed,
                            exception = exception,
                        )
                        if (
                            nonFatalSubtitleFallback != null &&
                            isCurrentSubtitleFallback(
                                target = nonFatalSubtitleFallback,
                                generation = subtitleFallbackGeneration,
                                itemId = replanItemId,
                            )
                        ) {
                            markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                        } else if (nonFatalSubtitleFallback == null) {
                            publishFailure(PlaybackError.Network)
                        }
                        return@launch
                    }
                if (nonFatalSubtitleFallback != null) {
                    if (
                        !isCurrentSubtitleFallback(
                            target = nonFatalSubtitleFallback,
                            generation = subtitleFallbackGeneration,
                            itemId = replanItemId,
                        )
                    ) {
                        return@launch
                    }
                    val fallbackTrack = playbackPlan.plannedSubtitle as? PlannedSubtitle.Track
                    if (
                        fallbackTrack?.deliveryMethod != SubtitleDeliveryMethod.Encode ||
                        fallbackTrack.streamIndex != nonFatalSubtitleFallback.streamIndex
                    ) {
                        markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                        return@launch
                    }
                }
                if (audioRecoveryTarget != null && playbackPlan.streamMode == StreamMode.DirectPlay) {
                    publishFailure(PlaybackError.UnsupportedMedia)
                    return@launch
                }
                val currentPlanState = plan
                val enrichedPlan =
                    playbackPlan
                        .withAudioActivationTarget()
                        .withSubtitleActivationTarget(
                            requestId = nextSubtitleActivationRequestId(),
                            selectedSubtitle = selectedSubtitleMediaStream(),
                        ).copy(
                            chapters = currentPlanState?.chapters.orEmpty(),
                            mediaSegments = currentPlanState?.mediaSegments.orEmpty(),
                        )
                if (
                    nonFatalSubtitleFallback != null &&
                    !isCurrentSubtitleFallback(
                        target = nonFatalSubtitleFallback,
                        generation = subtitleFallbackGeneration,
                        itemId = replanItemId,
                    )
                ) {
                    return@launch
                }
                playbackReportingCoordinator.stopNow(target)
                currentCoroutineContext().ensureActive()
                if (
                    nonFatalSubtitleFallback != null &&
                    !isCurrentSubtitleFallback(
                        target = nonFatalSubtitleFallback,
                        generation = subtitleFallbackGeneration,
                        itemId = replanItemId,
                    )
                ) {
                    return@launch
                }
                installPlan(enrichedPlan, resetReporting = true)
                if (nonFatalSubtitleFallback != null) {
                    subtitleFallbackTarget = null
                }
                logTvDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.PrepareRequested,
                )
                playerController.prepare(enrichedPlan)
                playerController.runtimeDiagnostics.value.prepareEpoch
                    ?.let(playbackHealthCoordinator::expectVideoOutput)
                logTvDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.PrepareDispatched,
                )
                applyInitialEmbeddedSelections(enrichedPlan)
                if (forcePlay || !wasPaused) {
                    playerController.play()
                } else {
                    playerController.pause()
                }
                if (audioRecoveryTarget != null && enrichedPlan.streamMode != StreamMode.DirectPlay) {
                    audioRecoveryTarget = null
                }
                publish(playerController.playbackState.value)
                maybeStartInstalledUnavailableFallback(enrichedPlan)
            }
    }

    private fun installPlan(
        playbackPlan: PlaybackPlan,
        resetReporting: Boolean,
    ) {
        plan = playbackPlan
        updatePlaybackHealthSessionContext()
        planEpoch += 1
        val authoritativeAudio =
            playbackPlan.selectedAudioStreamIndex.takeIf { playbackPlan.audioSelectionAuthoritative }
        if (authoritativeAudio != null) {
            requestedAudioStreamIndex = authoritativeAudio
            // Server/decoder truth affects this prepared plan only. Retaining
            // explicitAudioStreamIndex preserves a genuine user pick and keeps
            // automatic resolution from becoming durable on a later quality save.
        }
        if (resetReporting) {
            playSessionId = deviceInfoProvider.newDeviceId()
            lastStatus = PlaybackStatus.Idle
        }
        playbackReportingCoordinator.install(
            session = session,
            plan = playbackPlan,
            playSessionId = playbackPlan.playSessionId ?: playSessionId,
        )
    }

    private fun playbackHealthSessionContext(): PlaybackHealthSessionContext =
        PlaybackHealthSessionContext(
            sessionToken = playbackHealthGeneration,
            streamMode = plan?.streamMode,
            guidancePolicy =
                if (preferences.playbackWarningsEnabled) {
                    playbackHealthGuidancePolicy
                } else {
                    PlaybackHealthGuidancePolicy.Disabled
                },
            isOffline = plan?.streamMode == StreamMode.Offline,
            videoExpected = mediaStreams.any { stream -> stream.type.equals("Video", ignoreCase = true) },
            backend = playerController.activeBackend,
        )

    private fun updatePlaybackHealthSessionContext() {
        playbackHealthCoordinator.updateContext(playbackHealthSessionContext())
    }

    private fun handleAutomaticRecoverySignal(signalKind: PlaybackHealthSignalKind) {
        val trigger =
            when (signalKind) {
                PlaybackHealthSignalKind.NoVideoOutput -> AutoPlaybackRecoveryTrigger.NoVideoOutput
                PlaybackHealthSignalKind.CumulativeBuffering -> AutoPlaybackRecoveryTrigger.CumulativeBuffering
                PlaybackHealthSignalKind.RepeatedStalls -> AutoPlaybackRecoveryTrigger.RepeatedStalls
                PlaybackHealthSignalKind.DroppedFrames -> AutoPlaybackRecoveryTrigger.DroppedFrames
                PlaybackHealthSignalKind.SlowStartup,
                PlaybackHealthSignalKind.LongBuffering,
                -> return
            }
        handleAutomaticRecoveryTrigger(trigger)
    }

    private fun handleAutomaticRecoveryTrigger(trigger: AutoPlaybackRecoveryTrigger): Boolean {
        val currentPlan = plan ?: return false
        val result =
            autoRecoveryCoordinator.decide(
                AutoPlaybackRecoveryInput(
                    generation = playbackHealthGeneration,
                    policy = selectedQualityPolicy,
                    qualityRecoveryAuthorization = autoQualityRecoveryAuthorization(),
                    backend = playerController.activeBackend,
                    plan = currentPlan,
                    sourceBitrateBps = currentSourceBitrate(),
                    lowerQualityRungsBps = qualityOptions(currentSourceBitrate()).mapNotNull { option -> option.maxBitrateBps },
                    state = autoRecoveryState,
                    trigger = trigger,
                ),
            )
        autoRecoveryState = result.state
        logTvAutomaticRecoveryDecision(trigger, result.decision)
        when (val decision = result.decision) {
            AutoPlaybackRecoveryDecision.NoAction -> return false
            is AutoPlaybackRecoveryDecision.CompatibilityReplan -> {
                pendingRecoveredPlaybackGuidance = true
                replanAtPosition(
                    targetPositionMs = playerController.playbackState.value.positionMs,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            allowVideoStreamCopy = false,
                            clientTrigger = com.jellyscope.core.domain.playback.PlaybackClientTrigger.PlayerFailureFallback,
                            bitrateConstraint = activePlaybackBitrateConstraint(),
                            recoveryIntent = PlaybackRecoveryIntent.Compatibility,
                        ),
                    forcePlay = true,
                )
                return true
            }
            is AutoPlaybackRecoveryDecision.LowerTo -> {
                selectedQualityPolicy = PlaybackQualityPolicy.Auto
                selectedQualityMaxBitrate = decision.maxBitrateBps
                selectedQualityCapOrigin = PlaybackQualityCapOrigin.AutoSessionRecovery
                pendingRecoveredAutoQualityBps = decision.maxBitrateBps
                playbackActionNotice = null
                replanAtPosition(
                    targetPositionMs = playerController.playbackState.value.positionMs,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            clientTrigger = com.jellyscope.core.domain.playback.PlaybackClientTrigger.PlayerFailureFallback,
                            bitrateConstraint = PlaybackBitrateConstraint.AutoSessionLimit(decision.maxBitrateBps),
                            recoveryIntent = PlaybackRecoveryIntent.Quality,
                        ),
                    forcePlay = true,
                )
                return true
            }
            is AutoPlaybackRecoveryDecision.PromptUser -> {
                setPlaybackActionNotice(
                    PlaybackActionNotice(
                        reason =
                            when (decision.reason) {
                                AutoPlaybackRecoveryPromptReason.OriginalPlaybackFailed -> PlaybackActionNoticeReason.OriginalPlaybackFailed
                                AutoPlaybackRecoveryPromptReason.FixedQualityFailed -> PlaybackActionNoticeReason.FixedQualityFailed
                                AutoPlaybackRecoveryPromptReason.CompatibilityRecoveryExhausted ->
                                    PlaybackActionNoticeReason.CompatibilityRecoveryExhausted
                                AutoPlaybackRecoveryPromptReason.QualityRecoveryExhausted,
                                AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
                                AutoPlaybackRecoveryPromptReason.AutoQualityRecoveryRequiresExplicitSessionChoice,
                                -> PlaybackActionNoticeReason.NoLowerQualityAvailable
                            },
                        actions = decision.actions,
                    ),
                )
                publish(playerController.playbackState.value)
                return false
            }
        }
    }

    private fun autoQualityRecoveryAuthorization(): AutoPlaybackQualityRecoveryAuthorization =
        if (qualityOverrideExplicit && selectedQualityPolicy.mode == PlaybackQualityMode.Auto) {
            AutoPlaybackQualityRecoveryAuthorization.ExplicitSessionAuto
        } else {
            AutoPlaybackQualityRecoveryAuthorization.NotAuthorized
        }

    private fun activePlaybackBitrateConstraint(): PlaybackBitrateConstraint =
        if (selectedQualityPolicy.mode == PlaybackQualityMode.Auto) {
            autoRecoveryState.runtimeQualityCapBps
                ?.let(PlaybackBitrateConstraint.AutoSessionLimit::of)
                ?: PlaybackBitrateConstraint.NoClientLimit
        } else {
            selectedQualityPolicy.toBitrateConstraint()
        }

    private fun applyInitialEmbeddedSelections(playbackPlan: PlaybackPlan) {
        if (playbackPlan.streamMode == StreamMode.DirectPlay) {
            val target = playbackPlan.audioActivationTarget
            val descriptor =
                playbackPlan.embeddedAudioTracks.firstOrNull { track ->
                    track.jellyfinStreamIndex == playbackPlan.selectedAudioStreamIndex
                }
            if (target != null && descriptor != null) {
                playerController.selectEmbeddedAudio(
                    EmbeddedAudioSelection(target = target, descriptor = descriptor),
                )
            }
        }
        when (val plannedSubtitle = playbackPlan.plannedSubtitle) {
            is PlannedSubtitle.Off, is PlannedSubtitle.Unavailable -> playerController.selectEmbeddedSubtitle(null)
            is PlannedSubtitle.LocalAsset -> Unit
            is PlannedSubtitle.OfflineSidecar -> Unit
            is PlannedSubtitle.Track ->
                if (plannedSubtitle.deliveryMethod in localEmbeddedDeliveryMethods) {
                    val target = plannedSubtitle.activationTarget ?: return
                    playerController.selectEmbeddedSubtitle(
                        EmbeddedSubtitleSelection(
                            target = target,
                            descriptor = plannedSubtitle.embeddedTrack ?: return,
                        ),
                    )
                }
        }
    }

    // Stored durable intent (invalid entries deleted — the intent store is
    // device-local, so this never clobbers another device), then preferred
    // language, then the default track, then Off.
    private suspend fun resolveSubtitleSelection(
        stored: SubtitleSelectionIntent?,
        key: SubtitleSelectionKey,
        options: List<SubtitleTrackOption>,
    ): SubtitleSelectionIntent {
        when (stored) {
            SubtitleSelectionIntent.Off -> return SubtitleSelectionIntent.Off
            is SubtitleSelectionIntent.Track ->
                if (options.any { option -> option.streamIndex == stored.streamIndex }) {
                    return stored
                } else {
                    saveSubtitleSelection.delete(key)
                }
            is SubtitleSelectionIntent.LocalAsset -> saveSubtitleSelection.delete(key)
            SubtitleSelectionIntent.Unspecified, null -> Unit
        }
        return options
            .preferredSubtitleStreamIndex(preferences.preferredSubtitleLanguage)
            ?.let(SubtitleSelectionIntent::Track)
            ?: options.defaultSubtitleStreamIndex()?.let(SubtitleSelectionIntent::Track)
            ?: SubtitleSelectionIntent.Off
    }

    private fun subtitleSelectionKey(
        itemId: String,
        mediaSourceId: String,
    ): SubtitleSelectionKey =
        SubtitleSelectionKey(
            serverId = session.serverId,
            userId = session.userId,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
        )

    private fun persistSubtitleSelection(selection: SubtitleSelectionIntent) {
        val sourceId = selectedMediaSourceId?.takeIf { id -> id.isNotBlank() } ?: return
        saveSubtitleSelection.save(
            key = subtitleSelectionKey(currentItemId, sourceId),
            selection = selection,
        )
    }

    private fun selectedSubtitleMediaStream(): PlaybackMediaStream? {
        val streamIndex = requestedSubtitleStreamIndex ?: return null
        val option =
            subtitleOptions(mediaStreams).firstOrNull { track -> track.streamIndex == streamIndex } ?: return null
        val stream =
            mediaStreams
                .filter { mediaStream -> mediaStream.type.equals("Subtitle", ignoreCase = true) }
                .getOrNull(option.ordinal)
                ?: return null
        return if (stream.index == option.streamIndex) stream else stream.copy(index = option.streamIndex)
    }

    private fun newAudioActivationTarget(streamIndex: Int): AudioActivationTarget =
        AudioActivationTarget(
            requestId = ++audioActivationRequestId,
            itemId = currentItemId,
            streamIndex = streamIndex,
        )

    private fun nextSubtitleActivationRequestId(): Long {
        subtitleActivationRequestId += 1L
        return subtitleActivationRequestId
    }

    private fun invalidateSubtitleFallback() {
        subtitleFallbackGeneration += 1L
        subtitleFallbackTarget = null
    }

    private fun cancelAndInvalidateSubtitleFallback() {
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob = null
    }

    private fun isCurrentSubtitleFallback(
        target: SubtitleActivationTarget,
        generation: Long?,
        itemId: String,
    ): Boolean =
        generation == subtitleFallbackGeneration &&
            subtitleFallbackTarget == target &&
            target.itemId == itemId &&
            currentItemId == itemId &&
            plan?.subtitleActivationTarget == target &&
            (requestedSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex == target.streamIndex

    private fun newSubtitleActivationTarget(
        streamIndex: Int,
        kind: LocalSubtitleKind,
    ): SubtitleActivationTarget =
        SubtitleActivationTarget(
            requestId = nextSubtitleActivationRequestId(),
            itemId = currentItemId,
            identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
            kind = kind,
        )

    private fun PlaybackPlan.withAudioActivationTarget(): PlaybackPlan {
        val streamIndex = selectedAudioStreamIndex ?: return copy(audioActivationTarget = null)
        return copy(audioActivationTarget = newAudioActivationTarget(streamIndex))
    }

    private fun PlaybackPlan.withSubtitleActivationTarget(
        requestId: Long,
        selectedSubtitle: PlaybackMediaStream?,
    ): PlaybackPlan {
        val plannedTrack = plannedSubtitle as? PlannedSubtitle.Track
        val unavailable = plannedSubtitle as? PlannedSubtitle.Unavailable
        val streamIndex = plannedTrack?.streamIndex ?: unavailable?.streamIndex
        val kind =
            plannedTrack?.localKind()
                ?: unavailable
                    ?.takeIf { it.allowEncodeFallback }
                    ?.let {
                        when {
                            selectedSubtitle?.isExternal == true -> LocalSubtitleKind.ExternalText
                            it.kind == SubtitleKind.Bitmap -> LocalSubtitleKind.EmbeddedBitmap
                            else -> LocalSubtitleKind.EmbeddedText
                        }
                    }
        val target =
            if (streamIndex != null && kind != null) {
                SubtitleActivationTarget(
                    requestId = requestId,
                    itemId = currentItemId,
                    identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
                    kind = kind,
                )
            } else {
                null
            }
        return copy(
            selectedSubtitleStreamIndex = streamIndex,
            subtitleAsset = plannedTrack?.externalResource,
            subtitleActivationTarget = target,
            plannedSubtitle =
                when {
                    plannedTrack != null -> plannedTrack.copy(activationTarget = target)
                    unavailable != null -> unavailable.copy(activationTarget = target)
                    else -> plannedSubtitle
                },
        )
    }

    private fun PlannedSubtitle.Track.localKind(): LocalSubtitleKind? =
        when (deliveryMethod) {
            SubtitleDeliveryMethod.External -> LocalSubtitleKind.ExternalText
            SubtitleDeliveryMethod.Hls -> LocalSubtitleKind.HlsText
            SubtitleDeliveryMethod.Embed ->
                if (kind == SubtitleKind.Bitmap) LocalSubtitleKind.EmbeddedBitmap else LocalSubtitleKind.EmbeddedText
            else -> null
        }

    private fun isWithinTranscodedWindow(
        targetPositionMs: Long,
        currentPlan: PlaybackPlan,
    ): Boolean {
        val bufferedAheadMs = playerController.playbackState.value.bufferedPositionMs
        return targetPositionMs in currentPlan.startPositionMs..bufferedAheadMs
    }

    // Auto-skip fires only while Playing, once per segment identity per item
    // (the key set clears on startItem, surviving same-item replans), and is
    // ordered after coordinator state handling so its seek TimeUpdate
    // serializes behind the session Start in the reporting queue.
    private fun maybeAutoSkipSegment(playbackState: PlaybackState) {
        if (playbackState.status != PlaybackStatus.Playing) return
        val currentPlan = plan ?: return
        val segment =
            currentPlan.mediaSegments.firstOrNull { candidate ->
                playbackState.positionMs in candidate.startMs until candidate.endMs
            } ?: return
        if (preferences.policyFor(segment.type) != SegmentSkipPolicy.AutoSkip) return
        val key = "${segment.type.name}:${segment.startTicks}:${segment.endTicks}"
        if (!autoSkippedSegmentKeys.add(key)) return
        seekTo(segment.endMs)
    }

    private fun confirmedAudioStreamIndex(playbackState: PlaybackState): Int? {
        val activeTarget = (playbackState.audioActivation as? AudioActivationState.Active)?.target
        if (activeTarget != null && activeTarget.itemId == currentItemId) {
            return activeTarget.streamIndex
        }
        val currentPlan = plan ?: return null
        return currentPlan.selectedAudioStreamIndex.takeIf { currentPlan.audioSelectionAuthoritative }
    }

    private fun confirmedSubtitleStreamIndex(playbackState: PlaybackState): Int? {
        val activeIdentity = (playbackState.subtitleActivation as? SubtitleActivationState.Active)?.target
        if (activeIdentity != null && activeIdentity.itemId == currentItemId) {
            return (activeIdentity.identity as? SubtitleActivationIdentity.JellyfinTrack)?.streamIndex
        }
        // Server-rendered (Encode) subtitles are burned in — authoritative.
        val plannedTrack = plan?.plannedSubtitle as? PlannedSubtitle.Track ?: return null
        return plannedTrack.streamIndex.takeIf { plannedTrack.deliveryMethod == SubtitleDeliveryMethod.Encode }
    }

    private fun currentSourceBitrate(): Long? =
        mediaStreams
            .firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
            ?.bitRate

    private fun activeSegment(playbackState: PlaybackState): TvActiveSegment? {
        val currentPlan = plan ?: return null
        val segment =
            currentPlan.mediaSegments.firstOrNull { candidate ->
                playbackState.positionMs in candidate.startMs until candidate.endMs
            } ?: return null
        val policy = preferences.policyFor(segment.type)
        if (policy == SegmentSkipPolicy.Ignore) return null
        return TvActiveSegment(
            type = segment.type,
            endMs = segment.endMs,
            askUser = policy == SegmentSkipPolicy.Ask,
        )
    }

    private fun logTvSessionRecoveryDecision(decision: PlaybackSessionRecoveryDecision) {
        val requestPolicy =
            when (decision) {
                is PlaybackSessionRecoveryDecision.NetworkRetry -> decision.requestPolicy
                is PlaybackSessionRecoveryDecision.AudioActivationRecovery -> decision.requestPolicy
                is PlaybackSessionRecoveryDecision.SubtitleEncodeRecovery -> decision.requestPolicy
                PlaybackSessionRecoveryDecision.NoAction,
                is PlaybackSessionRecoveryDecision.AutomaticRecovery,
                is PlaybackSessionRecoveryDecision.SubtitleUnavailable,
                -> null
            }
        tvPlaybackDiagnosticLogger.i(
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.RecoveryDecision,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = playerController.activeBackend,
                    prepareSequence = playerController.runtimeDiagnostics.value.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    requestPolicy = requestPolicy?.diagnosticClass(),
                    clientTrigger = requestPolicy?.clientTrigger,
                    recoveryIntent = requestPolicy?.recoveryIntent,
                ),
            ),
        )
    }

    private fun logTvAutomaticRecoveryDecision(
        trigger: AutoPlaybackRecoveryTrigger,
        decision: AutoPlaybackRecoveryDecision,
    ) {
        val recoveryDecision =
            when (decision) {
                AutoPlaybackRecoveryDecision.NoAction -> PlaybackRecoveryDecision.NoAction
                is AutoPlaybackRecoveryDecision.CompatibilityReplan -> PlaybackRecoveryDecision.CompatibilityReplan
                is AutoPlaybackRecoveryDecision.LowerTo -> PlaybackRecoveryDecision.LowerQuality
                is AutoPlaybackRecoveryDecision.PromptUser -> PlaybackRecoveryDecision.PromptUser
            }
        tvPlaybackDiagnosticLogger.i(
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.RecoveryDecision,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = playerController.activeBackend,
                    prepareSequence = playerController.runtimeDiagnostics.value.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    autoRecoveryTrigger = trigger,
                    recoveryDecision = recoveryDecision,
                    recoveryPromptReason = (decision as? AutoPlaybackRecoveryDecision.PromptUser)?.reason,
                ),
            ),
        )
    }

    private fun logTvDiagnostic(
        stage: PlaybackDiagnosticStage,
        event: PlaybackDiagnosticEvent,
        exception: Throwable? = null,
        error: PlaybackError? = null,
        terminalOutcome: PlaybackTerminalOutcome? = null,
    ) {
        val controller = installedPlayerController ?: return
        val runtime = controller.runtimeDiagnostics.value
        val terminalSnapshot =
            terminalOutcome?.let {
                TvTerminalDiagnosticSnapshot(
                    sessionSequence = playbackHealthGeneration,
                    prepareSequence = runtime.prepareEpoch,
                    outcome = it,
                    error = error,
                )
            }
        if (terminalSnapshot != null && lastTerminalDiagnostic == terminalSnapshot) return
        if (terminalSnapshot != null) lastTerminalDiagnostic = terminalSnapshot
        val formatted = {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = stage,
                    event = event,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = runtime.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    exceptionType = exception?.playbackExceptionType(),
                    errorCategory = error,
                    terminalOutcome = terminalOutcome,
                    allocatedBufferBytes = runtime.allocatedBufferBytes.takeIf { terminalOutcome != null },
                    bufferedAheadMs = runtime.bufferedAheadMs.takeIf { terminalOutcome != null },
                    libVlcCachePercent = runtime.libVlcCachePercent.takeIf { terminalOutcome != null },
                ),
            )
        }
        if (terminalOutcome != null || event == PlaybackDiagnosticEvent.Failed) {
            tvPlaybackDiagnosticLogger.w(formatted())
        } else {
            tvPlaybackDiagnosticLogger.i(formatted())
        }
    }

    private fun logTvHealthSignal(signal: PlaybackHealthSignalKind) {
        val controller = installedPlayerController ?: return
        val runtime = controller.runtimeDiagnostics.value
        tvPlaybackDiagnosticLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSignal,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = runtime.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    healthSignal = signal,
                    allocatedBufferBytes = runtime.allocatedBufferBytes,
                    bufferedAheadMs = runtime.bufferedAheadMs,
                    libVlcCachePercent = runtime.libVlcCachePercent,
                ),
            )
        }
    }

    private fun logTvHealthSummary(summary: PlaybackHealthSummary) {
        val controller = installedPlayerController ?: return
        val runtime = controller.runtimeDiagnostics.value
        tvPlaybackDiagnosticLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSummary,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = runtime.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    allocatedBufferBytes = runtime.allocatedBufferBytes,
                    bufferedAheadMs = runtime.bufferedAheadMs,
                    libVlcCachePercent = runtime.libVlcCachePercent,
                    nativePrepareToFirstFrameMs = runtime.nativePrepareToFirstFrameMs,
                    rebufferCount = runtime.rebufferCount,
                    totalRebufferMs = runtime.totalRebufferMs,
                    maxRebufferMs = runtime.maxRebufferMs,
                    audioUnderrunCount = runtime.audioUnderrunCount,
                    maxAudioFeedGapMs = runtime.maxAudioFeedGapMs,
                    healthFirstPlayingObserved = summary.firstPlayingObserved,
                    healthBufferingDurationMs = summary.bufferingDurationMs,
                    healthBufferingIntervalCount = summary.bufferingIntervalCount,
                    healthStallCount = summary.stallCount,
                    healthDroppedFrameDurationMs = summary.droppedFrameDurationMs,
                    healthDroppedFrameSampleCount = summary.droppedFrameSampleCount,
                    healthGuidancePolicy = summary.guidancePolicy,
                    healthGuidancePublishable = summary.guidancePublishable,
                    healthBufferingIntervalOpenedSinceEvidenceRestart =
                        summary.bufferingIntervalOpenedSinceEvidenceRestart,
                    backendDroppedVideoFrames = runtime.droppedVideoFrames,
                    backendDroppedVideoFramesPerSecond = runtime.droppedVideoFramesPerSecond,
                    decoderDroppedVideoFrames = runtime.decoderDroppedVideoFrames,
                    outputDroppedVideoFrames = runtime.outputDroppedVideoFrames,
                    healthEmittedSignalCount = summary.emittedSignals.size,
                ),
            )
        }
    }

    private fun logTvVideoOutput(observed: Boolean) {
        val controller = installedPlayerController ?: return
        val runtime = controller.runtimeDiagnostics.value
        tvPlaybackDiagnosticLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.VideoOutput,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = runtime.prepareEpoch,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = plan?.streamMode,
                    firstVideoOutputAvailable = true,
                    firstVideoOutputObserved = observed,
                ),
            )
        }
    }

    private fun recordControllerFailure() {
        val diagnosticsContext = playbackDiagnosticsContext ?: return
        val failureKey =
            TvControllerFailureKey(
                sessionSequence = playbackHealthGeneration,
                prepareSequence = playerController.runtimeDiagnostics.value.prepareEpoch,
            )
        if (controllerFailureRecordedKey == failureKey) return
        controllerFailureRecordedKey = failureKey
        scope.launch(dispatchers.work) {
            val capabilities =
                runCatching { deviceProfileProvider?.capabilities(playerController.activeBackend) }.getOrNull()
            diagnosticsContext.recordFailure(
                backend = playerController.activeBackend,
                capabilities = capabilities,
                source =
                    mediaStreams.toDiagnosticsSourceDescriptor(
                        container = plan?.container,
                        selectedAudioStreamIndex = confirmedAudioStreamIndex(playerController.playbackState.value),
                    ),
            )
        }
    }

    private fun logTvTrackDiagnostics(playbackState: PlaybackState) {
        val currentPlan = plan ?: return
        val runtime = playerController.runtimeDiagnostics.value
        val confirmedAudio = confirmedAudioStreamIndex(playbackState)
        val requestedAudio = requestedAudioStreamIndex
        logTvTrackDiagnostic(
            snapshot =
                TvTrackDiagnosticSnapshot(
                    kind = PlaybackDiagnosticTrackKind.Audio,
                    requestedState = requestedAudio.toDiagnosticTrackState(),
                    confirmedState = confirmedAudio.toDiagnosticTrackState(),
                    matchesRequest = requestedAudio == confirmedAudio,
                    activation = playbackState.audioActivation.toDiagnosticTrackActivation(),
                ),
            currentPlan = currentPlan,
            prepareSequence = runtime.prepareEpoch,
        )

        val renderInfo =
            subtitleRenderInfo(
                options = subtitleOptions(mediaStreams),
                plannedSubtitle = currentPlan.plannedSubtitle,
                activationState = playbackState.subtitleActivation,
            )
        val requestedSubtitle =
            requestedSubtitleStreamIndex != null ||
                requestedSubtitleSelection is SubtitleSelectionIntent.Track ||
                requestedSubtitleSelection is SubtitleSelectionIntent.LocalAsset
        val confirmedSubtitle = confirmedSubtitleStreamIndex(playbackState)
        val confirmedSubtitleSelected =
            confirmedSubtitle != null || renderInfo.status == com.jellyscope.core.domain.playback.SubtitleRenderStatus.Active
        val matchesSubtitle =
            when {
                !requestedSubtitle && !confirmedSubtitleSelected -> true
                !requestedSubtitle || !confirmedSubtitleSelected -> false
                requestedSubtitleStreamIndex != null && confirmedSubtitle != null ->
                    requestedSubtitleStreamIndex == confirmedSubtitle

                else -> renderInfo.status == com.jellyscope.core.domain.playback.SubtitleRenderStatus.Active
            }
        logTvTrackDiagnostic(
            snapshot =
                TvTrackDiagnosticSnapshot(
                    kind = PlaybackDiagnosticTrackKind.Subtitle,
                    requestedState = requestedSubtitle.toDiagnosticTrackState(),
                    confirmedState = confirmedSubtitleSelected.toDiagnosticTrackState(),
                    matchesRequest = matchesSubtitle,
                    activation = playbackState.subtitleActivation.toDiagnosticTrackActivation(),
                    subtitleRenderMode = renderInfo.mode,
                    subtitleRenderStatus = renderInfo.status,
                    subtitleStyleable = renderInfo.styleable && playerController.appliesSubtitleStyle,
                ),
            currentPlan = currentPlan,
            prepareSequence = runtime.prepareEpoch,
        )
    }

    private fun logTvTrackDiagnostic(
        snapshot: TvTrackDiagnosticSnapshot,
        currentPlan: PlaybackPlan,
        prepareSequence: Long?,
    ) {
        val correlatedSnapshot =
            snapshot.copy(
                sessionSequence = playbackHealthGeneration,
                prepareSequence = prepareSequence,
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
        tvPlaybackDiagnosticLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Render,
                    event = PlaybackDiagnosticEvent.TrackState,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = playerController.activeBackend,
                    prepareSequence = prepareSequence,
                    sessionSequence = playbackHealthGeneration,
                    streamMode = currentPlan.streamMode,
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

    // Completed is a terminal-only phase decided by handleCompleted; a stale
    // controller Completed sample seen right after a queue advance or replan
    // renders as Loading so the Swift dismiss handler can never race it.
    private fun publish(
        playbackState: PlaybackState,
        terminalCompleted: Boolean = false,
    ) {
        logTvTrackDiagnostics(playbackState)
        val confirmedAudio = confirmedAudioStreamIndex(playbackState)
        val confirmedSubtitle = confirmedSubtitleStreamIndex(playbackState)
        val duration = playbackState.durationMs
        val inheritedQualityPolicy = preferences.effectiveDefaultQualityPolicy(playerController.activeBackend)
        _state.update {
            TvPlaybackUiState(
                playerInstalled = installedPlayerController != null,
                phase =
                    when (playbackState.status) {
                        PlaybackStatus.Failed -> TvPlaybackPhase.Failed
                        PlaybackStatus.Completed ->
                            if (terminalCompleted) TvPlaybackPhase.Completed else TvPlaybackPhase.Loading
                        PlaybackStatus.Idle, PlaybackStatus.Loading -> TvPlaybackPhase.Loading
                        else -> TvPlaybackPhase.Active
                    },
                status = playbackState.status,
                positionMs = playbackState.positionMs,
                durationMs = duration,
                bufferedPositionMs = playbackState.bufferedPositionMs,
                isTranscode = plan?.streamMode == StreamMode.Transcode,
                itemId = currentItemId,
                planEpoch = planEpoch,
                prepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch ?: 0L,
                title = title,
                seriesName = seriesName,
                episodeLabel = episodeLabel,
                artworkUrl = artworkUrl,
                chapters = plan?.chapters.orEmpty().map { chapter -> TvChapter(chapter.name, chapter.startMs) },
                audioTracks =
                    audioOptions(mediaStreams).map { option ->
                        TvTrackChoice(
                            streamIndex = option.streamIndex,
                            languageCode = option.language,
                            displayLabel = option.displayName?.takeIf(String::isNotBlank),
                            ordinal = option.ordinal,
                            selected = option.streamIndex == confirmedAudio,
                        )
                    },
                subtitleTracks =
                    subtitleOptions(mediaStreams).map { option ->
                        TvTrackChoice(
                            streamIndex = option.streamIndex,
                            languageCode = option.language,
                            displayLabel = option.displayName?.takeIf(String::isNotBlank),
                            ordinal = option.ordinal,
                            selected = option.streamIndex == confirmedSubtitle,
                        )
                    },
                qualityChoices =
                    playerQualityOptions(currentSourceBitrate(), selectedQualityPolicy).map { option ->
                        TvQualityChoice(
                            maxBitrateBps = option.maxBitrateBps,
                            resolutionHeight = option.resolutionHeight,
                            isCustom = option.isCustom,
                            selected =
                                if (option.inheritsDefault) {
                                    !qualityOverrideExplicit
                                } else {
                                    qualityOverrideExplicit &&
                                        option.mode == selectedQualityPolicy.mode &&
                                        option.maxBitrateBps == selectedQualityPolicy.maxBitrateBps
                                },
                            inheritsPlaybackDefault = option.inheritsDefault,
                            defaultSource =
                                if (option.inheritsDefault) {
                                    if (preferences.usesVlcDefaultQuality(playerController.activeBackend)) {
                                        TvQualityDefaultSource.VlcPlaybackSettings
                                    } else {
                                        TvQualityDefaultSource.PlaybackSettings
                                    }
                                } else {
                                    null
                                },
                            mode = option.mode,
                        )
                    },
                inheritedQualityPolicy = inheritedQualityPolicy,
                inheritedQualityResolutionHeight =
                    qualityRungForBitrate(inheritedQualityPolicy.maxBitrateBps)?.height,
                inheritedQualityUsesVlcSetting = preferences.usesVlcDefaultQuality(playerController.activeBackend),
                activeSegment = activeSegment(playbackState),
                nextEpisode = nextEpisodeItem?.toTvMediaCard(session, imageUrlBuilder),
                upNextVisible =
                    nextEpisodeItem != null &&
                        duration != null &&
                        duration - playbackState.positionMs <= UP_NEXT_WINDOW_MS,
                playbackGuidance = playbackHealthCoordinator.guidance,
                playbackActionNotice = playbackActionNotice,
                playbackActions = playbackActionNotice?.actions.orEmpty().toList(),
                error = playbackState.error,
            )
        }
    }

    private fun publishFailure(error: PlaybackError) {
        logTvDiagnostic(
            stage = PlaybackDiagnosticStage.NativePlayer,
            event = PlaybackDiagnosticEvent.TerminalError,
            terminalOutcome = PlaybackTerminalOutcome.Failed,
            error = error,
        )
        _state.update { current ->
            current.copy(
                phase = TvPlaybackPhase.Failed,
                error = error,
            )
        }
    }
}

private data class TvTrackDiagnosticSnapshot(
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

private data class TvTerminalDiagnosticSnapshot(
    val sessionSequence: Long,
    val prepareSequence: Long?,
    val outcome: PlaybackTerminalOutcome,
    val error: PlaybackError?,
)

private data class TvControllerFailureKey(
    val sessionSequence: Long,
    val prepareSequence: Long?,
)

private class TvControllerCandidateOwner {
    private var controller: PlayerController? = null

    fun acquire(candidate: PlayerController): PlayerController {
        check(controller == null) { "Controller candidate is already owned." }
        controller = candidate
        return candidate
    }

    fun transferTo(block: (PlayerController) -> Unit) {
        val candidate = checkNotNull(controller) { "Controller candidate is not owned." }
        block(candidate)
        controller = null
    }

    fun releaseUntransferred() {
        val candidate = controller
        controller = null
        candidate?.release()
    }
}

private enum class TvSessionRecoveryResult {
    NotHandled,
    RetryScheduled,
    Failed,
    NonTerminal,
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

private fun MediaItemDetail.selectedVersion(requestedMediaSourceId: String?): MediaVersion? =
    requestedMediaSourceId
        ?.takeIf { id -> id.isNotBlank() }
        ?.let { id -> versions.firstOrNull { version -> version.id == id } }
        ?: versions.firstOrNull { version -> version.id.isNotBlank() }

private val localEmbeddedDeliveryMethods =
    setOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Hls)

private val tvosMonotonicOrigin = TimeSource.Monotonic.markNow()

private fun tvosMonotonicTimeMs(): Long = tvosMonotonicOrigin.elapsedNow().inWholeMilliseconds

private const val VERIFIED_END_TOLERANCE_MS = 1_500L
private const val UP_NEXT_WINDOW_MS = 30_000L
