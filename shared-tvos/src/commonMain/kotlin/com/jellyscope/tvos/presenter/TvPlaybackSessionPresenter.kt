// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
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
import com.jellyscope.core.domain.playback.PlayerStillWatchingAutomaticAdvance
import com.jellyscope.core.domain.playback.PlayerStillWatchingState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.SubtitleStyle
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
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Playback session for the tvOS system player: plan -> prepare -> play with
 * shared recovery, ordered reporting, track and quality selection, subtitle
 * intent, segment skipping, and chronological episode advancement. Completed
 * publishes only for terminal completion; mid-queue advancement transitions
 * straight to Loading.
 */
class TvPlaybackSessionPresenter(
    private val session: Session,
    val startWithPlaybackInfoOverlay: Boolean,
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
    private val initialAudioStreamIndex: Int? = null,
    private val initialSubtitleMode: TvPlaybackSubtitleMode = TvPlaybackSubtitleMode.Unspecified,
    private val initialSubtitleStreamIndex: Int? = null,
    private val monotonicTimeMs: () -> Long = ::tvosMonotonicTimeMs,
    private val getLocalSubtitleAsset: GetLocalSubtitleAssetUseCase? = null,
    private val observeLocalSubtitleAssets: ObserveLocalSubtitleAssetsUseCase? = null,
    private val initialSubtitleAssetId: String? = null,
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
    private var currentStartMediaSourceId: String? = requestedMediaSourceId
    private var currentStartPositionTicks: Long = initialStartPositionTicks
    private var currentStartResetsReporting = false
    private val originalItemId: String = initialItemId
    private var initialRouteIntentConsumed = false
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
    private var requestedLocalSubtitleAsset: LocalSubtitleAsset? = null
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
    private var subtitleNotice: TvPlaybackNotice? = null
    private var subtitleNoticeToken = 0L
    private var playbackSpeed = 1f
    private var subtitleStyle = SubtitleStyle()

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
    private var queuePresentation = TvPlaybackQueueState()
    private var nextEpisodePresentation: TvMediaCard? = null
    private var queueLoadPending = false
    private var queueShuffled = false
    private var queueOrderGeneration = 0L
    private var advanceInFlight = false
    private var itemSwitchGeneration = 0L
    private var stillWatchingState = PlayerStillWatchingState()
    private var autoplayIdentity: TvAutoplayIdentity? = null
    private var autoplayCountdownSeconds: Int? = null
    private var autoplayDismissed = false
    private var handledCompletion: TvAutoplayIdentity? = null
    private var pendingQueueCompletion: TvPendingQueueCompletion? = null
    private var settledCompletion: TvAutoplayIdentity? = null
    private var completedWithNext = false
    private var playerIdentity = 0L

    private var started = false
    private var startInFlight = false
    private var closed = false
    private var startupGeneration = 0L
    private var startupJob: Job? = null
    private var observeJob: Job? = null
    private var videoOutputJob: Job? = null
    private var replanJob: Job? = null
    private var queueJob: Job? = null
    private var itemSwitchJob: Job? = null
    private var autoplayJob: Job? = null
    private var localSubtitleAssetsJob: Job? = null
    private var localSubtitleSelectionJob: Job? = null
    private var diagnosticsJob: Job? = null

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
        _state.update { current ->
            current.copy(
                phase = TvPlaybackPhase.Loading,
                error = null,
            )
        }
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
                            playerIdentity += 1L
                            installedPlaybackReportingCoordinator =
                                PlaybackReportingCoordinator(
                                    queue = reportingQueue,
                                    scope = scope,
                                    playbackState = installed.playbackState,
                                )
                            observeControllerOutput()
                            _state.update { current ->
                                current.copy(
                                    playerInstalled = true,
                                    playerIdentity = playerIdentity,
                                    backend = installed.activeBackend,
                                )
                            }
                        } catch (exception: Throwable) {
                            videoOutputJob?.cancel()
                            videoOutputJob = null
                            installedPlaybackReportingCoordinator = null
                            installedPlayerController = null
                            throw exception
                        }
                    }
                    if (
                        startItem(
                            itemId = currentItemId,
                            mediaSourceId = currentStartMediaSourceId,
                            startPositionTicks = currentStartPositionTicks,
                            resetReporting = currentStartResetsReporting,
                        )
                    ) {
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
        val controller = installedPlayerController ?: return
        if (plan?.itemId != currentItemId) return
        playbackHealthCoordinator.restartEvidenceWindow()
        controller.play()
    }

    fun pause() {
        val controller = installedPlayerController ?: return
        if (plan?.itemId != currentItemId) return
        playbackHealthCoordinator.restartEvidenceWindow()
        controller.pause()
    }

    fun togglePlayPause() {
        val controller = installedPlayerController ?: return
        if (plan?.itemId != currentItemId) return
        when (controller.playbackState.value.status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> pause()
            PlaybackStatus.Paused, PlaybackStatus.Loading -> play()
            else -> Unit
        }
    }

    fun seekTo(positionMs: Long) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        val target = positionMs.coerceAtLeast(0L)
        playbackHealthCoordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
        val currentPlan = plan
        if (
            controller.transcodeSeekRestartsStream &&
            currentPlan?.streamMode == StreamMode.Transcode &&
            !isWithinTranscodedWindow(target, currentPlan)
        ) {
            // Out-of-window transcode seeks wedge AVPlayer; restart the
            // transcode at the target instead (same contract as PlayerViewModel).
            replanAtPosition(target)
            return
        }
        controller.seekTo(target)
        installedPlaybackReportingCoordinator?.progress(
            positionMs = target,
            isPaused = controller.playbackState.value.status == PlaybackStatus.Paused,
            eventName = PlaybackProgressEvent.TimeUpdate,
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        if (startInFlight || !speed.isFinite()) return
        val controller = installedPlayerController ?: return
        val normalized =
            speed.coerceIn(
                com.jellyscope.core.domain.playback.MIN_PLAYBACK_SPEED,
                com.jellyscope.core.domain.playback.MAX_PLAYBACK_SPEED,
            )
        if (normalized !in tvPlaybackSpeedValues) return
        playbackSpeed = normalized
        plan = plan?.copy(playbackSpeed = normalized)
        controller.setPlaybackSpeed(normalized)
        publish(controller.playbackState.value.copy(playbackSpeed = normalized))
    }

    fun setSubtitleStyle(
        fontScale: Float,
        foregroundColor: String?,
        backgroundColor: String?,
        edgeStyleName: String,
    ) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        if (!canApplySubtitleStyle()) return
        subtitleStyle =
            SubtitleStyle(
                fontScale = fontScale.coerceIn(0.5f, 2f),
                foregroundColor = foregroundColor?.takeIf(String::isNotBlank),
                backgroundColor = backgroundColor?.takeIf(String::isNotBlank),
                edgeStyle = subtitleEdgeStyle(edgeStyleName),
            )
        plan = plan?.copy(subtitleStyle = subtitleStyle)
        controller.setSubtitleStyle(subtitleStyle)
        publish(controller.playbackState.value.copy(subtitleStyle = subtitleStyle))
    }

    fun setDiagnosticsVisible(visible: Boolean) {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        if (!visible) {
            _state.update { current -> current.copy(diagnostics = emptyList()) }
            return
        }
        val controller = installedPlayerController ?: return
        diagnosticsJob =
            scope.launch {
                controller.runtimeDiagnostics.collect { runtime ->
                    _state.update { current ->
                        current.copy(
                            diagnostics =
                                tvPlaybackDiagnosticRows(
                                    backend = controller.activeBackend,
                                    plan = plan,
                                    playbackState = controller.playbackState.value,
                                    runtime = runtime,
                                ),
                        )
                    }
                }
            }
    }

    fun dismissSubtitleNotice() {
        subtitleNotice = null
        installedPlayerController?.playbackState?.value?.let(::publish)
    }

    fun stop() {
        val controller = installedPlayerController ?: return
        cancelAutoplaySurface()
        cancelAndInvalidateSubtitleFallback()
        playbackHealthCoordinator.end()
        installedPlaybackReportingCoordinator?.stop(controller.playbackState.value.positionMs)
        controller.stop()
    }

    fun skipActiveSegment() {
        val segment = state.value.activeSegment ?: return
        seekTo(segment.endMs)
    }

    fun playNextEpisode() {
        val index = currentQueueIndex() + 1
        selectQueueItem(index)
    }

    fun playPreviousEpisode() {
        val index = currentQueueIndex() - 1
        selectQueueItem(index)
    }

    fun selectQueueItem(index: Int) {
        val selected = episodeQueue?.getOrNull(index) ?: return
        if (selected.id == currentItemId) return
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        cancelAutoplaySurface()
        autoplayDismissed = false
        switchToQueueIndex(index = index, stopAlreadyReported = false)
    }

    fun shuffleQueue() {
        val controller = installedPlayerController ?: return
        val queue = episodeQueue ?: return
        val currentIndex = currentQueueIndex()
        if (currentIndex !in queue.indices || queue.size < 2) return
        val expectedItemId = currentItemId
        val expectedLaunchGeneration = playbackHealthGeneration
        val expectedQueueGeneration = ++queueOrderGeneration
        queueJob?.cancel()
        queueJob =
            scope.launch {
                val (shuffled, presentation) =
                    withContext(dispatchers.work) {
                        val current = queue[currentIndex]
                        val reordered =
                            queue
                                .filterIndexed { index, _ -> index != currentIndex }
                                .shuffled()
                                .toMutableList()
                                .apply { add(currentIndex, current) }
                        reordered to
                            playbackQueueState(
                                queue = reordered,
                                currentItemId = expectedItemId,
                                shuffled = true,
                                session = session,
                                imageUrlBuilder = imageUrlBuilder,
                            )
                    }
                if (
                    closed ||
                    episodeQueue !== queue ||
                    currentItemId != expectedItemId ||
                    playbackHealthGeneration != expectedLaunchGeneration ||
                    queueOrderGeneration != expectedQueueGeneration
                ) {
                    return@launch
                }
                queueJob = null
                episodeQueue = shuffled
                queueShuffled = true
                queuePresentation = presentation
                nextEpisodeItem = shuffled.getOrNull(currentIndex + 1)
                nextEpisodePresentation = presentation.items.getOrNull(currentIndex + 1)?.media
                stillWatchingState = stillWatchingState.resetForManualNavigation()
                cancelAutoplaySurface()
                autoplayDismissed = false
                settledCompletion = null
                val playbackState = controller.playbackState.value
                if (playbackState.status == PlaybackStatus.Completed) {
                    handleCompleted(playbackState)
                } else {
                    publish(playbackState, retainCompletedHost = completedWithNext)
                }
            }
    }

    fun dismissNextUp() {
        autoplayDismissed = true
        cancelAutoplayCountdown()
        installedPlayerController?.playbackState?.value?.let { playbackState ->
            publish(playbackState, retainCompletedHost = completedWithNext)
        }
    }

    fun continueStillWatching() {
        val pendingIndex = stillWatchingState.pendingQueueIndex ?: return
        stillWatchingState = stillWatchingState.confirm()
        cancelAutoplaySurface()
        switchToQueueIndex(index = pendingIndex, stopAlreadyReported = true)
    }

    fun dismissStillWatching() {
        stillWatchingState = stillWatchingState.confirm()
        autoplayDismissed = true
        cancelAutoplayCountdown()
        installedPlayerController?.playbackState?.value?.let { playbackState ->
            publish(playbackState, retainCompletedHost = completedWithNext)
        }
    }

    private fun currentQueueIndex(): Int = episodeQueue.orEmpty().indexOfFirst { item -> item.id == currentItemId }

    private fun switchToQueueIndex(
        index: Int,
        stopAlreadyReported: Boolean,
    ) {
        val controller = installedPlayerController ?: return
        val reportingCoordinator = installedPlaybackReportingCoordinator ?: return
        val next = episodeQueue?.getOrNull(index) ?: return
        if (next.id == currentItemId) return
        val expectedSwitch = ++itemSwitchGeneration
        itemSwitchJob?.cancel()
        replanJob?.cancel()
        invalidateSubtitleFallback()
        val outgoingAlreadySettled = advanceInFlight || lastStatus == PlaybackStatus.Completed
        advanceInFlight = true
        val outgoingPositionMs = controller.playbackState.value.positionMs
        controller.stop()
        if (!stopAlreadyReported && !outgoingAlreadySettled) {
            reportingCoordinator.stop(outgoingPositionMs)
        }
        itemSwitchJob =
            scope.launch {
                try {
                    startItem(
                        itemId = next.id,
                        mediaSourceId = null,
                        startPositionTicks = next.playbackPositionTicks ?: 0L,
                        resetReporting = true,
                    )
                } finally {
                    if (expectedSwitch == itemSwitchGeneration) {
                        advanceInFlight = false
                        itemSwitchJob = null
                    }
                }
            }
    }

    private fun cancelAutoplayCountdown() {
        autoplayJob?.cancel()
        autoplayJob = null
        autoplayIdentity = null
        autoplayCountdownSeconds = null
    }

    private fun cancelAutoplaySurface() {
        cancelAutoplayCountdown()
        stillWatchingState =
            if (stillWatchingState.isPromptVisible) {
                stillWatchingState.confirm()
            } else {
                stillWatchingState
            }
    }

    fun selectAudio(streamIndex: Int) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
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
                    controller.playbackState.value.copy(
                        audioActivation = AudioActivationState.Unavailable(target),
                    ),
                )
            } else {
                controller.selectEmbeddedAudio(
                    EmbeddedAudioSelection(target = target, descriptor = descriptor),
                )
            }
            publish(controller.playbackState.value)
        } else {
            replanAtPosition(controller.playbackState.value.positionMs)
        }
    }

    fun selectSubtitle(streamIndex: Int?) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        val option =
            streamIndex?.let { selectedIndex ->
                subtitleOptions(mediaStreams).firstOrNull { track -> track.streamIndex == selectedIndex }
                    ?: return
            }
        replanJob?.cancel()
        replanJob = null
        invalidateSubtitleFallback()
        requestedSubtitleStreamIndex = streamIndex
        requestedLocalSubtitleAsset = null
        subtitleNotice = null
        requestedSubtitleSelection =
            streamIndex?.let(SubtitleSelectionIntent::Track) ?: SubtitleSelectionIntent.Off
        _state.update { current -> current.copy(localSubtitleSelected = false) }
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
            controller.selectEmbeddedSubtitle(selection)
            publish(controller.playbackState.value)
        } else {
            if (option == null) {
                controller.selectEmbeddedSubtitle(null)
            }
            replanAtPosition(controller.playbackState.value.positionMs)
        }
    }

    fun selectLocalSubtitle(assetId: String?) {
        if (assetId == null) {
            selectSubtitle(null)
            return
        }
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank) ?: return
        val selectionGeneration = playbackHealthGeneration
        val itemId = currentItemId
        val context = LocalSubtitleContext(session.serverId, session.userId, itemId, sourceId)
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob =
            scope.launch {
                val asset =
                    withContext(dispatchers.work) {
                        getLocalSubtitleAsset?.invoke(assetId, context)
                    }
                if (
                    closed ||
                    selectionGeneration != playbackHealthGeneration ||
                    itemId != currentItemId ||
                    sourceId != selectedMediaSourceId ||
                    installedPlayerController !== controller
                ) {
                    return@launch
                }
                if (asset == null) {
                    markLocalSubtitleUnavailable()
                    return@launch
                }
                invalidateSubtitleFallback()
                replanJob?.cancel()
                requestedSubtitleStreamIndex = null
                requestedLocalSubtitleAsset = asset
                requestedSubtitleSelection = SubtitleSelectionIntent.LocalAsset(asset.id)
                _state.update { current -> current.copy(localSubtitleSelected = true) }
                subtitleNotice = null
                persistSubtitleSelection(requestedSubtitleSelection)
                controller.selectEmbeddedSubtitle(null)
                observeSelectedLocalSubtitle(context)
                replanAtPosition(controller.playbackState.value.positionMs)
            }
    }

    fun selectQuality(maxBitrateBps: Long?) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy =
            maxBitrateBps
                ?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
                ?: PlaybackQualityPolicy.Auto
        selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
        selectedQualityCapOrigin = maxBitrateBps?.let { PlaybackQualityCapOrigin.ExplicitSessionChoice }
        qualityInitialized = true
        qualityOverrideExplicit = true
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, controller.activeBackend)
        replanAtPosition(controller.playbackState.value.positionMs)
    }

    fun selectQuality(policy: PlaybackQualityPolicy) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        pendingRecoveredAutoQualityBps = null
        val normalized = policy.normalized()
        selectedQualityPolicy = normalized
        selectedQualityMaxBitrate = normalized.maxBitrateBps
        selectedQualityCapOrigin =
            PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { normalized.mode == PlaybackQualityMode.Fixed }
        qualityInitialized = true
        qualityOverrideExplicit = true
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, controller.activeBackend)
        playbackActionNotice = null
        replanAtPosition(controller.playbackState.value.positionMs)
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
        installedPlayerController?.recordVideoOutputObservation(generation = generation)
    }

    fun recordNativePlaybackIntent(
        playerIdentity: Long,
        planEpoch: Long,
        itemId: String,
        isPlaying: Boolean,
    ) {
        if (closed || startInFlight) return
        val controller = installedPlayerController ?: return
        if (
            playerIdentity != this.playerIdentity ||
            planEpoch != this.planEpoch ||
            itemId != currentItemId ||
            plan?.itemId != currentItemId
        ) {
            return
        }
        val prepareEpoch = controller.runtimeDiagnostics.value.prepareEpoch ?: return
        if (controller.recordHostPlaybackIntent(prepareEpoch, isPlaying)) {
            playbackHealthCoordinator.restartEvidenceWindow()
        }
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
                installedPlayerController?.playbackState?.value?.let(::publish)
            }
            PlaybackAction.Retry -> retryPlayback()
            PlaybackAction.Dismiss -> {
                pendingRecoveredAutoQualityBps = null
                if (playbackActionNotice != null) {
                    playbackActionNotice = null
                } else {
                    playbackHealthCoordinator.dismissGuidance()
                }
                installedPlayerController?.playbackState?.value?.let(::publish)
            }
            PlaybackAction.OpenPlaybackSettings,
            PlaybackAction.Close,
            -> Unit
        }
    }

    private fun retryPlayback() {
        if (closed || startInFlight) return
        _state.update { current ->
            current.copy(
                phase = TvPlaybackPhase.Loading,
                error = null,
            )
        }
        val controller = installedPlayerController
        if (controller == null) {
            startupJob?.cancel()
            startupJob = null
            started = false
            start()
            return
        }
        val currentPlan = plan
        if (currentPlan == null || currentPlan.itemId != currentItemId) {
            val expectedGeneration = ++startupGeneration
            startupJob?.cancel()
            startupJob =
                scope.launch {
                    try {
                        if (closed || expectedGeneration != startupGeneration) return@launch
                        if (
                            startItem(
                                itemId = currentItemId,
                                mediaSourceId = currentStartMediaSourceId,
                                startPositionTicks = currentStartPositionTicks,
                                resetReporting = currentStartResetsReporting,
                            )
                        ) {
                            observePlaybackState()
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Throwable) {
                        if (!closed && expectedGeneration == startupGeneration) {
                            publishFailure(PlaybackError.Unknown)
                        }
                    }
                }
            return
        }
        val reportingCoordinator = installedPlaybackReportingCoordinator ?: return
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, currentItemId)
        playSessionId = deviceInfoProvider.newDeviceId()
        lastStatus = PlaybackStatus.Idle
        reportingCoordinator.install(
            session = session,
            plan = currentPlan,
            playSessionId = currentPlan.playSessionId ?: playSessionId,
        )
        controller.retry()
    }

    private fun clearQualityOverride() {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy = preferences.effectiveDefaultQualityPolicy(controller.activeBackend)
        selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
        selectedQualityCapOrigin =
            PlaybackQualityCapOrigin.SettingsDefault.takeIf { selectedQualityPolicy.mode == PlaybackQualityMode.Fixed }
        qualityInitialized = true
        qualityOverrideExplicit = false
        playbackActionNotice = null
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, controller.activeBackend)
        replanAtPosition(controller.playbackState.value.positionMs)
    }

    private fun tryHigherQuality() {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        pendingRecoveredAutoQualityBps = null
        selectedQualityPolicy = PlaybackQualityPolicy.Auto
        selectedQualityMaxBitrate = null
        selectedQualityCapOrigin = null
        qualityInitialized = true
        qualityOverrideExplicit = true
        playbackActionNotice = null
        autoRecoveryState = autoRecoveryCoordinator.clearRuntimeQualityCap(autoRecoveryState)
        replanAtPosition(controller.playbackState.value.positionMs)
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
        itemSwitchGeneration += 1L
        itemSwitchJob?.cancel()
        itemSwitchJob = null
        autoplayJob?.cancel()
        autoplayJob = null
        localSubtitleAssetsJob?.cancel()
        localSubtitleAssetsJob = null
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
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
        currentStartMediaSourceId = mediaSourceId
        currentStartPositionTicks = startPositionTicks
        currentStartResetsReporting = resetReporting
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
        cancelAutoplaySurface()
        handledCompletion = null
        pendingQueueCompletion = null
        settledCompletion = null
        autoplayDismissed = false
        completedWithNext = false
        autoSkippedSegmentKeys.clear()
        audioRecoveryTarget = null
        pendingRecoveredPlaybackGuidance = false
        pendingRecoveredAutoQualityBps = null
        queueLoadPending = false
        val itemChanged = currentItemId != itemId
        if (itemChanged) {
            plan = null
            planEpoch += 1L
            selectedMediaSourceId = null
            selectedSourceContainer = null
        }
        currentItemId = itemId
        nextEpisodeItem = null
        nextEpisodePresentation = null
        if (episodeQueue?.none { item -> item.id == itemId } == true) {
            episodeQueue = null
            queueShuffled = false
            queueOrderGeneration += 1L
            queuePresentation = TvPlaybackQueueState()
        } else {
            episodeQueue?.let { retainedQueue ->
                queuePresentation =
                    withContext(dispatchers.work) {
                        playbackQueueState(
                            queue = retainedQueue,
                            currentItemId = itemId,
                            shuffled = queueShuffled,
                            session = session,
                            imageUrlBuilder = imageUrlBuilder,
                        )
                    }
                val currentIndex = queuePresentation.currentIndex
                nextEpisodeItem = retainedQueue.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                nextEpisodePresentation = queuePresentation.items.getOrNull(currentIndex + 1)?.media
            }
        }
        explicitAudioStreamIndex = null
        qualityInitialized = false
        qualityOverrideExplicit = false
        rememberedPlaybackSelection = null
        requestedLocalSubtitleAsset = null
        localSubtitleAssetsJob?.cancel()
        localSubtitleAssetsJob = null
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
                mediaSourceId = selectedMediaSourceId,
                planEpoch = planEpoch,
                activeSegment = null,
                nextEpisode = nextEpisodePresentation,
                upNextVisible = false,
                queue = queuePresentation,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                localSubtitleSelected = false,
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
        preferences = launchContext.playbackPreferences.normalized()
        if (!preferences.stillWatchingPrompt) {
            stillWatchingState = stillWatchingState.resetForDisabledPreference()
        }
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
        val initialRouteIntentApplies =
            !initialRouteIntentConsumed &&
                itemId == originalItemId &&
                (requestedMediaSourceId.isNullOrBlank() || requestedMediaSourceId == version.id) &&
                (mediaSourceId.isNullOrBlank() || mediaSourceId == version.id)
        val validInitialAudio =
            initialAudioStreamIndex?.takeIf { index ->
                initialRouteIntentApplies && audioTrackOptions.any { option -> option.streamIndex == index }
            }
        explicitAudioStreamIndex =
            validInitialAudio
                ?: rememberedPlaybackSelection?.audioStreamIndex?.takeIf { index ->
                    audioTrackOptions.any { option -> option.streamIndex == index }
                }
        requestedAudioStreamIndex =
            explicitAudioStreamIndex
                ?: audioTrackOptions.preferredAudioStreamIndex(preferences.preferredAudioLanguage)
                ?: audioTrackOptions.firstOrNull { option -> option.isDefault }?.streamIndex
                ?: audioTrackOptions.firstOrNull()?.streamIndex
        val subtitleOptions = subtitleOptions(mediaStreams)
        val explicitInitialSubtitle =
            when {
                !initialRouteIntentApplies -> SubtitleSelectionIntent.Unspecified
                initialSubtitleMode == TvPlaybackSubtitleMode.Off -> SubtitleSelectionIntent.Off
                initialSubtitleMode == TvPlaybackSubtitleMode.Track ->
                    initialSubtitleStreamIndex
                        ?.takeIf { index -> subtitleOptions.any { option -> option.streamIndex == index } }
                        ?.let(SubtitleSelectionIntent::Track)
                        ?: SubtitleSelectionIntent.Unspecified
                initialSubtitleMode == TvPlaybackSubtitleMode.LocalAsset ->
                    initialSubtitleAssetId
                        ?.takeIf(String::isNotBlank)
                        ?.let(SubtitleSelectionIntent::LocalAsset)
                        ?: SubtitleSelectionIntent.Off
                else -> SubtitleSelectionIntent.Unspecified
            }
        val subtitleResolution =
            withContext(dispatchers.work) {
                resolveSubtitleSelection(
                    explicit = explicitInitialSubtitle,
                    stored = launchContext.subtitleSelection,
                    key = subtitleSelectionKey(itemId, version.id),
                    options = subtitleOptions,
                )
            }
        requestedSubtitleSelection = subtitleResolution.selection
        requestedLocalSubtitleAsset = subtitleResolution.localAsset
        if (subtitleResolution.localAssetUnavailable) {
            publishLocalSubtitleUnavailableNotice()
        }
        requestedSubtitleStreamIndex =
            (requestedSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex
        if (validInitialAudio != null) {
            saveAudioSelection()
        }
        if (explicitInitialSubtitle != SubtitleSelectionIntent.Unspecified) {
            persistSubtitleSelection(requestedSubtitleSelection)
        }

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
                        localSubtitleAsset = requestedLocalSubtitleAsset?.toPlaybackAsset(),
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
        if (itemId == originalItemId && !initialRouteIntentConsumed) {
            initialRouteIntentConsumed = true
        }
        val enrichedPlan =
            playbackPlan
                .withAudioActivationTarget()
                .withSubtitleActivationTarget(
                    requestId = nextSubtitleActivationRequestId(),
                    selectedSubtitle = selectedSubtitleMediaStream(),
                ).copy(
                    chapters = detail.chapters,
                    mediaSegments = segments,
                    playbackSpeed = playbackSpeed,
                    subtitleStyle = subtitleStyle,
                )
        currentCoroutineContext().ensureActive()
        installPlan(enrichedPlan, resetReporting = resetReporting)
        deriveEpisodeQueueIfNeeded(detail.item)
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
        observeSelectedLocalSubtitle(context = LocalSubtitleContext(session.serverId, session.userId, itemId, version.id))
        return true
    }

    private fun deriveEpisodeQueueIfNeeded(item: MediaItem) {
        if (item.kind != MediaKind.Episode) {
            episodeQueue = null
            nextEpisodeItem = null
            nextEpisodePresentation = null
            queueLoadPending = false
            pendingQueueCompletion = null
            queueShuffled = false
            queueOrderGeneration += 1L
            queuePresentation = TvPlaybackQueueState()
            _state.update { current ->
                current.copy(
                    nextEpisode = null,
                    queue = queuePresentation,
                )
            }
            return
        }
        episodeQueue?.takeIf { queue -> queue.any { episode -> episode.id == currentItemId } }?.let { queue ->
            val currentIndex = queuePresentation.currentIndex
            nextEpisodeItem = queue.getOrNull(currentIndex + 1)
            nextEpisodePresentation = queuePresentation.items.getOrNull(currentIndex + 1)?.media
            queueLoadPending = false
            queuePresentation = queuePresentation.copy(isPending = false)
            _state.update { current ->
                current.copy(
                    nextEpisode = nextEpisodePresentation,
                    queue = queuePresentation,
                )
            }
            return
        }
        val expectedItemId = currentItemId
        val expectedLaunchGeneration = playbackHealthGeneration
        val expectedQueueGeneration = ++queueOrderGeneration
        queueLoadPending = true
        queueShuffled = false
        episodeQueue = null
        nextEpisodeItem = null
        nextEpisodePresentation = null
        queuePresentation = TvPlaybackQueueState(isPending = true)
        _state.update { current ->
            current.copy(
                nextEpisode = null,
                queue = queuePresentation,
            )
        }
        queueJob =
            scope.launch {
                val (queue, presentation) =
                    withContext(dispatchers.work) {
                        val loadedQueue =
                            try {
                                getChronologicalEpisodeQueue(item).getOrElse { emptyList() }
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        loadedQueue to
                            playbackQueueState(
                                queue = loadedQueue,
                                currentItemId = expectedItemId,
                                shuffled = false,
                                session = session,
                                imageUrlBuilder = imageUrlBuilder,
                            )
                    }
                if (
                    closed ||
                    currentItemId != expectedItemId ||
                    playbackHealthGeneration != expectedLaunchGeneration ||
                    queueOrderGeneration != expectedQueueGeneration
                ) {
                    return@launch
                }
                queueJob = null
                episodeQueue = queue
                queueShuffled = false
                queueLoadPending = false
                queuePresentation = presentation.copy(isPending = false)
                val currentIndex = queuePresentation.currentIndex
                nextEpisodeItem = queue.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                nextEpisodePresentation = queuePresentation.items.getOrNull(currentIndex + 1)?.media
                val pendingCompletion = pendingQueueCompletion
                if (pendingCompletion?.identity == currentAutoplayIdentity()) {
                    pendingQueueCompletion = null
                    settleCompleted(pendingCompletion)
                } else {
                    pendingQueueCompletion = null
                    installedPlayerController?.playbackState?.value?.let(::publish)
                }
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
        if (plan?.itemId != currentItemId) {
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

    private fun handleCompleted(playbackState: PlaybackState) {
        val duration = playbackState.durationMs
        val verifiedEnd = duration != null && playbackState.positionMs >= duration - VERIFIED_END_TOLERANCE_MS
        val identity = currentAutoplayIdentity()
        if (handledCompletion == null) {
            handledCompletion = identity
            installedPlaybackReportingCoordinator?.stop(playbackState.positionMs)
        }
        val completion =
            TvPendingQueueCompletion(
                identity = identity,
                playbackState = playbackState,
                verifiedEnd = verifiedEnd,
            )
        if (verifiedEnd && queueLoadPending) {
            pendingQueueCompletion = pendingQueueCompletion ?: completion
            completedWithNext = false
            publish(playbackState, retainCompletedHost = true)
            return
        }
        if (!verifiedEnd && queueLoadPending) {
            queueJob?.cancel()
            queueJob = null
            episodeQueue = emptyList()
            nextEpisodeItem = null
            nextEpisodePresentation = null
            queueLoadPending = false
            queuePresentation = queuePresentation.copy(isPending = false)
            pendingQueueCompletion = null
        }
        settleCompleted(completion)
    }

    private fun settleCompleted(completion: TvPendingQueueCompletion) {
        val identity = completion.identity
        if (closed || currentAutoplayIdentity() != identity || settledCompletion == identity) return
        settledCompletion = identity
        val currentIndex = currentQueueIndex()
        val nextIndex = currentIndex + 1
        val next = episodeQueue?.getOrNull(nextIndex).takeIf { currentIndex >= 0 }
        if (completion.verifiedEnd && next != null) {
            completedWithNext = true
            nextEpisodeItem = next
            nextEpisodePresentation = queuePresentation.items.getOrNull(nextIndex)?.media
            if (
                preferences.autoPlayNext &&
                !autoplayDismissed &&
                !advanceInFlight &&
                autoplayIdentity != identity
            ) {
                startAutoplayCountdown(identity, nextIndex)
            }
            publish(completion.playbackState, retainCompletedHost = true)
            return
        }
        completedWithNext = false
        cancelAutoplaySurface()
        if (!advanceInFlight) {
            publish(completion.playbackState, terminalCompleted = true, retainCompletedHost = false)
        }
    }

    private fun currentAutoplayIdentity(): TvAutoplayIdentity =
        TvAutoplayIdentity(
            itemId = currentItemId,
            queueOrderGeneration = queueOrderGeneration,
            launchGeneration = playbackHealthGeneration,
        )

    private fun startAutoplayCountdown(
        identity: TvAutoplayIdentity,
        nextIndex: Int,
    ) {
        cancelAutoplayCountdown()
        autoplayIdentity = identity
        autoplayCountdownSeconds = preferences.normalized().autoPlayNextDelaySeconds
        autoplayJob =
            scope.launch {
                while ((autoplayCountdownSeconds ?: 0) > 0) {
                    delay(1_000L)
                    if (closed || autoplayIdentity != identity || currentAutoplayIdentity() != identity) return@launch
                    autoplayCountdownSeconds = (autoplayCountdownSeconds ?: 1) - 1
                    publish(playerController.playbackState.value, retainCompletedHost = true)
                }
                if (closed || autoplayIdentity != identity || currentAutoplayIdentity() != identity) return@launch
                autoplayJob = null
                autoplayIdentity = null
                autoplayCountdownSeconds = null
                advanceAfterCountdown(nextIndex)
            }
    }

    private fun advanceAfterCountdown(nextIndex: Int) {
        if (!preferences.stillWatchingPrompt) {
            stillWatchingState = stillWatchingState.resetForDisabledPreference()
            switchToQueueIndex(index = nextIndex, stopAlreadyReported = true)
            return
        }
        when (val advance = stillWatchingState.automaticAdvance(nextIndex)) {
            is PlayerStillWatchingAutomaticAdvance.Allowed -> {
                stillWatchingState = advance.state
                switchToQueueIndex(index = nextIndex, stopAlreadyReported = true)
            }
            is PlayerStillWatchingAutomaticAdvance.PromptBlocked -> {
                stillWatchingState = advance.state
                publish(playerController.playbackState.value, retainCompletedHost = true)
            }
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
                if (plan?.plannedSubtitle is PlannedSubtitle.LocalAsset) {
                    markLocalSubtitleUnavailable()
                } else {
                    markSubtitleFallbackUnavailable(decision.target)
                }
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
                                localSubtitleAsset = requestedLocalSubtitleAsset?.toPlaybackAsset(),
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
                            playbackSpeed = playbackSpeed,
                            subtitleStyle = subtitleStyle,
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
        explicit: SubtitleSelectionIntent,
        stored: SubtitleSelectionIntent?,
        key: SubtitleSelectionKey,
        options: List<SubtitleTrackOption>,
    ): TvResolvedSubtitleSelection {
        resolveExplicitSubtitleSelection(explicit, key, options)?.let { return it }
        when (stored) {
            SubtitleSelectionIntent.Off -> return TvResolvedSubtitleSelection(SubtitleSelectionIntent.Off)
            is SubtitleSelectionIntent.Track ->
                if (options.any { option -> option.streamIndex == stored.streamIndex }) {
                    return TvResolvedSubtitleSelection(stored)
                } else {
                    saveSubtitleSelection.delete(key)
                }
            is SubtitleSelectionIntent.LocalAsset -> {
                val asset = getLocalSubtitleAsset?.invoke(stored.assetId, key.toLocalSubtitleContext())
                if (asset != null) {
                    return TvResolvedSubtitleSelection(stored, localAsset = asset)
                }
                saveSubtitleSelection.delete(key)
                return TvResolvedSubtitleSelection(
                    selection = SubtitleSelectionIntent.Off,
                    localAssetUnavailable = true,
                )
            }
            SubtitleSelectionIntent.Unspecified, null -> Unit
        }
        val selection =
            options
                .preferredSubtitleStreamIndex(preferences.preferredSubtitleLanguage)
                ?.let(SubtitleSelectionIntent::Track)
                ?: options.defaultSubtitleStreamIndex()?.let(SubtitleSelectionIntent::Track)
                ?: SubtitleSelectionIntent.Off
        return TvResolvedSubtitleSelection(selection)
    }

    private suspend fun resolveExplicitSubtitleSelection(
        selection: SubtitleSelectionIntent,
        key: SubtitleSelectionKey,
        options: List<SubtitleTrackOption>,
    ): TvResolvedSubtitleSelection? =
        when (selection) {
            SubtitleSelectionIntent.Unspecified -> null
            SubtitleSelectionIntent.Off -> TvResolvedSubtitleSelection(SubtitleSelectionIntent.Off)
            is SubtitleSelectionIntent.Track ->
                selection
                    .takeIf { candidate -> options.any { option -> option.streamIndex == candidate.streamIndex } }
                    ?.let(::TvResolvedSubtitleSelection)
            is SubtitleSelectionIntent.LocalAsset -> {
                val asset = getLocalSubtitleAsset?.invoke(selection.assetId, key.toLocalSubtitleContext())
                if (asset != null) {
                    TvResolvedSubtitleSelection(selection, localAsset = asset)
                } else {
                    TvResolvedSubtitleSelection(
                        selection = SubtitleSelectionIntent.Off,
                        localAssetUnavailable = true,
                    )
                }
            }
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

    private fun observeSelectedLocalSubtitle(context: LocalSubtitleContext) {
        localSubtitleAssetsJob?.cancel()
        val selectedAssetId = requestedLocalSubtitleAsset?.id ?: return
        val observer = observeLocalSubtitleAssets ?: return
        localSubtitleAssetsJob =
            scope.launch {
                observer(context)
                    .flowOn(dispatchers.work)
                    .collect { assets ->
                        if (
                            requestedLocalSubtitleAsset?.id == selectedAssetId &&
                            assets.none { asset -> asset.id == selectedAssetId }
                        ) {
                            markLocalSubtitleUnavailable()
                        }
                    }
            }
    }

    private fun markLocalSubtitleUnavailable() {
        requestedSubtitleStreamIndex = null
        requestedLocalSubtitleAsset = null
        requestedSubtitleSelection = SubtitleSelectionIntent.Off
        _state.update { current -> current.copy(localSubtitleSelected = false) }
        persistSubtitleSelection(SubtitleSelectionIntent.Off)
        playerController.selectEmbeddedSubtitle(null)
        publishLocalSubtitleUnavailableNotice()
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    private fun publishLocalSubtitleUnavailableNotice() {
        subtitleNotice =
            TvPlaybackNotice(
                token = ++subtitleNoticeToken,
                kind = TvPlaybackNoticeKind.LocalSubtitleUnavailable,
            )
    }

    private fun canApplySubtitleStyle(): Boolean {
        val currentPlan = plan ?: return false
        val renderInfo =
            subtitleRenderInfo(
                options = subtitleOptions(mediaStreams),
                plannedSubtitle = currentPlan.plannedSubtitle,
                activationState = playerController.playbackState.value.subtitleActivation,
            )
        return renderInfo.styleable && playerController.appliesSubtitleStyle
    }

    private fun SubtitleSelectionKey.toLocalSubtitleContext(): LocalSubtitleContext =
        LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

    private fun LocalSubtitleAsset.toPlaybackAsset(): SubtitleAsset.LocalFile =
        SubtitleAsset.LocalFile(
            assetId = id,
            fileId = fileId,
            mimeType = mimeType,
            label = label,
            language = language,
        )

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
        val localAsset = plannedSubtitle as? PlannedSubtitle.LocalAsset
        if (localAsset != null) {
            val target =
                SubtitleActivationTarget(
                    requestId = requestId,
                    itemId = this.itemId,
                    identity = SubtitleActivationIdentity.LocalAsset(localAsset.assetId),
                    kind = LocalSubtitleKind.ExternalText,
                )
            return copy(
                selectedSubtitleStreamIndex = null,
                subtitleAsset =
                    (subtitleAsset as? SubtitleAsset.LocalFile)
                        ?.takeIf { asset -> asset.assetId == localAsset.assetId },
                subtitleActivationTarget = target,
                plannedSubtitle = localAsset.copy(activationTarget = target),
            )
        }
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
        retainCompletedHost: Boolean = completedWithNext,
    ) {
        val controller = installedPlayerController ?: return
        logTvTrackDiagnostics(playbackState)
        val confirmedAudio = confirmedAudioStreamIndex(playbackState)
        val confirmedSubtitle = confirmedSubtitleStreamIndex(playbackState)
        val duration = playbackState.durationMs
        val inheritedQualityPolicy = preferences.effectiveDefaultQualityPolicy(controller.activeBackend)
        _state.update { currentState ->
            TvPlaybackUiState(
                playerInstalled = installedPlayerController != null,
                playerIdentity = playerIdentity,
                backend = controller.activeBackend,
                phase =
                    when (playbackState.status) {
                        PlaybackStatus.Failed -> TvPlaybackPhase.Failed
                        PlaybackStatus.Completed ->
                            when {
                                retainCompletedHost -> TvPlaybackPhase.Active
                                terminalCompleted -> TvPlaybackPhase.Completed
                                else -> TvPlaybackPhase.Loading
                            }
                        PlaybackStatus.Idle, PlaybackStatus.Loading -> TvPlaybackPhase.Loading
                        else -> TvPlaybackPhase.Active
                    },
                status = playbackState.status,
                positionMs = playbackState.positionMs,
                durationMs = duration,
                bufferedPositionMs = playbackState.bufferedPositionMs,
                isTranscode = plan?.streamMode == StreamMode.Transcode,
                itemId = currentItemId,
                mediaSourceId = selectedMediaSourceId,
                planEpoch = planEpoch,
                prepareEpoch = controller.runtimeDiagnostics.value.prepareEpoch ?: 0L,
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
                localSubtitleSelected =
                    requestedLocalSubtitleAsset?.id?.let { assetId ->
                        (requestedSubtitleSelection as? SubtitleSelectionIntent.LocalAsset)?.assetId == assetId
                    } == true,
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
                                    if (preferences.usesVlcDefaultQuality(controller.activeBackend)) {
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
                inheritedQualityUsesVlcSetting = preferences.usesVlcDefaultQuality(controller.activeBackend),
                activeSegment = activeSegment(playbackState),
                nextEpisode = nextEpisodePresentation,
                upNextVisible =
                    nextEpisodeItem != null &&
                        !autoplayDismissed &&
                        (
                            retainCompletedHost ||
                                (
                                    duration != null &&
                                        duration - playbackState.positionMs <= UP_NEXT_WINDOW_MS
                                )
                        ),
                queue = queuePresentation,
                nextUpCountdownSeconds = autoplayCountdownSeconds,
                nextUpDismissed = autoplayDismissed,
                stillWatchingVisible = stillWatchingState.isPromptVisible,
                playbackSpeed = playbackState.playbackSpeed,
                playbackSpeedChoices = tvPlaybackSpeedChoices(playbackState.playbackSpeed),
                subtitleStyle = playbackState.subtitleStyle,
                subtitleStyleSupported = canApplySubtitleStyle(),
                diagnostics = currentState.diagnostics,
                playbackGuidance = playbackHealthCoordinator.guidance,
                playbackActionNotice = playbackActionNotice,
                playbackActions = playbackActionNotice?.actions.orEmpty().toList(),
                subtitleNotice = subtitleNotice,
                preferences = preferences,
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

private data class TvAutoplayIdentity(
    val itemId: String,
    val queueOrderGeneration: Long,
    val launchGeneration: Long,
)

private data class TvPendingQueueCompletion(
    val identity: TvAutoplayIdentity,
    val playbackState: PlaybackState,
    val verifiedEnd: Boolean,
)

private data class TvResolvedSubtitleSelection(
    val selection: SubtitleSelectionIntent,
    val localAsset: LocalSubtitleAsset? = null,
    val localAssetUnavailable: Boolean = false,
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
