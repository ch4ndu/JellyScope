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
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackChangeResult
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
import com.jellyscope.core.domain.playback.PlaybackPlanningException
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
import com.jellyscope.core.domain.playback.PlaybackTransitionReason
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
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
import com.jellyscope.core.domain.playback.canRejectPlaybackChange
import com.jellyscope.core.domain.playback.defaultSubtitleStreamIndex
import com.jellyscope.core.domain.playback.diagnosticClass
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.millisecondsToTicks
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.playerQualityOptions
import com.jellyscope.core.domain.playback.positionForRetry
import com.jellyscope.core.domain.playback.preferredAudioStreamIndex
import com.jellyscope.core.domain.playback.preferredSubtitleStreamIndex
import com.jellyscope.core.domain.playback.qualityOptions
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.core.domain.playback.resolvePlayerBackend
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.core.domain.playback.subtitleRenderInfo
import com.jellyscope.core.domain.playback.toBitrateConstraint
import com.jellyscope.core.domain.playback.withReplacementSeekTarget
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
import com.jellyscope.core.util.PlaybackDiagnosticSessions
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
    val showsClock: Boolean = true,
    initialItemId: String,
    private val requestedMediaSourceId: String?,
    private val initialStartPositionTicks: Long,
    private val playerControllerFactory: (PlayerBackend) -> PlayerController,
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
    private val playbackHealthGuidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
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

    /** The installed native player; the Swift host uses a conditional AVPlayer cast. */
    val platformPlayer: Any? get() = installedPlayerController?.platformPlayer
    val surfaceController: PlayerController? get() = installedPlayerController

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
    private var hostTransportEpoch = 0L
    private var desiredPlayWhenReady = true
    private var playbackIntentRevision = 0L
    private var pendingReplacementSeek: Long? = null
    private var replacementSeekStopPosition: Long? = null
    private var queuedSeekTarget: Long? = null
    private var controllerInstallInFlight = false
    private var backendSwitchJob: Job? = null
    private var backendSwitchGeneration = 0L
    private var backendSwitchPending = false
    private var stopRequestedDuringBackendInstall = false
    private var availableBackends: List<PlayerBackend> = emptyList()
    private var sessionBackend: PlayerBackend? = null
    private var hostVideoOutputAvailable = false
    private var healthPrepareEpoch: Long? = null
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

    /** The replan job of the Audio recovery request that launched for [audioRecoveryTarget]. */
    private var audioRecoveryJob: Job? = null
    private var subtitleFallbackTarget: SubtitleActivationTarget? = null
    private var subtitleFallbackGeneration = 0L

    /** The one pending user change; its fields commit only immediately before its plan installs. */
    private var pendingProposal: TvChangeProposal? = null
    private var proposalRequestIdentity = 0L

    /**
     * A user change displaced an admitted recovery and every replacement change inherits that. Ends at
     * a commit, a published failure, an admitted recovery, Retry, item change or close, or when the
     * exact displaced Audio or SubtitleEncode root below resolves; cancellation alone never ends it.
     */
    private var proposalDisplacedRecovery = false

    /**
     * The exact Audio recovery target, or SubtitleEncode fallback target, whose request started the
     * current displacing lineage, if that is what it displaced; a network or Auto recovery records
     * neither. Recorded only while [proposalDisplacedRecovery].
     */
    private var proposalDisplacedAudioRecovery: AudioActivationTarget? = null
    private var proposalDisplacedSubtitleRecovery: SubtitleActivationTarget? = null

    /** Whether [replanJob] belongs to an admitted Audio, SubtitleEncode, network or Auto recovery. */
    private var replanIsRecovery = false

    /**
     * A replan queued Stop for the installed reporting session, which then stays stopped until reporting
     * is installed again; cancelling or dropping that replan never ends this.
     */
    private var installedReportingMayBeStopped = false

    /** An installed local subtitle went missing while another request owned the replan slot. */
    private var localSubtitleRepair: TvLocalSubtitleRepair? = null

    private var playSessionId: String = deviceInfoProvider.newDeviceId()
    private var lastStatus: PlaybackStatus = PlaybackStatus.Idle
    private val autoSkippedSegmentKeys = mutableSetOf<String>()
    private var playbackHealthGeneration = 0L

    /** Process-unique diagnostic correlation for [playbackHealthGeneration]; never compared operationally. */
    private var diagnosticSession = 0L
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
                installedPlayerController?.playbackHealthMeasurementCapabilities?.let { capabilities ->
                    capabilities.copy(hasReliableFirstVideoOutput = capabilities.hasReliableFirstVideoOutput && hostVideoOutputAvailable)
                }
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
                reduce { TvPlaybackSessionChange.GuidanceChanged(guidance) }
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

    private fun reduce(change: TvPlaybackSessionChange) = reduce { change }

    private fun reduce(change: () -> TvPlaybackSessionChange) {
        _state.update { current -> reduceTvPlaybackSession(current, change()) }
    }

    fun accept(intent: TvPlaybackSessionIntent) {
        if (intent !is TvPlaybackSessionIntent.SelectBackend &&
            intent !is TvPlaybackSessionIntent.RecordVideoOutputReady &&
            intent !is TvPlaybackSessionIntent.RecordVideoOutputAvailability &&
            intent !is TvPlaybackSessionIntent.RecordNativePlaybackIntent &&
            intent !is TvPlaybackSessionIntent.SetDiagnosticsVisible &&
            intent != TvPlaybackSessionIntent.DismissSubtitleNotice &&
            intent != TvPlaybackSessionIntent.Start &&
            intent != TvPlaybackSessionIntent.Stop
        ) {
            invalidateBackendSwitch()
        }
        when (intent) {
            TvPlaybackSessionIntent.Start -> start()
            TvPlaybackSessionIntent.Play -> play()
            TvPlaybackSessionIntent.Pause -> pause()
            TvPlaybackSessionIntent.TogglePlayPause -> togglePlayPause()
            is TvPlaybackSessionIntent.SeekTo -> seekTo(intent.positionMs)
            is TvPlaybackSessionIntent.SetPlaybackSpeed -> setPlaybackSpeed(intent.speed)
            is TvPlaybackSessionIntent.SetSubtitleStyle ->
                setSubtitleStyle(
                    intent.fontScale,
                    intent.foregroundColor,
                    intent.backgroundColor,
                    intent.edgeStyleName,
                )
            is TvPlaybackSessionIntent.SetDiagnosticsVisible -> setDiagnosticsVisible(intent.visible)
            TvPlaybackSessionIntent.DismissSubtitleNotice -> dismissSubtitleNotice()
            TvPlaybackSessionIntent.Stop -> stop()
            TvPlaybackSessionIntent.SkipActiveSegment -> skipActiveSegment()
            TvPlaybackSessionIntent.PlayNextEpisode -> playNextEpisode()
            TvPlaybackSessionIntent.PlayPreviousEpisode -> playPreviousEpisode()
            is TvPlaybackSessionIntent.SelectQueueItem -> selectQueueItem(intent.index)
            TvPlaybackSessionIntent.ShuffleQueue -> shuffleQueue()
            TvPlaybackSessionIntent.DismissNextUp -> dismissNextUp()
            TvPlaybackSessionIntent.ContinueStillWatching -> continueStillWatching()
            TvPlaybackSessionIntent.DismissStillWatching -> dismissStillWatching()
            is TvPlaybackSessionIntent.SelectAudio -> selectAudio(intent.streamIndex)
            is TvPlaybackSessionIntent.SelectSubtitle -> selectSubtitle(intent.streamIndex)
            is TvPlaybackSessionIntent.SelectLocalSubtitle -> selectLocalSubtitle(intent.assetId)
            is TvPlaybackSessionIntent.SelectQualityLimit -> selectQuality(intent.maxBitrateBps)
            is TvPlaybackSessionIntent.SelectQualityPolicy -> selectQuality(intent.policy)
            is TvPlaybackSessionIntent.SelectQualityChoice -> selectQualityChoice(intent.modeName, intent.maxBitrateBps)
            is TvPlaybackSessionIntent.SelectBackend -> selectBackend(intent.backend)
            is TvPlaybackSessionIntent.RecordVideoOutputReady ->
                if (acceptsOutputHost(intent.playerIdentity, intent.planEpoch, intent.itemId, intent.generation) &&
                    hostVideoOutputAvailable
                ) {
                    installedPlayerController?.recordVideoOutputObservation(generation = intent.generation)
                }
            is TvPlaybackSessionIntent.RecordVideoOutputAvailability ->
                if (acceptsOutputHost(intent.playerIdentity, intent.planEpoch, intent.itemId, intent.generation)) {
                    val changed = hostVideoOutputAvailable != intent.available
                    hostVideoOutputAvailable = intent.available
                    if (changed) playbackHealthCoordinator.restartEvidenceWindow()
                }
            is TvPlaybackSessionIntent.RecordNativePlaybackIntent ->
                recordNativePlaybackIntent(
                    intent.playerIdentity,
                    intent.planEpoch,
                    intent.itemId,
                    intent.isPlaying,
                    intent.hostTransportEpoch,
                )
            is TvPlaybackSessionIntent.HandlePlaybackAction -> handlePlaybackAction(intent.action)
        }
    }

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

    private fun start() {
        if (started || closed) {
            return
        }
        started = true
        reduce(TvPlaybackSessionChange.StartLoading)
        val expectedGeneration = ++startupGeneration
        startupJob =
            scope.launch {
                try {
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
                        reduce { TvPlaybackSessionChange.StartFailed(installedPlayerController != null) }
                    }
                }
            }
    }

    private fun observeControllerOutput() {
        val controller = playerController
        videoOutputJob =
            scope.launch {
                controller.videoOutputObservations.collect { observation ->
                    if (installedPlayerController !== controller ||
                        observation.generation != controller.runtimeDiagnostics.value.prepareEpoch
                    ) {
                        return@collect
                    }
                    playbackHealthCoordinator.observeVideoOutput(observation)
                    if (observation.presented && videoOutputLoggedPrepareEpoch != playerController.runtimeDiagnostics.value.prepareEpoch) {
                        videoOutputLoggedPrepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch
                        logTvVideoOutput(observation.observedAtMs >= 0L)
                    }
                }
            }
    }

    private fun play() {
        val controller = installedPlayerController ?: return
        playbackReportingCoordinator.settleRetryTransition()
        desiredPlayWhenReady = true
        playbackIntentRevision += 1L
        hostTransportEpoch += 1L
        if (plan?.itemId == currentItemId && !startInFlight && pendingReplacementSeek == null && !controllerInstallInFlight) {
            playbackHealthCoordinator.restartEvidenceWindow()
            controller.play()
        }
        publish(controller.playbackState.value)
    }

    private fun pause() {
        val controller = installedPlayerController ?: return
        playbackReportingCoordinator.settleRetryTransition()
        desiredPlayWhenReady = false
        playbackIntentRevision += 1L
        hostTransportEpoch += 1L
        if (plan?.itemId == currentItemId && !startInFlight && pendingReplacementSeek == null && !controllerInstallInFlight) {
            playbackHealthCoordinator.restartEvidenceWindow()
            controller.pause()
        }
        publish(controller.playbackState.value)
    }

    private fun togglePlayPause() {
        if (pendingReplacementSeek != null) {
            if (desiredPlayWhenReady) pause() else play()
            return
        }
        val controller = installedPlayerController ?: return
        if (plan?.itemId != currentItemId) return
        when (controller.playbackState.value.status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> pause()
            PlaybackStatus.Paused, PlaybackStatus.Loading -> play()
            else -> Unit
        }
    }

    private fun seekTo(positionMs: Long) {
        if (startInFlight) return
        val controller = installedPlayerController ?: return
        playbackReportingCoordinator.settleRetryTransition()
        val target = positionMs.coerceAtLeast(0L)
        if (controllerInstallInFlight) {
            if (pendingReplacementSeek != null) {
                queuedSeekTarget = target
                pendingReplacementSeek = target
                publish(controller.playbackState.value)
            }
            return
        }
        val wasHeld = pendingReplacementSeek != null
        if (wasHeld) {
            replanJob?.cancel()
            replanJob = null
            cancelPendingProposal()
        }
        val currentPlan = plan
        if (
            controller.transcodeSeekRestartsStream &&
            currentPlan?.streamMode == StreamMode.Transcode &&
            !isWithinTranscodedWindow(target, currentPlan)
        ) {
            // Out-of-window transcode seeks wedge AVPlayer; restart the
            // transcode at the target instead (same contract as PlayerViewModel).
            if (!wasHeld) replacementSeekStopPosition = controller.playbackState.value.positionMs
            pendingReplacementSeek = target
            hostTransportEpoch += 1L
            playbackHealthCoordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
            playbackHealthCoordinator.suspendForInstallation()
            controller.pause()
            publish(controller.playbackState.value)
            proposeChange(TvPlaybackChangeOperation.Seek, controller, targetPositionMs = target)
            return
        }
        playbackHealthCoordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
        hostTransportEpoch += 1L
        pendingReplacementSeek = null
        replacementSeekStopPosition = null
        controller.seekTo(target)
        if (wasHeld && desiredPlayWhenReady) controller.play()
        if (wasHeld) playbackHealthCoordinator.resumeAfterInstallation(controller.playbackState.value)
        publish(controller.playbackState.value)
        installedPlaybackReportingCoordinator?.progress(
            positionMs = target,
            isPaused = controller.playbackState.value.status == PlaybackStatus.Paused,
            eventName = PlaybackProgressEvent.TimeUpdate,
        )
    }

    private fun setPlaybackSpeed(speed: Float) {
        if (startInFlight || controllerInstallInFlight || !speed.isFinite()) return
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

    private fun setSubtitleStyle(
        fontScale: Float,
        foregroundColor: String?,
        backgroundColor: String?,
        edgeStyleName: String,
    ) {
        if (startInFlight || controllerInstallInFlight) return
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

    private fun setDiagnosticsVisible(visible: Boolean) {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        if (!visible) {
            reduce(TvPlaybackSessionChange.DiagnosticsHidden)
            return
        }
        val controller = installedPlayerController ?: return
        diagnosticsJob =
            scope.launch {
                controller.runtimeDiagnostics.collect { runtime ->
                    reduce {
                        TvPlaybackSessionChange.DiagnosticsLoaded(
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

    private fun dismissSubtitleNotice() {
        subtitleNotice = null
        installedPlayerController?.playbackState?.value?.let(::publish)
    }

    private fun stop() {
        desiredPlayWhenReady = false
        playbackIntentRevision += 1L
        invalidateBackendSwitch()
        if (controllerInstallInFlight && backendSwitchPending) {
            stopRequestedDuringBackendInstall = true
            return
        }
        hostTransportEpoch += 1L
        pendingReplacementSeek = null
        queuedSeekTarget = null
        replacementSeekStopPosition = null

        val controller = installedPlayerController ?: return
        cancelAutoplaySurface()
        cancelAndInvalidateSubtitleFallback()
        localSubtitleRepair = null
        playbackHealthCoordinator.end()
        installedPlaybackReportingCoordinator?.stop(controller.playbackState.value.positionMs)
        controller.stop()
    }

    private fun skipActiveSegment() {
        val segment = state.value.activeSegment ?: return
        seekTo(segment.endMs)
    }

    private fun playNextEpisode() {
        val index = currentQueueIndex() + 1
        selectQueueItem(index)
    }

    private fun playPreviousEpisode() {
        val index = currentQueueIndex() - 1
        selectQueueItem(index)
    }

    private fun selectQueueItem(index: Int) {
        if (controllerInstallInFlight) return
        val selected = episodeQueue?.getOrNull(index) ?: return
        if (selected.id == currentItemId) return
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        cancelAutoplaySurface()
        autoplayDismissed = false
        switchToQueueIndex(index = index, stopAlreadyReported = false)
    }

    private fun shuffleQueue() {
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

    private fun dismissNextUp() {
        autoplayDismissed = true
        cancelAutoplayCountdown()
        installedPlayerController?.playbackState?.value?.let { playbackState ->
            publish(playbackState, retainCompletedHost = completedWithNext)
        }
    }

    private fun continueStillWatching() {
        if (controllerInstallInFlight) return
        val pendingIndex = stillWatchingState.pendingQueueIndex ?: return
        stillWatchingState = stillWatchingState.confirm()
        cancelAutoplaySurface()
        switchToQueueIndex(index = pendingIndex, stopAlreadyReported = true)
    }

    private fun dismissStillWatching() {
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
        if (controllerInstallInFlight) return
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

    private fun selectAudio(streamIndex: Int) {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        audioOptions(mediaStreams).firstOrNull { track -> track.streamIndex == streamIndex } ?: return
        val currentPlan = plan
        if (currentPlan != null && currentPlan.streamMode != StreamMode.DirectPlay) {
            proposeChange(TvPlaybackChangeOperation.Audio, controller) {
                copy(audioStreamIndex = streamIndex, explicitAudioStreamIndex = streamIndex)
            }
            return
        }
        // A DirectPlay switch is an immediate native change: it commits now and cancels a pending change.
        cancelPendingProposal()
        cancelAndInvalidateSubtitleFallback()
        // Accepting this Audio retires the unresolved Audio recovery, so a lineage that displaced exactly
        // that recovery ends here; one that displaced any other recovery still holds. A root already
        // retired by its exact native Active ended its lineage then.
        if (audioRecoveryTarget != null && audioRecoveryTarget == proposalDisplacedAudioRecovery) {
            clearDisplacedRecovery()
        }
        audioRecoveryTarget = null
        requestedAudioStreamIndex = streamIndex
        explicitAudioStreamIndex = streamIndex
        saveAudioSelection()

        currentPlan ?: return
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
        reconsiderLocalSubtitleRepair()
    }

    private fun selectSubtitle(streamIndex: Int?) {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        val option =
            streamIndex?.let { selectedIndex ->
                subtitleOptions(mediaStreams).firstOrNull { track -> track.streamIndex == selectedIndex }
                    ?: return
            }
        val intent = streamIndex?.let(SubtitleSelectionIntent::Track) ?: SubtitleSelectionIntent.Off
        val currentPlan = plan
        val installedTrack = currentPlan?.plannedSubtitle as? PlannedSubtitle.Track
        val canSwitchInPlayer =
            (option == null && installedTrack?.deliveryMethod in localEmbeddedDeliveryMethods) ||
                (
                    option != null &&
                        installedTrack?.streamIndex == option.streamIndex &&
                        installedTrack.deliveryMethod in localEmbeddedDeliveryMethods
                )
        if (currentPlan != null && !canSwitchInPlayer) {
            // The accepted new plan applies Off natively; nothing changes before it commits.
            proposeChange(TvPlaybackChangeOperation.Subtitle, controller) {
                copy(subtitleSelection = intent, subtitleStreamIndex = streamIndex, localSubtitleAsset = null)
            }
            return
        }
        // An in-player embedded switch or Off is an immediate native change: it commits now and
        // cancels a pending change.
        if (pendingReplacementSeek != null) {
            val healthyOwner =
                canRejectPlaybackChange(
                    installedPlayerController === controller && currentPlan != null && !controllerInstallInFlight,
                    !installedReportingMayBeStopped,
                    proposalDisplacedRecovery,
                    _state.value.phase == TvPlaybackPhase.Failed,
                    controller.playbackState.value.status,
                )
            if (!healthyOwner) {
                cancelPendingProposal()
                replanJob?.cancel()
                replanJob = null
                publishFailure(PlaybackError.Unknown)
                return
            }
            hostTransportEpoch += 1L
            pendingReplacementSeek = null
            replacementSeekStopPosition = null
            queuedSeekTarget = null
            if (desiredPlayWhenReady) controller.play() else controller.pause()
            playbackHealthCoordinator.resumeAfterInstallation(controller.playbackState.value)
        }
        cancelPendingProposal()
        replanJob?.cancel()
        replanJob = null
        invalidateSubtitleFallback()
        requestedSubtitleStreamIndex = streamIndex
        requestedLocalSubtitleAsset = null
        subtitleNotice = null
        requestedSubtitleSelection = intent
        reduce { TvPlaybackSessionChange.LocalSubtitleChanged(false) }
        persistSubtitleSelection(intent)

        currentPlan ?: return
        val target =
            option?.let { track ->
                newSubtitleActivationTarget(
                    streamIndex = track.streamIndex,
                    kind = installedTrack?.localKind() ?: LocalSubtitleKind.EmbeddedText,
                )
            }
        val embeddedSelection =
            option?.let {
                EmbeddedSubtitleSelection(
                    target = requireNotNull(target),
                    descriptor = installedTrack?.embeddedTrack ?: return,
                )
            }
        // This native Off or switch replaces the installed target's demand, so a lineage that displaced
        // exactly that target's SubtitleEncode fallback ends here; one that displaced any other holds.
        val replacedTarget = currentPlan.subtitleActivationTarget
        if (replacedTarget != null && replacedTarget == proposalDisplacedSubtitleRecovery) {
            clearDisplacedRecovery()
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
        controller.selectEmbeddedSubtitle(embeddedSelection)
        publish(controller.playbackState.value)
        reconsiderLocalSubtitleRepair()
    }

    private fun selectLocalSubtitle(assetId: String?) {
        if (assetId == null) {
            selectSubtitle(null)
            return
        }
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        val proposal =
            openProposal(TvPlaybackChangeOperation.LocalSubtitle, controller) {
                copy(
                    subtitleSelection = SubtitleSelectionIntent.LocalAsset(assetId),
                    subtitleStreamIndex = null,
                    localSubtitleAsset = null,
                )
            } ?: return
        val context = LocalSubtitleContext(session.serverId, session.userId, proposal.itemId, proposal.mediaSourceId)
        localSubtitleSelectionJob =
            scope.launch {
                val readJob = currentCoroutineContext()[Job]
                val asset =
                    try {
                        withContext(dispatchers.work) {
                            getLocalSubtitleAsset?.invoke(assetId, context)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        // The exception class only: never its message, the asset identity or a path.
                        logTvDiagnostic(
                            stage = PlaybackDiagnosticStage.Persistence,
                            event = PlaybackDiagnosticEvent.Failed,
                            exception = exception,
                        )
                        // An ordinary Throwable resume can win over cancellation; a cancelled read settles nothing.
                        currentCoroutineContext().ensureActive()
                        if (localSubtitleSelectionJob === readJob) localSubtitleSelectionJob = null
                        if (isCurrentProposal(proposal)) {
                            settleFailedLocalSubtitleRead(proposal, planningPlaybackError(exception))
                        } else {
                            dropProposal(proposal, PlaybackTransitionReason.ProposalStale)
                        }
                        return@launch
                    }
                // Retry, a newer choice, item change or close invalidates the captured change.
                if (!isCurrentProposal(proposal)) {
                    dropProposal(proposal, PlaybackTransitionReason.ProposalStale)
                    return@launch
                }
                localSubtitleSelectionJob = null
                if (asset == null) {
                    settleUnreadableLocalSubtitle(proposal)
                    return@launch
                }
                // The same change, now with its asset, keeps its identity into planning.
                val readProposal = proposal.copy(localSubtitleAsset = asset)
                pendingProposal = readProposal
                replanAtPosition(readProposal.targetPositionMs, proposal = readProposal)
            }
    }

    private fun selectQuality(maxBitrateBps: Long?) {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        val policy = maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
        proposeChange(TvPlaybackChangeOperation.Quality, controller) {
            copy(
                qualityPolicy = policy,
                qualityMaxBitrate = policy.maxBitrateBps,
                qualityCapOrigin = maxBitrateBps?.let { PlaybackQualityCapOrigin.ExplicitSessionChoice },
                qualityOverrideExplicit = true,
                qualityRecovery = TvQualityRecoveryEffect.Reset,
            )
        }
    }

    private fun selectQuality(policy: PlaybackQualityPolicy) {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        val normalized = policy.normalized()
        proposeChange(TvPlaybackChangeOperation.Quality, controller) {
            copy(
                qualityPolicy = normalized,
                qualityMaxBitrate = normalized.maxBitrateBps,
                qualityCapOrigin =
                    PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { normalized.mode == PlaybackQualityMode.Fixed },
                qualityOverrideExplicit = true,
                qualityRecovery = TvQualityRecoveryEffect.Reset,
            )
        }
    }

    private fun selectQualityChoice(
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

    private fun acceptsOutputHost(
        identity: Long,
        epoch: Long,
        itemId: String,
        prepareEpoch: Long,
    ): Boolean =
        !closed &&
            identity == playerIdentity &&
            epoch == planEpoch &&
            itemId == currentItemId &&
            plan?.itemId == itemId &&
            prepareEpoch > 0L &&
            installedPlayerController?.runtimeDiagnostics?.value?.prepareEpoch == prepareEpoch

    private fun expectPreparedVideoOutput() {
        hostVideoOutputAvailable = false
        healthPrepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch
        healthPrepareEpoch?.let(playbackHealthCoordinator::expectVideoOutput)
    }

    private fun validatePlannedSelection(
        playbackPlan: PlaybackPlan,
        itemId: String,
        sourceId: String,
        quality: PlaybackQualityPolicy,
        subtitle: SubtitleSelectionIntent,
    ) {
        val subtitleMatches =
            when (subtitle) {
                SubtitleSelectionIntent.Unspecified -> true
                SubtitleSelectionIntent.Off -> playbackPlan.plannedSubtitle is PlannedSubtitle.Off
                is SubtitleSelectionIntent.Track ->
                    when (val planned = playbackPlan.plannedSubtitle) {
                        is PlannedSubtitle.Track -> planned.streamIndex == subtitle.streamIndex
                        is PlannedSubtitle.Unavailable -> planned.streamIndex == subtitle.streamIndex
                        else -> false
                    }
                is SubtitleSelectionIntent.LocalAsset ->
                    (playbackPlan.plannedSubtitle as? PlannedSubtitle.LocalAsset)?.assetId == subtitle.assetId &&
                        (playbackPlan.subtitleAsset as? SubtitleAsset.LocalFile)?.assetId == subtitle.assetId
            }
        if (playbackPlan.itemId != itemId ||
            playbackPlan.mediaSourceId != sourceId ||
            playbackPlan.qualityPolicy != quality ||
            !subtitleMatches
        ) {
            throw PlaybackPlanningException.NoSupportedStream
        }
    }

    /** Candidate remains owned by its caller until every installed field and collector is bound. */
    private fun transferController(candidateOwner: TvControllerCandidateOwner) {
        candidateOwner.transferTo { installed ->
            installedPlayerController = installed
            try {
                playerIdentity += 1L
                hostVideoOutputAvailable = false
                healthPrepareEpoch = null
                installedPlaybackReportingCoordinator =
                    PlaybackReportingCoordinator(queue = reportingQueue, scope = scope, playbackState = installed.playbackState)
                installedReportingMayBeStopped = false
                observeControllerOutput()
                reduce { TvPlaybackSessionChange.Installed(playerIdentity, installed.activeBackend) }
            } catch (exception: Throwable) {
                observeJob?.cancel()
                observeJob = null
                videoOutputJob?.cancel()
                videoOutputJob = null
                installedPlaybackReportingCoordinator = null
                installedPlayerController = null
                reduce(TvPlaybackSessionChange.StartFailed(playerInstalled = false))
                throw exception
            }
        }
    }

    private fun invalidateBackendSwitch() {
        // Once Stop is admitted, the replacement owns installation even if transport intent changes.
        if (controllerInstallInFlight) return
        backendSwitchGeneration += 1L
        backendSwitchJob?.cancel()
        backendSwitchJob = null
        if (pendingProposal?.operation == TvPlaybackChangeOperation.Backend) pendingProposal = null
        if (backendSwitchPending) {
            backendSwitchPending = false
            installedPlayerController?.playbackState?.value?.let(::publish)
        }
    }

    private fun selectBackend(backend: PlayerBackend) {
        val outgoing = installedPlayerController ?: return
        val outgoingPlan = plan ?: return
        if (closed ||
            startInFlight ||
            controllerInstallInFlight ||
            backendSwitchPending ||
            backend == outgoing.activeBackend ||
            backend == PlayerBackend.Auto ||
            backend !in availableBackends ||
            outgoingPlan.streamMode == StreamMode.Offline ||
            outgoing.playbackState.value.status !in setOf(PlaybackStatus.Playing, PlaybackStatus.Paused, PlaybackStatus.Buffering)
        ) {
            return
        }
        // An unresolved seek/recovery retains its newest target and reporting ownership.
        if (pendingReplacementSeek != null) return
        val quality = if (qualityOverrideExplicit) selectedQualityPolicy else preferences.effectiveDefaultQualityPolicy(backend)
        val proposal =
            openProposal(TvPlaybackChangeOperation.Backend, outgoing) {
                copy(
                    qualityPolicy = quality,
                    qualityMaxBitrate = quality.maxBitrateBps,
                    qualityCapOrigin =
                        if (qualityOverrideExplicit) {
                            selectedQualityCapOrigin
                        } else {
                            PlaybackQualityCapOrigin.SettingsDefault.takeIf { quality.maxBitrateBps != null }
                        },
                )
            } ?: return
        val generation = ++backendSwitchGeneration
        val revision = playbackIntentRevision
        val hostSeek = outgoing.lastAcceptedHostSeek
        val shouldResume = desiredPlayWhenReady && outgoing.playbackState.value.status != PlaybackStatus.Paused
        backendSwitchPending = true
        publish(outgoing.playbackState.value)
        backendSwitchJob =
            scope.launch {
                val owner = TvControllerCandidateOwner()
                var pausedForPlanning = false
                var transferred = false
                var installAdmitted = false
                var replacementController: PlayerController? = null

                fun ownsOutgoingSession(): Boolean =
                    !closed &&
                        installedPlayerController === outgoing &&
                        currentItemId == proposal.itemId &&
                        selectedMediaSourceId == proposal.mediaSourceId &&
                        planEpoch == proposal.planEpoch &&
                        plan === outgoingPlan

                fun current(): Boolean =
                    generation == backendSwitchGeneration &&
                        isCurrentProposal(proposal) &&
                        plan === outgoingPlan &&
                        outgoing.lastAcceptedHostSeek == hostSeek
                try {
                    val candidate = withContext(dispatchers.work) { playerControllerFactory(backend).also(owner::acquire) }
                    replacementController = candidate
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    check(candidate.activeBackend == backend) { "Controller backend differs from requested backend." }
                    // Freeze the current timeline before its one planning request: a transcode offset
                    // belongs to the returned URL and cannot be repaired by copying startPositionMs.
                    hostTransportEpoch += 1L
                    pausedForPlanning = true
                    outgoing.pause()
                    val target = outgoing.playbackState.value.positionForRetry()
                    val replacement =
                        withContext(dispatchers.work) {
                            playbackInfoPlanner.plan(
                                session = session,
                                itemId = proposal.itemId,
                                mediaSourceId = proposal.mediaSourceId,
                                startPositionTicks = millisecondsToTicks(target),
                                audioStreamIndex = proposal.audioStreamIndex,
                                detailMediaStreams = mediaStreams,
                                subtitleSelection = proposal.subtitleSelection,
                                localSubtitleAsset = proposal.localSubtitleAsset?.toPlaybackAsset(),
                                maxStreamingBitrate = proposal.qualityMaxBitrate,
                                qualityPolicy = quality,
                                qualityCapOrigin = proposal.qualityCapOrigin,
                                sourceContainer = selectedSourceContainer,
                                requestPolicy =
                                    PlaybackInfoRequestPolicy(backend = backend, diagnosticSessionSequence = diagnosticSession),
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    // A host/native seek or a pause that has not settled must not install a URL
                    // whose immutable server offset names an older point on the timeline.
                    if (outgoing.playbackState.value.positionForRetry() != target) {
                        throw PlaybackPlanningException.NoSupportedStream
                    }
                    val subtitlePreserved =
                        when (val selection = proposal.subtitleSelection) {
                            SubtitleSelectionIntent.Unspecified -> true
                            SubtitleSelectionIntent.Off -> replacement.plannedSubtitle is PlannedSubtitle.Off
                            is SubtitleSelectionIntent.Track ->
                                (replacement.plannedSubtitle as? PlannedSubtitle.Track)?.streamIndex ==
                                    selection.streamIndex
                            is SubtitleSelectionIntent.LocalAsset ->
                                (replacement.subtitleAsset as? SubtitleAsset.LocalFile)?.assetId ==
                                    selection.assetId
                        }
                    if (replacement.itemId != proposal.itemId ||
                        replacement.mediaSourceId != proposal.mediaSourceId ||
                        replacement.qualityPolicy != quality ||
                        !subtitlePreserved ||
                        (
                            proposal.explicitAudioStreamIndex != null &&
                                replacement.selectedAudioStreamIndex != proposal.explicitAudioStreamIndex
                        )
                    ) {
                        throw PlaybackPlanningException.NoSupportedStream
                    }
                    val enriched =
                        replacement
                            .withAudioActivationTarget()
                            .withSubtitleActivationTarget(
                                requestId = nextSubtitleActivationRequestId(),
                                selectedSubtitle = selectedSubtitleMediaStream(proposal.subtitleStreamIndex),
                            ).copy(
                                chapters = outgoingPlan.chapters,
                                mediaSegments = outgoingPlan.mediaSegments,
                                playbackSpeed = playbackSpeed,
                                subtitleStyle = subtitleStyle,
                            )
                    controllerInstallInFlight = true
                    installAdmitted = true
                    hostTransportEpoch += 1L
                    playbackHealthCoordinator.suspendForInstallation()
                    installedReportingMayBeStopped = true
                    playbackReportingCoordinator.stopNow(outgoing.playbackState.value.positionForRetry())
                    currentCoroutineContext().ensureActive()
                    if (!current()) {
                        if (ownsOutgoingSession() && installedReportingMayBeStopped) {
                            throw PlaybackPlanningException.NoSupportedStream
                        }
                        return@launch
                    }
                    if (outgoing.playbackState.value.positionForRetry() != target) {
                        throw PlaybackPlanningException.NoSupportedStream
                    }
                    observeJob?.cancel()
                    observeJob = null
                    videoOutputJob?.cancel()
                    videoOutputJob = null
                    try {
                        transferController(owner)
                        transferred = true
                    } finally {
                        outgoing.release()
                    }
                    sessionBackend = backend
                    commitProposal(proposal)
                    playbackHealthGeneration += 1L
                    playbackHealthCoordinator.end()
                    autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, backend)
                    playbackSessionRecoveryState = playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, currentItemId)
                    playbackHealthCoordinator.start(playbackHealthGeneration, playbackHealthSessionContext())
                    installPlan(enriched, resetReporting = true)
                    if (playbackIntentRevision == revision) desiredPlayWhenReady = shouldResume
                    playerController.prepare(enriched)
                    expectPreparedVideoOutput()
                    if (playerController.playbackState.value.status == PlaybackStatus.Failed) {
                        publishFailure(playerController.playbackState.value.error ?: PlaybackError.Unknown)
                        return@launch
                    }
                    applyInitialEmbeddedSelections(enriched)
                    if (desiredPlayWhenReady && !stopRequestedDuringBackendInstall) playerController.play() else playerController.pause()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    logTvDiagnostic(PlaybackDiagnosticStage.Planner, PlaybackDiagnosticEvent.Failed, exception = exception)
                    if (!closed) {
                        if (!transferred &&
                            current() &&
                            canRejectHealthily(proposal)
                        ) {
                            rejectProposal(proposal, planningPlaybackError(exception))
                        } else if (
                            (installAdmitted && ownsOutgoingSession()) ||
                            (
                                installAdmitted &&
                                    installedPlayerController == null &&
                                    currentItemId == proposal.itemId &&
                                    selectedMediaSourceId == proposal.mediaSourceId &&
                                    planEpoch == proposal.planEpoch &&
                                    plan === outgoingPlan
                            ) ||
                            (transferred && installedPlayerController === replacementController && currentItemId == proposal.itemId) ||
                            current()
                        ) {
                            publishFailure(planningPlaybackError(exception))
                        }
                    }
                } finally {
                    owner.releaseUntransferred()
                    val stopAfterInstall = installAdmitted && stopRequestedDuringBackendInstall
                    if (!transferred &&
                        pausedForPlanning &&
                        !closed &&
                        installedPlayerController === outgoing &&
                        plan === outgoingPlan &&
                        !installedReportingMayBeStopped &&
                        pendingReplacementSeek == null &&
                        shouldResume &&
                        desiredPlayWhenReady &&
                        playbackIntentRevision == revision
                    ) {
                        outgoing.play()
                    }
                    if (generation == backendSwitchGeneration) {
                        if (pendingProposal?.requestIdentity == proposal.requestIdentity) pendingProposal = null
                        backendSwitchPending = false
                        backendSwitchJob = null
                    }
                    if (installAdmitted) {
                        controllerInstallInFlight = false
                        stopRequestedDuringBackendInstall = false
                    }
                    if (stopAfterInstall && !closed) stop()
                    if (!closed) {
                        installedPlayerController?.let { controller ->
                            if (installAdmitted) {
                                playbackHealthCoordinator.resumeAfterInstallation(
                                    controller.playbackState.value.takeIf { pendingReplacementSeek == null },
                                )
                            }
                            publish(controller.playbackState.value)
                            observePlaybackState()
                            if (transferred && !stopAfterInstall) plan?.let(::maybeStartInstalledUnavailableFallback)
                        }
                    }
                }
            }
    }

    private fun recordNativePlaybackIntent(
        playerIdentity: Long,
        planEpoch: Long,
        itemId: String,
        isPlaying: Boolean,
        hostTransportEpoch: Long,
    ) {
        if (closed ||
            startInFlight ||
            pendingReplacementSeek != null ||
            controllerInstallInFlight ||
            hostTransportEpoch != this.hostTransportEpoch
        ) {
            return
        }
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
            invalidateBackendSwitch()
            playbackReportingCoordinator.settleRetryTransition()
            desiredPlayWhenReady = isPlaying
            playbackIntentRevision += 1L
            playbackHealthCoordinator.restartEvidenceWindow()
        }
    }

    private fun handlePlaybackAction(action: PlaybackAction) {
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
        if (closed || startInFlight || controllerInstallInFlight) return
        pendingReplacementSeek?.let { target ->
            queuedSeekTarget = null
            replanAtPosition(target)
            return
        }
        hostTransportEpoch += 1L
        // An accepted Retry owns playback in all three branches: a pending change and its local read
        // are invalidated, and the passive notice and any repair fact belong to the outgoing attempt.
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        pendingProposal = null
        clearDisplacedRecovery()
        localSubtitleRepair = null
        subtitleNotice = null
        reduce(TvPlaybackSessionChange.StartLoading)
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
        val retryTarget = controller.playbackState.value.positionForRetry()
        val priorPrepareEpoch = controller.runtimeDiagnostics.value.prepareEpoch
        val retryPlanEpoch = planEpoch
        val retryStartup = startupGeneration
        val retryItem = currentItemId
        val retrySource = selectedMediaSourceId
        val retryPaused = !desiredPlayWhenReady
        val retryIntentRevision = playbackIntentRevision
        // Pending replans belong to the failed attempt and must not install over this Retry.
        cancelAndInvalidateSubtitleFallback()
        val retryFallbackGeneration = subtitleFallbackGeneration
        // Drop a stored notice; pending credit stays with the installed plan this Retry re-prepares.
        playbackActionNotice = null
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, currentItemId)
        playSessionId = deviceInfoProvider.newDeviceId()
        lastStatus = PlaybackStatus.Idle
        reportingCoordinator.install(
            session = session,
            plan = currentPlan,
            playSessionId = currentPlan.playSessionId ?: playSessionId,
        )
        installedReportingMayBeStopped = false
        if (controller.activeBackend == PlayerBackend.VlcKit) {
            reportingCoordinator.beginRetryTransition(retryTarget, !retryPaused, priorPrepareEpoch)
        }
        // Native retry can replace an uncollected exact Active with Pending.
        retireResolvedAudioFallback(controller.playbackState.value)
        controller.retry()
        if (closed ||
            startInFlight ||
            controllerInstallInFlight ||
            installedPlayerController !== controller ||
            plan !== currentPlan ||
            planEpoch != retryPlanEpoch ||
            startupGeneration != retryStartup ||
            currentItemId != retryItem ||
            selectedMediaSourceId != retrySource ||
            installedPlaybackReportingCoordinator !== reportingCoordinator ||
            subtitleFallbackGeneration != retryFallbackGeneration
        ) {
            return
        }
        val newEpoch = controller.runtimeDiagnostics.value.prepareEpoch
        val retainedTarget =
            if (newEpoch != null && newEpoch != priorPrepareEpoch && controller.playbackState.value.status != PlaybackStatus.Failed) {
                controller.retainedPlan?.takeIf { it.itemId == retryItem && it.mediaSourceId == retrySource }?.startPositionMs
            } else {
                null
            }
        expectPreparedVideoOutput()
        maybeStartInstalledUnavailableFallback(
            currentPlan,
            includeInadmissibleAudio = true,
            positionOverride = retainedTarget ?: retryTarget,
            pausedOverride = if (playbackIntentRevision == retryIntentRevision) retryPaused else !desiredPlayWhenReady,
        )
    }

    private fun clearQualityOverride() {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        val inherited = preferences.effectiveDefaultQualityPolicy(controller.activeBackend)
        proposeChange(TvPlaybackChangeOperation.Quality, controller) {
            copy(
                qualityPolicy = inherited,
                qualityMaxBitrate = inherited.maxBitrateBps,
                qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault.takeIf { inherited.mode == PlaybackQualityMode.Fixed },
                qualityOverrideExplicit = false,
                qualityRecovery = TvQualityRecoveryEffect.Reset,
            )
        }
    }

    private fun tryHigherQuality() {
        if (startInFlight || controllerInstallInFlight) return
        val controller = installedPlayerController ?: return
        proposeChange(TvPlaybackChangeOperation.Quality, controller) {
            copy(
                qualityPolicy = PlaybackQualityPolicy.Auto,
                qualityMaxBitrate = null,
                qualityCapOrigin = null,
                qualityOverrideExplicit = true,
                qualityRecovery = TvQualityRecoveryEffect.ClearRuntimeCap,
            )
        }
    }

    /** Opens a user change and plans it; see [openProposal]. */
    private fun proposeChange(
        operation: TvPlaybackChangeOperation,
        controller: PlayerController,
        targetPositionMs: Long = pendingReplacementSeek ?: controller.playbackState.value.positionMs,
        change: TvChangeProposal.() -> TvChangeProposal = { this },
    ) {
        val proposal = openProposal(operation, controller, targetPositionMs, change) ?: return
        replanAtPosition(proposal.targetPositionMs, proposal = proposal)
    }

    /**
     * Makes the one pending user change from the installed inputs plus [change]. Nothing installed,
     * persisted, native, health, credit or recovery-owned changes here: the older change, its local
     * read and the replan slot are cancelled (invalidating a superseded Subtitle fallback token), the
     * record captures its authority and any recovery it displaces. Passive notices survive planning.
     */
    private fun openProposal(
        operation: TvPlaybackChangeOperation,
        controller: PlayerController,
        targetPositionMs: Long = pendingReplacementSeek ?: controller.playbackState.value.positionMs,
        change: TvChangeProposal.() -> TvChangeProposal = { this },
    ): TvChangeProposal? {
        if (controllerInstallInFlight) return null
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank) ?: return null
        // A replacement inherits its lineage's provenance; only a fresh lineage records which recovery it
        // displaces, before the cancellation below invalidates the SubtitleEncode fallback's token.
        if (!proposalDisplacedRecovery) {
            proposalDisplacedAudioRecovery = pendingAudioRecoveryTarget()
            proposalDisplacedSubtitleRecovery = pendingSubtitleFallbackTarget()
        }
        val displacesRecovery = proposalDisplacedRecovery || (replanIsRecovery && replanJob?.isActive == true)
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        cancelAndInvalidateSubtitleFallback()
        proposalDisplacedRecovery = displacesRecovery
        val proposal =
            TvChangeProposal(
                operation = operation,
                requestIdentity = ++proposalRequestIdentity,
                controller = controller,
                planEpoch = planEpoch,
                itemId = currentItemId,
                mediaSourceId = sourceId,
                targetPositionMs = targetPositionMs.coerceAtLeast(0L),
                audioStreamIndex = requestedAudioStreamIndex,
                explicitAudioStreamIndex = explicitAudioStreamIndex,
                subtitleSelection = requestedSubtitleSelection,
                subtitleStreamIndex = requestedSubtitleStreamIndex,
                localSubtitleAsset = requestedLocalSubtitleAsset,
                qualityPolicy = selectedQualityPolicy,
                qualityMaxBitrate = selectedQualityMaxBitrate,
                qualityCapOrigin = selectedQualityCapOrigin,
                qualityOverrideExplicit = qualityOverrideExplicit,
                qualityRecovery = TvQualityRecoveryEffect.Retain,
            ).change()
        pendingProposal = proposal
        return proposal
    }

    /** The change is still pending and its captured controller, plan epoch, item and source still hold. */
    private fun isCurrentProposal(proposal: TvChangeProposal): Boolean =
        !closed &&
            !startInFlight &&
            pendingProposal?.requestIdentity == proposal.requestIdentity &&
            installedPlayerController === proposal.controller &&
            planEpoch == proposal.planEpoch &&
            currentItemId == proposal.itemId &&
            selectedMediaSourceId == proposal.mediaSourceId

    /**
     * Only a current change whose lineage holds no unresolved displaced recovery, over healthy content
     * that has not failed and whose installed reporting session is intact, keeps playback with a passive
     * notice; every other planning failure stays fatal. A current change's lineage is
     * [proposalDisplacedRecovery], so a displaced root that resolved while it was pending counts.
     */
    private fun canRejectHealthily(proposal: TvChangeProposal): Boolean =
        canRejectPlaybackChange(
            isCurrentProposal(proposal),
            !installedReportingMayBeStopped,
            proposalDisplacedRecovery,
            _state.value.phase == TvPlaybackPhase.Failed,
            proposal.controller.playbackState.value.status,
        )

    /** Keeps the installed playback and every installed input; only the passive notice is new. */
    private fun rejectProposal(
        proposal: TvChangeProposal,
        error: PlaybackError?,
        settledJob: Job? = null,
    ) {
        pendingProposal = null
        if (pendingReplacementSeek != null) {
            hostTransportEpoch += 1L
            pendingReplacementSeek = null
            replacementSeekStopPosition = null
            if (desiredPlayWhenReady) proposal.controller.play()
            playbackHealthCoordinator.resumeAfterInstallation(proposal.controller.playbackState.value)
        }
        subtitleNotice =
            TvPlaybackNotice(
                token = ++subtitleNoticeToken,
                kind = TvPlaybackNoticeKind.ChangeRejected,
                operation = proposal.operation,
                error = error,
            )
        logTvChangeRejected(proposal.operation, error)
        publish(proposal.controller.playbackState.value)
        reconsiderLocalSubtitleRepair(settledJob)
    }

    /** A cancelled or stale change settles silently; it never shows a rejection or overwrites a failure. */
    private fun dropProposal(
        proposal: TvChangeProposal,
        reason: PlaybackTransitionReason,
        settledJob: Job? = null,
    ) {
        if (pendingProposal?.requestIdentity != proposal.requestIdentity) return
        pendingProposal = null
        logTvTransition(reason)
        reconsiderLocalSubtitleRepair(settledJob)
    }

    /** An immediate native change commits now; the pending change it overrides is cancelled. */
    private fun cancelPendingProposal() {
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        if (pendingProposal == null) return
        pendingProposal = null
        cancelAndInvalidateSubtitleFallback()
    }

    /**
     * A requested local asset could not be read. A healthy current change that displaced no recovery
     * is rejected and writes nothing. When the installed reporting session may be stopped or a replacement
     * seek is held, the change is fatal: an unavailable repair whose planning fails could not restart
     * reporting or settle the held seek. Otherwise the existing unavailable path turns subtitles off.
     */
    private fun settleUnreadableLocalSubtitle(proposal: TvChangeProposal) {
        if (canRejectHealthily(proposal)) {
            rejectProposal(proposal, error = null)
            return
        }
        if (installedReportingMayBeStopped || pendingReplacementSeek != null) {
            settleFailedLocalSubtitleRead(proposal, PlaybackError.UnsupportedMedia)
            return
        }
        pendingProposal = null
        markLocalSubtitleUnavailable()
    }

    /**
     * Reading the requested local asset threw. A healthy current change that displaced no recovery is
     * rejected and writes nothing; otherwise the failure is fatal, as for a planning failure.
     */
    private fun settleFailedLocalSubtitleRead(
        proposal: TvChangeProposal,
        error: PlaybackError,
    ) {
        if (canRejectHealthily(proposal)) {
            rejectProposal(proposal, error)
            return
        }
        pendingProposal = null
        publishFailure(error)
    }

    /**
     * Applies every accepted write of [proposal] without suspension, immediately before its plan
     * installs. The new plan applies native subtitle Off itself.
     */
    private fun commitProposal(proposal: TvChangeProposal) {
        pendingProposal = null
        clearDisplacedRecovery()
        requestedAudioStreamIndex = proposal.audioStreamIndex
        explicitAudioStreamIndex = proposal.explicitAudioStreamIndex
        requestedSubtitleSelection = proposal.subtitleSelection
        requestedSubtitleStreamIndex = proposal.subtitleStreamIndex
        requestedLocalSubtitleAsset = proposal.localSubtitleAsset
        selectedQualityPolicy = proposal.qualityPolicy
        selectedQualityMaxBitrate = proposal.qualityMaxBitrate
        selectedQualityCapOrigin = proposal.qualityCapOrigin
        qualityOverrideExplicit = proposal.qualityOverrideExplicit
        autoRecoveryState =
            when (proposal.qualityRecovery) {
                TvQualityRecoveryEffect.Retain -> autoRecoveryState
                TvQualityRecoveryEffect.Reset ->
                    autoRecoveryCoordinator.reset(playbackHealthGeneration, currentItemId, proposal.controller.activeBackend)
                TvQualityRecoveryEffect.ClearRuntimeCap -> autoRecoveryCoordinator.clearRuntimeQualityCap(autoRecoveryState)
            }
        when (proposal.operation) {
            TvPlaybackChangeOperation.Audio -> {
                audioRecoveryTarget = null
                saveAudioSelection()
            }
            TvPlaybackChangeOperation.Subtitle,
            TvPlaybackChangeOperation.LocalSubtitle,
            -> {
                reduce { TvPlaybackSessionChange.LocalSubtitleChanged(proposal.localSubtitleAsset != null) }
                persistSubtitleSelection(proposal.subtitleSelection)
                observeSelectedLocalSubtitle(
                    LocalSubtitleContext(session.serverId, session.userId, proposal.itemId, proposal.mediaSourceId),
                )
            }
            TvPlaybackChangeOperation.Quality, TvPlaybackChangeOperation.Backend -> Unit
            TvPlaybackChangeOperation.Seek ->
                playbackHealthCoordinator.restartEvidenceWindow(PlaybackHealthExclusionReason.Seek)
        }
        subtitleNotice = null
        playbackHealthCoordinator.markExclusion(PlaybackHealthExclusionReason.Replan)
        invalidateSubtitleFallback()
        pendingRecoveredPlaybackGuidance = false
        pendingRecoveredAutoQualityBps = null
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
        invalidateBackendSwitch()
        hostTransportEpoch += 1L
        pendingReplacementSeek = null
        queuedSeekTarget = null
        replacementSeekStopPosition = null

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
        audioRecoveryJob = null
        replanIsRecovery = false
        installedReportingMayBeStopped = false
        pendingProposal = null
        clearDisplacedRecovery()
        localSubtitleRepair = null
        subtitleNotice = null
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
        pendingReplacementSeek = null
        queuedSeekTarget = null
        replacementSeekStopPosition = null
        hostTransportEpoch += 1L
        desiredPlayWhenReady = true

        cancelAutoplaySurface()
        handledCompletion = null
        pendingQueueCompletion = null
        settledCompletion = null
        autoplayDismissed = false
        completedWithNext = false
        autoSkippedSegmentKeys.clear()
        audioRecoveryTarget = null
        // Item start owns playback; this clear precedes any startup unavailable notice.
        localSubtitleSelectionJob?.cancel()
        localSubtitleSelectionJob = null
        pendingProposal = null
        clearDisplacedRecovery()
        localSubtitleRepair = null
        subtitleNotice = null
        pendingRecoveredPlaybackGuidance = false
        pendingRecoveredAutoQualityBps = null
        queueLoadPending = false
        val itemChanged = currentItemId != itemId
        if (itemChanged) {
            plan = null
            hostTransportEpoch += 1L
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
        diagnosticSession = PlaybackDiagnosticSessions.allocate()
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackHealthGeneration, itemId)
        playbackActionNotice = null
        // Stale menus must not act on the outgoing item mid-switch.
        mediaStreams = emptyList()
        reduce { TvPlaybackSessionChange.ItemLoading(itemId, selectedMediaSourceId, planEpoch, nextEpisodePresentation, queuePresentation) }

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
        rememberedPlaybackSelection = launchContext.playbackSelection

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
        val provider = deviceProfileProvider
        val resolvedBackend =
            if (provider != null) {
                withContext(dispatchers.work) {
                    val available = provider.availableBackends
                    availableBackends = provider.backendPolicy.concreteBackends.filter { it in available }
                    val video = mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
                    val audio =
                        mediaStreams.firstOrNull {
                            it.index == requestedAudioStreamIndex &&
                                it.type.equals("Audio", ignoreCase = true)
                        }
                    val range = video?.videoRangeType?.uppercase().orEmpty()
                    val requested =
                        resolvePlayerBackend(
                            defaultBackend = preferences.defaultPlayerBackend,
                            itemOverride = sessionBackend,
                            source =
                                BackendSourceDescriptor(
                                    container = version.container,
                                    videoCodec = video?.codec,
                                    audioCodec = audio?.codec,
                                    isHdrOrDolbyVision =
                                        range.contains("HDR") || range.contains("DOLBY") || range in setOf("DOVI", "DV", "HLG"),
                                ),
                            avPlayerCapabilities = provider.capabilities(PlayerBackend.AVPlayer),
                            backendPolicy = provider.backendPolicy,
                        )
                    requested.takeIf { it in availableBackends }
                        ?: provider.backendPolicy.concreteDefaultBackend.takeIf { it in availableBackends }
                        ?: throw IllegalStateException("No available concrete playback backend.")
                }
            } else {
                sessionBackend ?: PlayerBackend.AVPlayer
            }
        if (installedPlayerController == null || playerController.activeBackend != resolvedBackend) {
            val candidateOwner = TvControllerCandidateOwner()
            try {
                val candidate = withContext(dispatchers.work) { playerControllerFactory(resolvedBackend).also(candidateOwner::acquire) }
                currentCoroutineContext().ensureActive()
                if (closed) return false
                check(candidate.activeBackend == resolvedBackend) { "Controller backend differs from requested backend." }
                val outgoing = installedPlayerController
                observeJob?.cancel()
                observeJob = null
                videoOutputJob?.cancel()
                videoOutputJob = null
                try {
                    transferController(candidateOwner)
                } finally {
                    outgoing?.release()
                }
            } finally {
                candidateOwner.releaseUntransferred()
            }
        }
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackHealthGeneration, itemId, playerController.activeBackend)
        playbackHealthCoordinator.start(generation = playbackHealthGeneration, context = playbackHealthSessionContext())
        if (!qualityInitialized) {
            qualityOverrideExplicit = false
            selectedQualityPolicy = preferences.effectiveDefaultQualityPolicy(playerController.activeBackend)
            selectedQualityMaxBitrate = selectedQualityPolicy.maxBitrateBps
            selectedQualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault.takeIf { selectedQualityMaxBitrate != null }
            qualityInitialized = true
        }
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
                    playbackInfoPlanner
                        .plan(
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
                                    backend = playerController.activeBackend,
                                    diagnosticSessionSequence = diagnosticSession,
                                ),
                        ).also { validatePlannedSelection(it, itemId, version.id, selectedQualityPolicy, requestedSubtitleSelection) }
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
                currentCoroutineContext().ensureActive()
                publishFailure(planningPlaybackError(exception))
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
        expectPreparedVideoOutput()
        logTvDiagnostic(
            stage = PlaybackDiagnosticStage.Prepare,
            event = PlaybackDiagnosticEvent.PrepareDispatched,
        )
        applyInitialEmbeddedSelections(enrichedPlan)
        hostTransportEpoch += 1L
        if (desiredPlayWhenReady) playerController.play() else playerController.pause()
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
            reduce { TvPlaybackSessionChange.QueueChanged(null, queuePresentation) }
            return
        }
        episodeQueue?.takeIf { queue -> queue.any { episode -> episode.id == currentItemId } }?.let { queue ->
            val currentIndex = queuePresentation.currentIndex
            nextEpisodeItem = queue.getOrNull(currentIndex + 1)
            nextEpisodePresentation = queuePresentation.items.getOrNull(currentIndex + 1)?.media
            queueLoadPending = false
            queuePresentation = queuePresentation.copy(isPending = false)
            reduce { TvPlaybackSessionChange.QueueChanged(nextEpisodePresentation, queuePresentation) }
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
        reduce { TvPlaybackSessionChange.QueueChanged(null, queuePresentation) }
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
        val controller = playerController
        observeJob =
            scope.launch {
                controller.playbackState.collect { playbackState ->
                    if (installedPlayerController !== controller) return@collect
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
        if (controllerInstallInFlight) return
        val prepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch
        if (healthPrepareEpoch != prepareEpoch) expectPreparedVideoOutput()
        if (pendingReplacementSeek != null && playbackState.status != PlaybackStatus.Failed) {
            publish(playbackState)
            return
        }
        retireResolvedAudioFallback(playbackState)
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
        playbackReportingCoordinator.onPlaybackState(playbackState, lastStatus, prepareEpoch = prepareEpoch)
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

    /** Recovery may spend allowances or command the controller only for the installed current plan. */
    private fun canHandlePlaybackSessionRecovery(): Boolean =
        !closed &&
            !startInFlight &&
            installedPlayerController != null &&
            selectedMediaSourceId != null &&
            plan?.itemId == currentItemId

    private fun maybeHandlePlaybackSessionRecovery(
        playbackState: PlaybackState,
        pausedOverride: Boolean? = null,
    ): TvSessionRecoveryResult {
        if (!canHandlePlaybackSessionRecovery()) return TvSessionRecoveryResult.NotHandled
        val result =
            playbackSessionRecoveryPolicy.decide(
                PlaybackSessionRecoveryInput(
                    generation = playbackHealthGeneration,
                    itemId = currentItemId,
                    plan = plan,
                    playbackState = playbackState,
                    state = playbackSessionRecoveryState,
                    pendingAudioRecovery = pendingAudioRecoveryTarget(),
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

            // The in-flight Audio recovery owns this failure; its silent exit re-evaluates it.
            PlaybackSessionRecoveryDecision.Deferred -> TvSessionRecoveryResult.NonTerminal

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
                    audioRecovery = decision.target,
                    pausedOverride = pausedOverride,
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
                    pausedOverride = pausedOverride,
                )
                TvSessionRecoveryResult.RetryScheduled
            }

            is PlaybackSessionRecoveryDecision.SubtitleUnavailable -> {
                if (plan?.plannedSubtitle is PlannedSubtitle.LocalAsset) {
                    repairMissingLocalSubtitle()
                } else {
                    markSubtitleFallbackUnavailable(decision.target)
                }
                TvSessionRecoveryResult.NonTerminal
            }
        }
    }

    /**
     * Hands planned activation gaps of the installed [playbackPlan] to one recovery decision.
     *
     * A selected DirectPlay Audio target without an embedded descriptor, including selected external
     * Audio, never arms a native activation deadline. The target's stream index equals
     * `selectedAudioStreamIndex` in decorated plans. The snapshot keeps the plan start position because
     * a paused VLCKit prepare temporarily reports native time 0.
     *
     * Only ordinary Retry passes [includeInadmissibleAudio]: a descriptor stamped not DirectPlay-admissible
     * is then a gap only while it is the exact unresolved [audioRecoveryTarget] an earlier fallback
     * recorded and the snapshot is not that exact target's Active. A server-approved initial inadmissible
     * track has no record and stays exempt.
     */
    private fun maybeStartInstalledUnavailableFallback(
        playbackPlan: PlaybackPlan,
        includeInadmissibleAudio: Boolean = false,
        positionOverride: Long? = null,
        pausedOverride: Boolean? = null,
    ) {
        val controller = installedPlayerController ?: return
        if (plan !== playbackPlan || !canHandlePlaybackSessionRecovery()) return
        val nativeState = controller.playbackState.value
        val audioTarget =
            playbackPlan.audioActivationTarget?.takeIf { target ->
                playbackPlan.streamMode == StreamMode.DirectPlay &&
                    playbackPlan.embeddedAudioTracks.none { track ->
                        track.jellyfinStreamIndex == target.streamIndex &&
                            (
                                !includeInadmissibleAudio ||
                                    track.directPlayAdmissible ||
                                    audioRecoveryTarget != target ||
                                    (nativeState.audioActivation as? AudioActivationState.Active)?.target == target
                            )
                    }
            }
        val subtitleTarget =
            (playbackPlan.plannedSubtitle as? PlannedSubtitle.Unavailable)
                ?.takeIf { unavailable -> unavailable.allowEncodeFallback && unavailable.normalizedFormat != null }
                ?.activationTarget
        if (audioTarget == null && subtitleTarget == null) return
        maybeHandlePlaybackSessionRecovery(
            nativeState.copy(
                positionMs = positionOverride ?: playbackPlan.startPositionMs,
                audioActivation =
                    audioTarget?.let { target -> AudioActivationState.Unavailable(target) }
                        ?: nativeState.audioActivation,
                subtitleActivation =
                    subtitleTarget?.let { target -> SubtitleActivationState.Unavailable(target) }
                        ?: nativeState.subtitleActivation,
            ),
            pausedOverride = pausedOverride,
        )
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
        hostTransportEpoch += 1L
        planEpoch += 1
        publish(playerController.playbackState.value)
    }

    private fun replanAtPosition(
        targetPositionMs: Long,
        requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
        forcePlay: Boolean = false,
        nonFatalSubtitleFallback: SubtitleActivationTarget? = null,
        subtitleFallbackGeneration: Long? = null,
        nonFatalLocalSubtitleFailure: Boolean = false,
        audioRecovery: AudioActivationTarget? = null,
        proposal: TvChangeProposal? = null,
        pausedOverride: Boolean? = null,
    ) {
        if (startInFlight) return
        val mediaSource = selectedMediaSourceId ?: return
        val target = targetPositionMs.coerceAtLeast(0L)
        val replanItemId = currentItemId
        val nativePausedAtRequest =
            pausedOverride ?: (pendingReplacementSeek == null && playerController.playbackState.value.status == PlaybackStatus.Paused)
        val playbackShouldPlayAtRequest = desiredPlayWhenReady && (forcePlay || !nativePausedAtRequest)
        val playbackIntentRevisionAtRequest = playbackIntentRevision
        val admittedRecovery = proposal == null && !nonFatalLocalSubtitleFailure
        if (proposal == null) {
            // A recovery or repair takes the slot from a pending user change without a notice and
            // plans from installed inputs; a user change marks health and credit only at commit.
            // This request's own settlement reconsiders a waiting repair fact.
            if (pendingProposal != null) {
                pendingProposal = null
                logTvTransition(PlaybackTransitionReason.ProposalPreempted)
            }
            localSubtitleSelectionJob?.cancel()
            localSubtitleSelectionJob = null
            playbackHealthCoordinator.markExclusion(PlaybackHealthExclusionReason.Replan)
            if (nonFatalSubtitleFallback == null) {
                invalidateSubtitleFallback()
                // A superseding request must not inherit the installed recovery's
                // unconsumed credit; an Encode continuation keeps that stream's credit.
                pendingRecoveredPlaybackGuidance = false
                pendingRecoveredAutoQualityBps = null
            }
        }
        if (admittedRecovery) clearDisplacedRecovery()
        replanIsRecovery = admittedRecovery
        val subtitleStreamIndex = if (proposal != null) proposal.subtitleStreamIndex else requestedSubtitleStreamIndex
        val qualityPolicy = proposal?.qualityPolicy ?: selectedQualityPolicy
        val bitrateConstraint =
            requestPolicy.bitrateConstraint.takeUnless { constraint ->
                constraint == PlaybackBitrateConstraint.NoClientLimit
            } ?: activePlaybackBitrateConstraint(
                policy = qualityPolicy,
                runtimeQualityCapBps =
                    autoRecoveryState.runtimeQualityCapBps.takeIf {
                        proposal == null || proposal.qualityRecovery == TvQualityRecoveryEffect.Retain
                    },
            )
        replanJob?.cancel()
        val launchedJob =
            scope.launch {
                val requestJob = currentCoroutineContext()[Job]
                // A user change keeps current content on screen while it plans.
                if (proposal == null) reduce(TvPlaybackSessionChange.Loading)
                val playbackPlan =
                    try {
                        withContext(dispatchers.work) {
                            playbackInfoPlanner
                                .plan(
                                    session = session,
                                    itemId = replanItemId,
                                    mediaSourceId = mediaSource,
                                    startPositionTicks = millisecondsToTicks(target),
                                    audioStreamIndex = if (proposal != null) proposal.audioStreamIndex else requestedAudioStreamIndex,
                                    detailMediaStreams = mediaStreams,
                                    subtitleSelection = proposal?.subtitleSelection ?: requestedSubtitleSelection,
                                    localSubtitleAsset =
                                        (if (proposal != null) proposal.localSubtitleAsset else requestedLocalSubtitleAsset)
                                            ?.toPlaybackAsset(),
                                    maxStreamingBitrate = if (proposal != null) proposal.qualityMaxBitrate else selectedQualityMaxBitrate,
                                    qualityPolicy = qualityPolicy,
                                    qualityCapOrigin = if (proposal != null) proposal.qualityCapOrigin else selectedQualityCapOrigin,
                                    sourceContainer = selectedSourceContainer,
                                    requestPolicy =
                                        requestPolicy.copy(
                                            backend = playerController.activeBackend,
                                            diagnosticSessionSequence = diagnosticSession,
                                            bitrateConstraint = bitrateConstraint,
                                        ),
                                ).also {
                                    validatePlannedSelection(
                                        it,
                                        replanItemId,
                                        mediaSource,
                                        qualityPolicy,
                                        proposal?.subtitleSelection ?: requestedSubtitleSelection,
                                    )
                                }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logTvDiagnostic(
                            stage = PlaybackDiagnosticStage.Planner,
                            event = PlaybackDiagnosticEvent.Failed,
                            exception = exception,
                        )
                        // An ordinary Throwable resume can win over cancellation;
                        // reject stale catch publication before any branch.
                        currentCoroutineContext().ensureActive()
                        if (proposal != null) {
                            when {
                                !isCurrentProposal(proposal) ->
                                    dropProposal(proposal, PlaybackTransitionReason.ProposalStale, requestJob)
                                canRejectHealthily(proposal) ->
                                    rejectProposal(proposal, planningPlaybackError(exception), requestJob)
                                else -> {
                                    pendingProposal = null
                                    publishFailure(planningPlaybackError(exception))
                                }
                            }
                            return@launch
                        }
                        if (audioRecovery != null && !isCurrentAudioFallback(audioRecovery, replanItemId)) {
                            releaseSilentAudioRecovery(requestJob)
                            return@launch
                        }
                        if (nonFatalLocalSubtitleFailure) {
                            publish(playerController.playbackState.value)
                        } else if (
                            nonFatalSubtitleFallback != null &&
                            isCurrentSubtitleFallback(
                                target = nonFatalSubtitleFallback,
                                generation = subtitleFallbackGeneration,
                                itemId = replanItemId,
                            )
                        ) {
                            markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                        } else if (nonFatalSubtitleFallback == null) {
                            publishFailure(planningPlaybackError(exception))
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
                if (audioRecovery != null && !isCurrentAudioFallback(audioRecovery, replanItemId)) {
                    releaseSilentAudioRecovery(requestJob)
                    return@launch
                }
                if (proposal != null && !isCurrentProposal(proposal)) {
                    dropProposal(proposal, PlaybackTransitionReason.ProposalStale, requestJob)
                    return@launch
                }
                // A new Audio choice retires the unresolved fallback when it commits; any other request
                // keeps the global refusal of DirectPlay while that fallback is unresolved.
                val unresolvedAudioRecovery =
                    audioRecoveryTarget.takeUnless { proposal?.operation == TvPlaybackChangeOperation.Audio }
                if (unresolvedAudioRecovery != null && playbackPlan.streamMode == StreamMode.DirectPlay) {
                    if (nonFatalLocalSubtitleFailure) {
                        publish(playerController.playbackState.value)
                    } else {
                        if (proposal != null) pendingProposal = null
                        publishFailure(PlaybackError.UnsupportedMedia)
                    }
                    return@launch
                }
                val currentPlanState = plan
                val enrichedPlan =
                    playbackPlan
                        .withAudioActivationTarget()
                        .withSubtitleActivationTarget(
                            requestId = nextSubtitleActivationRequestId(),
                            selectedSubtitle = selectedSubtitleMediaStream(subtitleStreamIndex),
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
                if (audioRecovery != null && !isCurrentAudioFallback(audioRecovery, replanItemId)) {
                    releaseSilentAudioRecovery(requestJob)
                    return@launch
                }
                // Cancellation can land during the await, after Stop was queued.
                controllerInstallInFlight = true
                hostTransportEpoch += 1L
                try {
                    installedReportingMayBeStopped = true
                    playbackReportingCoordinator.stopNow(replacementSeekStopPosition ?: playerController.playbackState.value.positionMs)
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
                    // Accepted limit: a drop here follows the queued Stop, so reporting stays stopped.
                    if (audioRecovery != null && !isCurrentAudioFallback(audioRecovery, replanItemId)) {
                        releaseSilentAudioRecovery(requestJob)
                        return@launch
                    }
                    if (proposal != null && !isCurrentProposal(proposal)) {
                        dropProposal(proposal, PlaybackTransitionReason.ProposalStale, requestJob)
                        return@launch
                    }
                    proposal?.let(::commitProposal)
                    audioRecoveryJob = null
                    installPlan(enrichedPlan, resetReporting = true)
                    if (nonFatalSubtitleFallback != null) {
                        subtitleFallbackTarget = null
                    }
                    logTvDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.PrepareRequested,
                    )
                    playerController.prepare(enrichedPlan)
                    if (playerController.playbackState.value.status == PlaybackStatus.Failed) {
                        publishFailure(playerController.playbackState.value.error ?: PlaybackError.Unknown)
                        return@launch
                    }
                    expectPreparedVideoOutput()
                    logTvDiagnostic(
                        stage = PlaybackDiagnosticStage.Prepare,
                        event = PlaybackDiagnosticEvent.PrepareDispatched,
                    )
                    applyInitialEmbeddedSelections(enrichedPlan)
                    hostTransportEpoch += 1L
                    if (playbackIntentRevision == playbackIntentRevisionAtRequest) {
                        desiredPlayWhenReady = playbackShouldPlayAtRequest
                    }
                    if (desiredPlayWhenReady && queuedSeekTarget == null) {
                        playerController.play()
                    } else {
                        playerController.pause()
                    }
                    // Credit only a prepared recovery stream, by this request's own intent; the
                    // planner's forced retry relabels only its internal request as Compatibility.
                    when (requestPolicy.recoveryIntent) {
                        PlaybackRecoveryIntent.Compatibility -> pendingRecoveredPlaybackGuidance = true
                        PlaybackRecoveryIntent.Quality ->
                            pendingRecoveredAutoQualityBps =
                                (enrichedPlan.bitrateConstraint as? PlaybackBitrateConstraint.AutoSessionLimit)?.bitrateBps
                        PlaybackRecoveryIntent.Initial -> Unit
                    }
                    if (audioRecoveryTarget != null && enrichedPlan.streamMode != StreamMode.DirectPlay) {
                        // Only a repair install still carries a lineage here; this install resolves its root.
                        if (audioRecoveryTarget == proposalDisplacedAudioRecovery) clearDisplacedRecovery()
                        audioRecoveryTarget = null
                    }
                    if (queuedSeekTarget == null) {
                        pendingReplacementSeek = null
                        replacementSeekStopPosition = null
                    }
                    publish(playerController.playbackState.value)
                } finally {
                    controllerInstallInFlight = false
                    playbackHealthCoordinator.resumeAfterInstallation(
                        playerController.playbackState.value.takeIf { !closed && pendingReplacementSeek == null },
                    )
                }
                if (queuedSeekTarget != null) {
                    val newest = queuedSeekTarget
                    queuedSeekTarget = null
                    replacementSeekStopPosition = playerController.playbackState.value.positionMs
                    replanJob = null
                    newest?.let(::seekTo)
                    return@launch
                }
                maybeStartInstalledUnavailableFallback(enrichedPlan)
                reconsiderLocalSubtitleRepair(requestJob)
            }
        replanJob = launchedJob
        audioRecoveryJob = launchedJob.takeIf { audioRecovery != null }
    }

    /**
     * Pending only while the exact Audio recovery request that launched still owns the replan slot;
     * a retained [audioRecoveryTarget] alone is not ownership.
     */
    private fun pendingAudioRecoveryTarget(): AudioActivationTarget? =
        audioRecoveryTarget?.takeIf {
            val job = audioRecoveryJob
            job != null && job === replanJob && job.isActive
        }

    /**
     * The current plan's SubtitleEncode fallback target while its admitted request still owns the replan
     * slot; any other request invalidates the token first.
     */
    private fun pendingSubtitleFallbackTarget(): SubtitleActivationTarget? =
        subtitleFallbackTarget?.takeIf { target ->
            replanIsRecovery &&
                replanJob?.isActive == true &&
                plan?.subtitleActivationTarget == target &&
                target.itemId == currentItemId
        }

    /** Ends the recovery-displacing lineage together with its recorded exact roots. */
    private fun clearDisplacedRecovery() {
        proposalDisplacedRecovery = false
        proposalDisplacedAudioRecovery = null
        proposalDisplacedSubtitleRecovery = null
    }

    /**
     * An Audio recovery that exits without installing or publishing a failure releases its
     * ownership. While no newer request owns the slot, the current native state is re-evaluated
     * once through the ordinary handler, so a failure it deferred is never resurrected later.
     */
    private fun releaseSilentAudioRecovery(requestJob: Job?) {
        if (requestJob == null || audioRecoveryJob !== requestJob) return
        audioRecoveryJob = null
        if (replanJob !== requestJob || !canHandlePlaybackSessionRecovery()) return
        val current = playerController.playbackState.value
        if (current.status == PlaybackStatus.Failed) {
            onPlaybackState(current)
        } else {
            reconsiderLocalSubtitleRepair(requestJob)
        }
    }

    /**
     * The installed local asset went missing. With no pending change or Audio request this repairs once
     * from installed inputs; otherwise a fact qualified to that asset waits for the owner to settle.
     */
    private fun repairMissingLocalSubtitle() {
        val assetId = requestedLocalSubtitleAsset?.id
        val sourceId = selectedMediaSourceId
        if (assetId != null && sourceId != null && (pendingProposal != null || pendingAudioRecoveryTarget() != null)) {
            localSubtitleRepair = TvLocalSubtitleRepair(assetId, currentItemId, sourceId)
            return
        }
        localSubtitleRepair = null
        markLocalSubtitleUnavailable()
    }

    /**
     * One reconsideration when the change or Audio request a repair fact waited for settles. A
     * replacement that no longer uses the asset clears the fact; a newer owner keeps it waiting.
     */
    private fun reconsiderLocalSubtitleRepair(settledJob: Job? = null) {
        val repair = localSubtitleRepair ?: return
        val audioPending = pendingAudioRecoveryTarget() != null && audioRecoveryJob !== settledJob
        if (pendingProposal != null || audioPending) return
        localSubtitleRepair = null
        if (
            repair.itemId == currentItemId &&
            repair.mediaSourceId == selectedMediaSourceId &&
            requestedLocalSubtitleAsset?.id == repair.assetId
        ) {
            markLocalSubtitleUnavailable()
        }
    }

    private fun installPlan(
        playbackPlan: PlaybackPlan,
        resetReporting: Boolean,
    ) {
        // An owned replacement is a new attempt; clear a failure published
        // after the existing early Loading reset.
        if (_state.value.phase == TvPlaybackPhase.Failed) {
            reduce(TvPlaybackSessionChange.Loading)
        }
        plan = playbackPlan
        updatePlaybackHealthSessionContext()
        hostTransportEpoch += 1L
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
        installedReportingMayBeStopped = false
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

    private fun activePlaybackBitrateConstraint(
        policy: PlaybackQualityPolicy = selectedQualityPolicy,
        runtimeQualityCapBps: Long? = autoRecoveryState.runtimeQualityCapBps,
    ): PlaybackBitrateConstraint =
        if (policy.mode == PlaybackQualityMode.Auto) {
            runtimeQualityCapBps
                ?.let(PlaybackBitrateConstraint.AutoSessionLimit::of)
                ?: PlaybackBitrateConstraint.NoClientLimit
        } else {
            policy.toBitrateConstraint()
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
                            repairMissingLocalSubtitle()
                        }
                    }
            }
    }

    private fun markLocalSubtitleUnavailable() {
        requestedSubtitleStreamIndex = null
        requestedLocalSubtitleAsset = null
        requestedSubtitleSelection = SubtitleSelectionIntent.Off
        reduce { TvPlaybackSessionChange.LocalSubtitleChanged(false) }
        persistSubtitleSelection(SubtitleSelectionIntent.Off)
        playerController.selectEmbeddedSubtitle(null)
        publishLocalSubtitleUnavailableNotice()
        replanAtPosition(
            playerController.playbackState.value.positionMs,
            nonFatalLocalSubtitleFailure = true,
        )
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

    private fun selectedSubtitleMediaStream(subtitleStreamIndex: Int? = requestedSubtitleStreamIndex): PlaybackMediaStream? {
        val streamIndex = subtitleStreamIndex ?: return null
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

    /**
     * Native exact Active resolves the recorded Audio fallback for the current plan and item. Retry also
     * samples the installed controller before native retry so an uncollected Active is not lost to Pending.
     * A lineage that displaced exactly this recovery ends with it, including while its change is pending.
     * Shared recovery budgets, selection and persistence are untouched.
     */
    private fun retireResolvedAudioFallback(playbackState: PlaybackState) {
        val target = audioRecoveryTarget ?: return
        val currentPlan = plan ?: return
        if (
            (playbackState.audioActivation as? AudioActivationState.Active)?.target == target &&
            currentPlan.audioActivationTarget == target &&
            target.itemId == currentItemId &&
            currentPlan.itemId == currentItemId
        ) {
            if (target == proposalDisplacedAudioRecovery) clearDisplacedRecovery()
            audioRecoveryTarget = null
        }
    }

    /**
     * Whether the Audio fallback request captured as [target] for [itemId] still owns its record. The
     * installed controller's exact Active resolves it before the collector observes that sample.
     *
     * Accepted limit: when this turns false while `stopNow` is suspended, installation is dropped after
     * Stop was queued. Native playback continues, but progress, pause and final Stop reporting stay
     * disabled until another install, explicit Retry or item change, and resume keeps the captured
     * fallback position.
     */
    private fun isCurrentAudioFallback(
        target: AudioActivationTarget,
        itemId: String,
    ): Boolean =
        !closed &&
            !startInFlight &&
            audioRecoveryTarget == target &&
            plan?.audioActivationTarget == target &&
            target.itemId == itemId &&
            currentItemId == itemId &&
            plan?.itemId == itemId &&
            (installedPlayerController?.playbackState?.value?.audioActivation as? AudioActivationState.Active)
                ?.target != target

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
                PlaybackSessionRecoveryDecision.Deferred,
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
                    sessionSequence = diagnosticSession,
                    streamMode = plan?.streamMode,
                    requestPolicy = requestPolicy?.diagnosticClass(),
                    clientTrigger = requestPolicy?.clientTrigger,
                    recoveryIntent = requestPolicy?.recoveryIntent,
                    reason =
                        PlaybackTransitionReason.PendingAudioDeferred.takeIf {
                            decision == PlaybackSessionRecoveryDecision.Deferred
                        },
                ),
            ),
        )
    }

    /** A rejected user change: closed operation and error category only. */
    private fun logTvChangeRejected(
        operation: TvPlaybackChangeOperation,
        error: PlaybackError?,
    ) {
        val controller = installedPlayerController ?: return
        tvPlaybackDiagnosticLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Planner,
                    event = PlaybackDiagnosticEvent.Rejected,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = controller.runtimeDiagnostics.value.prepareEpoch,
                    sessionSequence = diagnosticSession,
                    streamMode = plan?.streamMode,
                    errorCategory = error,
                    operation = operation.diagnosticOperation(),
                    playbackChangeResult = PlaybackChangeResult.CurrentPlaybackKept,
                ),
            )
        }
    }

    /** A user change that settled silently at its owning transition. */
    private fun logTvTransition(reason: PlaybackTransitionReason) {
        val controller = installedPlayerController ?: return
        tvPlaybackDiagnosticLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Planner,
                    event = PlaybackDiagnosticEvent.Ended,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                    backend = controller.activeBackend,
                    prepareSequence = controller.runtimeDiagnostics.value.prepareEpoch,
                    sessionSequence = diagnosticSession,
                    streamMode = plan?.streamMode,
                    reason = reason,
                ),
            )
        }
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
                    sessionSequence = diagnosticSession,
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
                    sessionSequence = diagnosticSession,
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
                    sessionSequence = diagnosticSession,
                    streamMode = plan?.streamMode,
                    exceptionType = exception?.playbackExceptionType(),
                    errorCategory = error,
                    terminalOutcome = terminalOutcome,
                    allocatedBufferBytes = runtime.allocatedBufferBytes.takeIf { terminalOutcome != null },
                    bufferedAheadMs = runtime.bufferedAheadMs.takeIf { terminalOutcome != null },
                    libVlcCachePercent = runtime.libVlcCachePercent.takeIf { terminalOutcome != null },
                    // Apple backends expose no renderer-target evidence, so terminal records say Unknown.
                    outputColor = runtime.outputColor.takeIf { terminalOutcome != null },
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
                    sessionSequence = diagnosticSession,
                    streamMode = plan?.streamMode,
                    healthSignal = signal,
                    allocatedBufferBytes = runtime.allocatedBufferBytes,
                    bufferedAheadMs = runtime.bufferedAheadMs,
                    libVlcCachePercent = runtime.libVlcCachePercent,
                    outputColor = runtime.outputColor,
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
                    sessionSequence = diagnosticSession,
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
                    outputColor = runtime.outputColor,
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
                    sessionSequence = diagnosticSession,
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
                sessionSequence = diagnosticSession,
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
                sessionSequence = diagnosticSession,
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
                    sessionSequence = diagnosticSession,
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
        val visiblePlaybackState =
            if (pendingReplacementSeek != null && _state.value.phase == TvPlaybackPhase.Failed) {
                playbackState.copy(status = PlaybackStatus.Failed)
            } else {
                playbackState
            }
        val projectedPlaybackState = visiblePlaybackState.withReplacementSeekTarget(pendingReplacementSeek)
        logTvTrackDiagnostics(playbackState)
        val confirmedAudio = confirmedAudioStreamIndex(projectedPlaybackState)
        val confirmedSubtitle = confirmedSubtitleStreamIndex(projectedPlaybackState)
        val duration = projectedPlaybackState.durationMs
        val inheritedQualityPolicy = preferences.effectiveDefaultQualityPolicy(controller.activeBackend)
        reduce {
            TvPlaybackSessionChange.Projected(
                TvPlaybackUiState(
                    playerInstalled = installedPlayerController != null,
                    playerIdentity = playerIdentity,
                    hostTransportEpoch = hostTransportEpoch,
                    pendingReplacementSeek = pendingReplacementSeek != null,
                    desiredPlayWhenReady = desiredPlayWhenReady,
                    backend = controller.activeBackend,
                    backendChoices = availableBackends,
                    backendSwitchPending = backendSwitchPending,
                    phase =
                        when {
                            pendingReplacementSeek != null && projectedPlaybackState.status != PlaybackStatus.Failed ->
                                TvPlaybackPhase.Active
                            else ->
                                when (projectedPlaybackState.status) {
                                    PlaybackStatus.Failed -> TvPlaybackPhase.Failed
                                    PlaybackStatus.Completed ->
                                        when {
                                            retainCompletedHost -> TvPlaybackPhase.Active
                                            terminalCompleted -> TvPlaybackPhase.Completed
                                            else -> TvPlaybackPhase.Loading
                                        }
                                    PlaybackStatus.Idle, PlaybackStatus.Loading -> TvPlaybackPhase.Loading
                                    else -> TvPlaybackPhase.Active
                                }
                        },
                    status = projectedPlaybackState.status,
                    positionMs = projectedPlaybackState.positionMs,
                    durationMs = duration,
                    bufferedPositionMs = projectedPlaybackState.bufferedPositionMs,
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
                    activeSegment = activeSegment(projectedPlaybackState),
                    nextEpisode = nextEpisodePresentation,
                    upNextVisible =
                        nextEpisodeItem != null &&
                            !autoplayDismissed &&
                            (
                                retainCompletedHost ||
                                    (
                                        duration != null &&
                                            duration - projectedPlaybackState.positionMs <= UP_NEXT_WINDOW_MS
                                    )
                            ),
                    queue = queuePresentation,
                    nextUpCountdownSeconds = autoplayCountdownSeconds,
                    nextUpDismissed = autoplayDismissed,
                    stillWatchingVisible = stillWatchingState.isPromptVisible,
                    playbackSpeed = projectedPlaybackState.playbackSpeed,
                    playbackSpeedChoices = tvPlaybackSpeedChoices(projectedPlaybackState.playbackSpeed),
                    subtitleStyle = projectedPlaybackState.subtitleStyle,
                    subtitleStyleSupported = canApplySubtitleStyle(),
                    playbackGuidance = playbackHealthCoordinator.guidance,
                    playbackActionNotice = playbackActionNotice,
                    playbackActions = playbackActionNotice?.actions.orEmpty().toList(),
                    subtitleNotice = subtitleNotice,
                    preferences = preferences,
                    error = projectedPlaybackState.error,
                ),
            )
        }
    }

    private fun publishFailure(error: PlaybackError) {
        playbackHealthCoordinator.resumeAfterInstallation(null)
        // A published failure ends any recovery-displacing change chain.
        clearDisplacedRecovery()
        logTvDiagnostic(
            stage = PlaybackDiagnosticStage.NativePlayer,
            event = PlaybackDiagnosticEvent.TerminalError,
            terminalOutcome = PlaybackTerminalOutcome.Failed,
            error = error,
        )
        reduce { TvPlaybackSessionChange.Failed(error) }
    }

    // Mirrors the Compose player's planning-error mapping; change both together.
    private fun planningPlaybackError(exception: Throwable): PlaybackError {
        val copyFailure = exception as? PlaybackPlanningException.SourceVideoCopyUnsupported
        val isDeterministicCapabilityFailure =
            when (copyFailure?.cause) {
                null -> copyFailure != null
                is PlaybackPlanningException.SourceVideoCopyRejected,
                PlaybackPlanningException.NoSupportedStream,
                -> true

                else -> false
            }
        val rootFailure = copyFailure?.cause ?: exception
        return when {
            isDeterministicCapabilityFailure -> PlaybackError.UnsupportedMedia
            rootFailure == PlaybackPlanningException.NoSupportedStream -> PlaybackError.UnsupportedMedia
            rootFailure is PlaybackPlanningException.RemoteRequestFailed && rootFailure.isNetworkFailure ->
                PlaybackError.Network
            else -> PlaybackError.Unknown
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

/** An immutable user change: its proposed inputs and the authority it must still hold to commit. */
private data class TvChangeProposal(
    val operation: TvPlaybackChangeOperation,
    val requestIdentity: Long,
    val controller: PlayerController,
    val planEpoch: Long,
    val itemId: String,
    val mediaSourceId: String,
    val targetPositionMs: Long,
    val audioStreamIndex: Int?,
    val explicitAudioStreamIndex: Int?,
    val subtitleSelection: SubtitleSelectionIntent,
    val subtitleStreamIndex: Int?,
    val localSubtitleAsset: LocalSubtitleAsset?,
    val qualityPolicy: PlaybackQualityPolicy,
    val qualityMaxBitrate: Long?,
    val qualityCapOrigin: PlaybackQualityCapOrigin?,
    val qualityOverrideExplicit: Boolean,
    val qualityRecovery: TvQualityRecoveryEffect,
)

/** What a committed quality change does to the session's Auto recovery state. */
private enum class TvQualityRecoveryEffect {
    Retain,
    Reset,
    ClearRuntimeCap,
}

/** The installed local asset a deferred repair is qualified to. */
private data class TvLocalSubtitleRepair(
    val assetId: String,
    val itemId: String,
    val mediaSourceId: String,
)

private fun TvPlaybackChangeOperation.diagnosticOperation(): PlayerOperation =
    when (this) {
        TvPlaybackChangeOperation.Backend -> PlayerOperation.BackendSwitch
        TvPlaybackChangeOperation.Quality -> PlayerOperation.QualityChange
        TvPlaybackChangeOperation.Audio -> PlayerOperation.SelectEmbeddedAudio
        TvPlaybackChangeOperation.Subtitle -> PlayerOperation.SelectEmbeddedSubtitle
        TvPlaybackChangeOperation.LocalSubtitle -> PlayerOperation.SubtitleActivation
        TvPlaybackChangeOperation.Seek -> PlayerOperation.SeekTo
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
