// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import co.touchlab.kermit.Logger
import com.jellyscope.core.data.local.DesktopPlayerVolumeStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerVolumeController
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.diagnosticName
import com.jellyscope.core.domain.playback.droppedFramePoll
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack
import com.jellyscope.core.domain.playback.vlcSlaveSubtitleUrl
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.util.nextPositiveGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class DesktopLibVlcPlayerController private constructor(
    private val session: Session,
    private val stateScope: CoroutineScope,
    private val localSubtitleFileStore: LocalSubtitleFileStore?,
    private val volumeStore: DesktopPlayerVolumeStore?,
    private val engineFactory: () -> DesktopVlcEngine,
    private val nativeDispatcher: CoroutineDispatcher,
    private val monotonicTimeNanos: () -> Long,
) : PlayerController,
    PlayerVolumeController,
    DesktopVlcVideoOutput {
    constructor(
        session: Session,
        stateScope: CoroutineScope,
        localSubtitleFileStore: LocalSubtitleFileStore? = null,
        volumeStore: DesktopPlayerVolumeStore? = null,
    ) : this(
        session = session,
        stateScope = stateScope,
        localSubtitleFileStore = localSubtitleFileStore,
        volumeStore = volumeStore,
        engineFactory = DesktopVlcEngineFactory::create,
        nativeDispatcher =
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "jellyscope-desktop-libvlc").apply { isDaemon = true }
                }.asCoroutineDispatcher(),
        monotonicTimeNanos = System::nanoTime,
    )

    internal constructor(
        session: Session,
        stateScope: CoroutineScope,
        engine: DesktopVlcEngine,
        localSubtitleFileStore: LocalSubtitleFileStore? = null,
        volumeStore: DesktopPlayerVolumeStore? = null,
        nativeDispatcher: CoroutineDispatcher,
        monotonicTimeNanos: () -> Long = System::nanoTime,
    ) : this(
        session = session,
        stateScope = stateScope,
        localSubtitleFileStore = localSubtitleFileStore,
        volumeStore = volumeStore,
        engineFactory = { engine },
        nativeDispatcher = nativeDispatcher,
        monotonicTimeNanos = monotonicTimeNanos,
    )

    internal constructor(
        session: Session,
        stateScope: CoroutineScope,
        engineFactory: () -> DesktopVlcEngine,
        nativeDispatcher: CoroutineDispatcher,
        monotonicTimeNanos: () -> Long = System::nanoTime,
    ) : this(
        session = session,
        stateScope = stateScope,
        localSubtitleFileStore = null,
        volumeStore = null,
        engineFactory = engineFactory,
        nativeDispatcher = nativeDispatcher,
        monotonicTimeNanos = monotonicTimeNanos,
    )

    private val nativeScope = CoroutineScope(SupervisorJob() + nativeDispatcher)
    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)
    private val logger = Logger.withTag("DesktopLibVlc")
    private val released = AtomicBoolean(false)
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()
    private var offlinePath: String? = null
    private var offlineSidecarPath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null

    /** Platform DI seam; the public controller constructors remain remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    private val playLifecycleLock = Any()
    private val surfaceLifecycleLock = Any()
    private val releaseDetachCallbacks = mutableListOf<() -> Unit>()
    private val _playbackState =
        MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
            ),
        )
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    private val _videoPresentationReady = MutableStateFlow(false)
    override val videoPresentationReady: StateFlow<Boolean> = _videoPresentationReady.asStateFlow()
    private val _volumeState = MutableStateFlow(volumeStore?.restoredState ?: PlayerVolumeState())
    override val volumeState: StateFlow<PlayerVolumeState> = _volumeState.asStateFlow()
    override val platformPlayer: Any?
        get() = this
    override val activeBackend: PlayerBackend = PlayerBackend.LibVlc
    override val appliesSubtitleStyle: Boolean = false

    /** Once unavailable, this prepare may not manufacture first-output evidence. */
    @Volatile
    private var displayedPictureCounterUnavailable = false

    override val playbackHealthMeasurementCapabilities
        get() =
            if (displayedPictureCounterUnavailable) {
                PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames
            } else {
                PlaybackHealthMeasurementCapabilities(
                    hasReliableBufferingTransitions = true,
                    hasDroppedFrameMeasurements = true,
                    hasReliableFirstVideoOutput = true,
                )
            }

    private val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    private val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> =
        droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> =
        videoOutputObservationsChannel.receiveAsFlow()
    override val videoOutputMeasurementCapabilities
        get() =
            if (displayedPictureCounterUnavailable) {
                VideoOutputMeasurementCapabilities.Unsupported
            } else {
                VideoOutputMeasurementCapabilities.DisplayedPictureCounter
            }

    @Volatile
    private var engine: DesktopVlcEngine? = null

    @Volatile
    private var generation = 0L

    @Volatile
    private var lastPlan: PlaybackPlan? = null

    @Volatile
    private var lastSubtitleAsset: SubtitleAsset? = null

    @Volatile
    private var preparedGeneration: Long? = null

    @Volatile
    private var nativePlayingGeneration: Long? = null

    @Volatile
    private var playIntent = false

    @Volatile
    private var playIssued = false

    @Volatile
    private var surface: DesktopVlcSurfaceHandle? = null

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    private var fillCrop = false

    @Volatile
    private var pendingStartPositionMs: Long? = null

    @Volatile
    private var startupTargetPositionMs = 0L

    @Volatile
    private var startupDisplayedPicturesBaseline: Long? = null

    @Volatile
    private var startupAudioSuppressed = false

    @Volatile
    private var releaseNativeCompleted = false

    @Volatile
    private var initializationError: PlaybackError? = null

    private val initializationInFlight = AtomicBoolean(false)

    @Volatile
    private var pendingUserSeekPositionMs: Long? = null

    @Volatile
    private var pendingAudioSelection: EmbeddedAudioSelection? = null

    @Volatile
    private var pendingSubtitleSelection: EmbeddedSubtitleSelection? = null

    @Volatile
    private var pendingSubtitleSelectionWasSet = false

    @Volatile
    private var lastRequestedAudioSelection: EmbeddedAudioSelection? = null

    @Volatile
    private var lastRequestedSubtitleSelection: EmbeddedSubtitleSelection? = null

    @Volatile
    private var lastRequestedSubtitleSelectionWasSet = false

    private var pollJob: Job? = null

    @Volatile
    private var previousLostPictures: Long? = null

    @Volatile
    private var previousLostPictureSampleMs: Long? = null

    private var startSeekJob: Job? = null
    private var userSeekTimeoutJob: Job? = null
    private var trackSelectionJob: Job? = null
    private val endOfStream = VlcEndOfStreamEvidence()

    /**
     * Activation publication is owned by the shared kernels, exactly as on mpv: the
     * exact-target guard, the single non-extending three-second confirmation
     * deadline and the allowlisted mapping diagnostics all live there. The
     * VLC-specific retry loop below only *drives* selection — elementary streams
     * exist only once playback has started — and never publishes state itself.
     */
    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = stateScope,
            platform = PlaybackDiagnosticPlatform.Desktop,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(audioActivation = activation) }
            },
        )
    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = stateScope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(subtitleActivation = activation) }
            },
            platform = PlaybackDiagnosticPlatform.Desktop,
        )

    /**
     * libvlc delivers events on its own callback thread while the poll loop
     * publishes from `stateScope`; both read-decide-update the same terminal
     * evidence and seek-pin state. They are funnelled through this unbounded
     * channel so a single consumer applies them in arrival order. A channel is
     * used rather than confining the consumer to [nativeDispatcher] because the
     * poll snapshot hops to that dispatcher, so a handler must never queue
     * behind it.
     */
    private val publications = Channel<DesktopVlcPublication>(Channel.UNLIMITED)

    init {
        startPublicationConsumer()
        launchEngineInitialization()
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (released.get()) return
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            offlinePath = null
            offlineSidecarPath = null
            // The outgoing native item may still be reading its artifact. The
            // exact lease is released by the serialized stop job, before the
            // replacement prepare job is allowed to run.
            enqueueNativeStopThenRelease(offlineLeaseHolder.detach())
        } else if (offlinePath == null) {
            _playbackState.update { current ->
                current.copy(status = PlaybackStatus.Failed, error = PlaybackError.OfflineArtifactUnavailable)
            }
            return
        }
        generation = nextPositiveGeneration(generation)
        val currentGeneration = generation
        pollJob?.cancel()
        resetDroppedFrameBaseline()
        startSeekJob?.cancel()
        userSeekTimeoutJob?.cancel()
        userSeekTimeoutJob = null
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        playIntent = false
        playIssued = false
        nativePlayingGeneration = null
        pendingStartPositionMs = plan.resumeSeekPositionMs()
        startupTargetPositionMs = plan.clampedStartPositionMs()
        startupDisplayedPicturesBaseline = null
        displayedPictureCounterUnavailable = false
        startupAudioSuppressed = true
        _videoPresentationReady.value = false
        pendingUserSeekPositionMs = null
        preparedGeneration = null
        pendingAudioSelection = null
        pendingSubtitleSelection = null
        pendingSubtitleSelectionWasSet = false
        lastRequestedAudioSelection = null
        lastRequestedSubtitleSelection = null
        lastRequestedSubtitleSelectionWasSet = false
        lastPlan = plan
        lastSubtitleAsset = subtitleAsset
        endOfStream.onPrepare(plan.startPositionMs)
        _runtimeDiagnostics.value =
            PlaybackRuntimeDiagnostics.EMPTY.copy(
                prepareEpoch = currentGeneration,
                presentationPath = DESKTOP_LIBVLC_PRESENTATION_PATH,
            )
        _playbackState.value =
            PlaybackState(
                status = if (initializationError == null) PlaybackStatus.Loading else PlaybackStatus.Failed,
                positionMs = plan.clampedStartPositionMs(),
                durationMs = null,
                bufferedPositionMs = 0L,
                playbackSpeed = plan.playbackSpeed,
                subtitleStyle = plan.subtitleStyle,
                audioActivation = AudioActivationState.None,
                subtitleActivation = SubtitleActivationState.None,
                error = initializationError,
            )
        audioActivationConfirmation.applyInitial(initialAudioActivationFor(plan))
        val plannedSubtitleTarget = plan.subtitleActivationTarget
        if (plannedSubtitleTarget == null) {
            subtitleActivationConfirmation.clear()
        } else {
            subtitleActivationConfirmation.begin(plannedSubtitleTarget)
        }
        if (initializationError != null) return
        nativeScope.launch {
            val active = engine ?: return@launch
            prepareNative(currentGeneration, plan, subtitleAsset, active)
        }
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (released.get()) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.LibVlc))
        val resolver =
            offlineArtifactResolver
                ?: return OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)
        stop()
        val lease =
            when (val resolution = resolver.acquireForOfflinePlan(plan, session.accountIdentity())) {
                is OfflineArtifactResolution.Available -> resolution.lease
                is OfflineArtifactResolution.Unavailable ->
                    return OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)
            }
        offlineLeaseHolder.replace(lease)
        offlinePath = lease.mainPathForController
        offlineSidecarPath = lease.sidecarPathsForController.firstOrNull()
        return try {
            prepare(plan)
            OfflinePrepareResult.Started
        } catch (_: Throwable) {
            offlinePath = null
            offlineSidecarPath = null
            enqueueNativeStopThenRelease(offlineLeaseHolder.detach())
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.LibVlc))
        }
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (released.get()) return
        lastRequestedAudioSelection = selection
        pendingAudioSelection = selection
        audioActivationConfirmation.begin(selection.target)
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        if (nativePlayingGeneration == generation) scheduleTrackSelection()
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (released.get()) return
        lastRequestedSubtitleSelection = selection
        lastRequestedSubtitleSelectionWasSet = true
        pendingSubtitleSelection = selection
        pendingSubtitleSelectionWasSet = true
        if (selection == null) {
            subtitleActivationConfirmation.clear()
            nativeScope.launch { engine?.selectSubtitleTrack(null) }
        } else {
            subtitleActivationConfirmation.begin(selection.target)
            trackSelectionJob?.cancel()
            trackSelectionJob = null
            if (nativePlayingGeneration == generation) scheduleTrackSelection()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (!speed.isFinite() || speed <= 0f || released.get()) return
        _playbackState.update { current -> current.copy(playbackSpeed = speed) }
        nativeScope.launch { engine?.setRate(speed) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        _playbackState.update { current -> current.copy(subtitleStyle = style) }
    }

    override fun setVolume(percent: Int) {
        val next = _volumeState.value.copy(volumePercent = percent.coerceIn(0, 100))
        _volumeState.value = next
        volumeStore?.submit(next)
        nativeScope.launch { engine?.setVolume(next.volumePercent) }
    }

    override fun setMuted(muted: Boolean) {
        val next = _volumeState.value.copy(muted = muted)
        _volumeState.value = next
        volumeStore?.submit(next)
        nativeScope.launch { engine?.setMuted(muted || startupAudioSuppressed) }
    }

    override fun play() {
        if (released.get()) return
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.COMMAND,
                detail = DesktopProbeToken.from("play"),
            ),
        )
        playIntent = true
        issuePlayIfReady()
    }

    override fun pause() {
        if (released.get()) return
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.COMMAND,
                detail = DesktopProbeToken.from("pause"),
            ),
        )
        playIntent = false
        playIssued = false
        resetDroppedFrameBaseline()
        nativeScope.launch { engine?.pause() }
        publishStatus(PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        if (released.get()) return
        val target = positionMs.coerceAtLeast(0L)
        resetDroppedFrameBaseline()
        startSeekJob?.cancel()
        startSeekJob = null
        pendingStartPositionMs = null
        pendingUserSeekPositionMs = target
        userSeekTimeoutJob?.cancel()
        userSeekTimeoutJob =
            stateScope.launch {
                delay(USER_SEEK_TIMEOUT_MS)
                if (pendingUserSeekPositionMs == target) {
                    pendingUserSeekPositionMs = null
                }
            }
        nativeScope.launch { engine?.seekTo(target) }
        _playbackState.update { current ->
            current.copy(
                status = if (playIntent) PlaybackStatus.Buffering else current.status,
                positionMs = target,
                bufferedPositionMs = target,
            )
        }
        endOfStream.ignoreNextSample()
    }

    override fun stop() {
        if (released.get()) return
        generation = nextPositiveGeneration(generation)
        resetDroppedFrameBaseline()
        playIntent = false
        playIssued = false
        preparedGeneration = null
        nativePlayingGeneration = null
        pendingStartPositionMs = null
        startupTargetPositionMs = 0L
        startupDisplayedPicturesBaseline = null
        displayedPictureCounterUnavailable = false
        startupAudioSuppressed = false
        _videoPresentationReady.value = false
        pendingUserSeekPositionMs = null
        pollJob?.cancel()
        startSeekJob?.cancel()
        userSeekTimeoutJob?.cancel()
        userSeekTimeoutJob = null
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        endOfStream.onStop()
        // Clearing through the kernels also cancels any armed confirmation deadline,
        // so a late timeout cannot publish Unavailable over the stopped session.
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        enqueueNativeStopThenRelease(offlineLeaseHolder.detach())
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
                error = null,
            )
        }
        offlinePath = null
        offlineSidecarPath = null
    }

    override fun retry() {
        if (released.get()) return
        val plan = lastPlan ?: return
        val audioSelection = lastRequestedAudioSelection
        val subtitleSelection = lastRequestedSubtitleSelection
        val subtitleSelectionWasSet = lastRequestedSubtitleSelectionWasSet
        val selectionDecision =
            retrySelectionDecision(
                audioSelection = audioSelection,
                subtitleIntent =
                    when {
                        !subtitleSelectionWasSet -> RetrySubtitleIntent.Unspecified
                        subtitleSelection == null -> RetrySubtitleIntent.ExplicitOff
                        else -> RetrySubtitleIntent.Selection(subtitleSelection)
                    },
                planSubtitleTarget = plan.subtitleActivationTarget,
            )
        val shouldPlay = playIntent
        val shouldReinitialize = initializationError != null && engine == null
        if (shouldReinitialize) initializationError = null
        prepare(
            plan,
            plan.subtitleAsset.takeUnless {
                plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
            },
        )
        selectionDecision.audioSelection?.let(::selectEmbeddedAudio)
        when (selectionDecision) {
            is RetrySelectionDecision.RestorePlan -> Unit
            is RetrySelectionDecision.ReassertSubtitleOff -> selectEmbeddedSubtitle(null)
            is RetrySelectionDecision.SelectSubtitle -> selectEmbeddedSubtitle(selectionDecision.selection)
        }
        if (shouldPlay) play()
        if (shouldReinitialize) launchEngineInitialization()
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        publications.close()
        droppedFrameMeasurementsChannel.close()
        videoOutputObservationsChannel.close()
        resetDroppedFrameBaseline()
        generation = nextPositiveGeneration(generation)
        playIntent = false
        playIssued = false
        nativePlayingGeneration = null
        pollJob?.cancel()
        startSeekJob?.cancel()
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        pendingStartPositionMs = null
        startupTargetPositionMs = 0L
        startupDisplayedPicturesBaseline = null
        displayedPictureCounterUnavailable = false
        startupAudioSuppressed = false
        _videoPresentationReady.value = false
        pendingUserSeekPositionMs = null
        userSeekTimeoutJob?.cancel()
        userSeekTimeoutJob = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        val detachedOfflineLease = offlineLeaseHolder.detach()
        nativeScope.launch {
            try {
                engine?.setEventListener(null)
                engine?.setDrawable(null)
                engine?.release()
            } finally {
                engine = null
                val detachCallbacks =
                    synchronized(surfaceLifecycleLock) {
                        surface = null
                        surfaceWidth = 0
                        surfaceHeight = 0
                        releaseNativeCompleted = true
                        releaseDetachCallbacks.toList().also { releaseDetachCallbacks.clear() }
                    }
                detachCallbacks.forEach { callback -> runCatching(callback) }
                nativeScope.cancel()
                (nativeDispatcher as? AutoCloseable)?.close()
                offlinePath = null
                offlineSidecarPath = null
                // Keep the lease until the native engine has actually been
                // released. Capturing this exact generation prevents a rapid
                // replacement from releasing a newer lease.
                detachedOfflineLease?.release()
            }
        }
    }

    override fun attachVideoSurface(surface: DesktopVlcSurfaceHandle) {
        if (released.get()) return
        val current = this.surface
        if (current != null && surface.generation < current.generation) return
        this.surface = surface
        nativeScope.launch {
            if (released.get() || this@DesktopLibVlcPlayerController.surface !== surface) return@launch
            engine?.setDrawable(surface.value)
            engine?.setFillCrop(fillCrop, surfaceWidth, surfaceHeight)
            issuePlayIfReady()
        }
    }

    override fun videoSurfaceUnavailable(generation: Long) {
        if (released.get() || surface?.generation?.let { current -> generation < current } == true) return
        failPlayback(
            expectedGeneration = this.generation,
            error = PlaybackError.UnsupportedMedia,
            stage = DesktopVlcFailureStage.SurfaceUnavailable,
        )
    }

    override fun resizeVideoSurface(
        generation: Long,
        width: Int,
        height: Int,
    ) {
        if (surface?.generation != generation || released.get()) return
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        nativeScope.launch {
            if (surface?.generation == generation) {
                engine?.setFillCrop(fillCrop, surfaceWidth, surfaceHeight)
            }
        }
    }

    override fun detachVideoSurface(
        generation: Long,
        onDetached: () -> Unit,
    ) {
        val detachAfterRelease =
            synchronized(surfaceLifecycleLock) {
                val current = surface
                when {
                    current?.generation != generation -> false
                    !released.get() -> null
                    releaseNativeCompleted -> false
                    else -> {
                        releaseDetachCallbacks += onDetached
                        true
                    }
                }
            }
        if (detachAfterRelease == false) {
            onDetached()
            return
        }
        if (detachAfterRelease == true) return
        nativeScope.launch {
            if (surface?.generation == generation) {
                engine?.setDrawable(null)
                surface = null
                surfaceWidth = 0
                surfaceHeight = 0
                playIssued = false
            }
            onDetached()
        }
    }

    override fun setFillCrop(crop: Boolean) {
        fillCrop = crop
        nativeScope.launch { engine?.setFillCrop(crop, surfaceWidth, surfaceHeight) }
    }

    /**
     * Queues native stop and then releases exactly [lease]. This controller's
     * native dispatcher is single-threaded, so a subsequent prepare/release
     * job cannot overtake the stop that proves the old file is no longer read.
     */
    private fun enqueueNativeStopThenRelease(lease: OfflineArtifactLease?) {
        nativeScope.launch {
            try {
                engine?.setEventListener(null)
                engine?.stop()
            } finally {
                // The lease belongs to this exact queued teardown job; no
                // newer holder generation is touched even if native stop
                // reports an exception while unwinding.
                lease?.release()
            }
        }
    }

    private suspend fun prepareNative(
        expectedGeneration: Long,
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
        suppliedEngine: DesktopVlcEngine? = engine,
    ) {
        if (!isCurrent(expectedGeneration) || preparedGeneration == expectedGeneration) return
        val active = suppliedEngine ?: return
        val authorizedUrl =
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlinePath?.let { path -> File(path).toURI().toString() }
                    ?: run {
                        failPlayback(
                            expectedGeneration = expectedGeneration,
                            error = PlaybackError.OfflineArtifactUnavailable,
                            stage = DesktopVlcFailureStage.Prepare,
                        )
                        return
                    }
            } else {
                credentialOriginGuard.authorizedUrl(plan.streamUrl, session.accessToken)
                    ?: run {
                        failPlayback(
                            expectedGeneration = expectedGeneration,
                            error = PlaybackError.Network,
                            stage = DesktopVlcFailureStage.Authorization,
                        )
                        return
                    }
            }
        val offline = plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
        val subtitleResource =
            if (offline) {
                offlineSidecarPath?.let { path -> File(path).toURI().toASCIIString() }
            } else {
                resolveSubtitleResource(subtitleAsset)
            }
        val subtitleTarget = plan.subtitleActivationTarget
        if (offline &&
            subtitleTarget?.kind == com.jellyscope.core.domain.playback.LocalSubtitleKind.ExternalText &&
            subtitleResource == null
        ) {
            failPlayback(
                expectedGeneration = expectedGeneration,
                error = PlaybackError.OfflineArtifactUnavailable,
                stage = DesktopVlcFailureStage.Prepare,
            )
            return
        }
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.PREPARE,
                mode = DesktopProbeToken.from(plan.streamMode.name),
                delivery =
                    DesktopProbeToken.from(
                        (plan.plannedSubtitle as? com.jellyscope.core.domain.playback.PlannedSubtitle.Track)
                            ?.deliveryMethod
                            ?.name,
                    ),
                detail = DesktopProbeToken.from(plan.plannedSubtitle::class.simpleName),
                resource = subtitleResource != null,
                accepted = subtitleTarget != null,
            ),
        )
        active.setEventListener(null)
        active.stop()
        val externalSubtitleRequested = subtitleTarget?.kind == com.jellyscope.core.domain.playback.LocalSubtitleKind.ExternalText
        var subtitleReady = !externalSubtitleRequested || subtitleResource != null
        try {
            active.prepare(
                authorizedMediaUrl = authorizedUrl,
                externalSubtitleResource = subtitleResource,
                startPositionMs = plan.startPositionMs,
            )
        } catch (_: DesktopVlcSubtitleException) {
            try {
                active.prepare(
                    authorizedMediaUrl = authorizedUrl,
                    externalSubtitleResource = null,
                    startPositionMs = plan.startPositionMs,
                )
            } catch (_: Throwable) {
                failPlayback(
                    expectedGeneration = expectedGeneration,
                    error = PlaybackError.UnsupportedMedia,
                    stage = DesktopVlcFailureStage.Prepare,
                )
                return
            }
            subtitleReady = false
            if (subtitleTarget != null) {
                publishSubtitleUnavailable(expectedGeneration, subtitleTarget)
            }
        } catch (_: Throwable) {
            failPlayback(
                expectedGeneration = expectedGeneration,
                error = PlaybackError.UnsupportedMedia,
                stage = DesktopVlcFailureStage.Prepare,
            )
            return
        }
        if (!isCurrent(expectedGeneration)) return
        active.setEventListener { event ->
            publications.trySend(DesktopVlcPublication.Native(expectedGeneration, event))
        }
        active.setVolume(_volumeState.value.volumePercent)
        active.setMuted(_volumeState.value.muted || startupAudioSuppressed)
        active.setRate(plan.playbackSpeed)
        active.setFillCrop(fillCrop, surfaceWidth, surfaceHeight)
        surface?.let { current -> active.setDrawable(current.value) }
        preparedGeneration = expectedGeneration
        if (pendingSubtitleSelectionWasSet && pendingSubtitleSelection == null) {
            active.selectSubtitleTrack(null)
        } else if (subtitleTarget != null && !pendingSubtitleSelectionWasSet && !subtitleReady) {
            // The plan target stays Pending from prepare(); only an unresolvable
            // sidecar fails it here, and only while it is still the live target.
            publishSubtitleUnavailable(expectedGeneration, subtitleTarget)
        }
        issuePlayIfReady()
    }

    private fun resolveSubtitleResource(asset: SubtitleAsset?): String? =
        when (asset) {
            null -> null
            is SubtitleAsset.JellyfinRemote ->
                // Path-only normalization; the origin guard still decides credentials.
                credentialOriginGuard.authorizedUrl(vlcSlaveSubtitleUrl(asset.url), session.accessToken)
            is SubtitleAsset.LocalFile ->
                localSubtitleFileStore
                    ?.resolvePath(asset.fileId)
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                    ?.toURI()
                    ?.toASCIIString()
        }

    private fun issuePlayIfReady() {
        val expectedGeneration: Long
        val expectedSurface: DesktopVlcSurfaceHandle
        synchronized(playLifecycleLock) {
            if (released.get()) return
            if (!playIntent) return
            if (playIssued) return
            if (preparedGeneration != generation) return
            if (surface == null) return
            if (_playbackState.value.status in TERMINAL_STATUSES) return
            playIssued = true
            expectedGeneration = generation
            expectedSurface = surface ?: return
        }
        nativeScope.launch {
            if (!isCurrent(expectedGeneration) || surface !== expectedSurface) {
                playIssued = false
                return@launch
            }
            val accepted = engine?.play() == true
            if (!accepted) {
                playIssued = false
                failPlayback(
                    expectedGeneration = expectedGeneration,
                    error = PlaybackError.UnsupportedMedia,
                    stage = DesktopVlcFailureStage.PlayRejected,
                )
                return@launch
            }
            publishStatus(PlaybackStatus.Buffering)
            startPolling(expectedGeneration)
            scheduleStartSeek(expectedGeneration)
        }
    }

    private fun scheduleStartSeek(expectedGeneration: Long) {
        val target = pendingStartPositionMs ?: return
        startSeekJob?.cancel()
        startSeekJob =
            stateScope.launch {
                repeat(START_SEEK_ATTEMPTS) {
                    delay(START_SEEK_RETRY_MS)
                    if (
                        !isCurrent(expectedGeneration) ||
                        !playIntent ||
                        pendingStartPositionMs != target
                    ) {
                        return@launch
                    }
                    val result =
                        withContext(nativeDispatcher) {
                            val active = engine ?: return@withContext StartPositionAttempt.notReady()
                            val snapshot = active.snapshot()
                            if (!snapshot.seekable) return@withContext StartPositionAttempt.notReady()
                            if (
                                abs(snapshot.positionMs - target) <=
                                START_POSITION_CONFIRM_TOLERANCE_MS
                            ) {
                                StartPositionAttempt(
                                    result = StartPositionResult.Confirmed,
                                    snapshot = snapshot,
                                )
                            } else {
                                active.seekTo(target)
                                StartPositionAttempt(
                                    result = StartPositionResult.FallbackSeekApplied,
                                    snapshot = snapshot,
                                )
                            }
                        }
                    if (result.result != StartPositionResult.NotReady) {
                        startupDisplayedPicturesBaseline = result.snapshot?.displayedPictures
                        pendingStartPositionMs = null
                        startSeekJob = null
                        endOfStream.ignoreNextSample()
                        _playbackState.update { current -> current.copy(positionMs = target) }
                        emitDesktopVlcProbe(
                            DesktopVlcProbeRecord(
                                action = DesktopVlcProbeAction.START_POSITION,
                                result = DesktopProbeToken.from(result.result.name),
                                positionMs = target,
                            ),
                        )
                        result.snapshot?.let { snapshot ->
                            publishVideoPresentationIfReady(expectedGeneration, snapshot)
                        }
                        return@launch
                    }
                }
                if (pendingStartPositionMs == target) {
                    pendingStartPositionMs = null
                }
                startSeekJob = null
            }
    }

    private fun scheduleTrackSelection() {
        if (trackSelectionJob?.isActive == true) return
        // Native readiness (Playing / a track list) starts the single non-extending
        // confirmation deadline; repeated track events cannot restart it, and it
        // resolves a pending target even if this driving job never reaches
        // exhaustion. Exhaustion below is the earlier, better-informed publication.
        pendingAudioSelection?.let { selection -> audioActivationConfirmation.armTimeout(selection.target) }
        pendingSubtitleSelection?.let { selection -> subtitleActivationConfirmation.armTimeout(selection.target) }
        val expectedGeneration = generation
        trackSelectionJob =
            stateScope.launch {
                try {
                    repeat(TRACK_SELECTION_ATTEMPTS) {
                        delay(TRACK_SELECTION_RETRY_MS)
                        if (!isCurrent(expectedGeneration)) return@launch
                        val resolved =
                            withContext(nativeDispatcher) {
                                applyPendingTrackSelections(engine ?: return@withContext false)
                            }
                        if (resolved) return@launch
                    }
                    val candidateCounts =
                        try {
                            withContext(nativeDispatcher) {
                                val active = engine ?: return@withContext 0 to 0
                                active.audioTracks().size to active.subtitleTracks().size
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            0 to 0
                        }
                    // Still-pending guard: a target confirmed (or replaced) while the
                    // last attempt ran must never be overwritten by this exhaustion.
                    pendingAudioSelection?.let { selection ->
                        if (_playbackState.value.audioActivation == AudioActivationState.Pending(selection.target)) {
                            audioActivationConfirmation.fail(
                                target = selection.target,
                                result = NativeTrackMappingResult.Timeout,
                                candidateCount = candidateCounts.first,
                            )
                        }
                    }
                    pendingSubtitleSelection?.let { selection ->
                        if (_playbackState.value.subtitleActivation == SubtitleActivationState.Pending(selection.target)) {
                            subtitleActivationConfirmation.fail(
                                target = selection.target,
                                reason = SubtitleActivationFailureReason.DesktopVlcSelectionExhausted,
                                result = NativeTrackMappingResult.Timeout,
                                candidateCount = candidateCounts.second,
                            )
                        }
                    }
                } finally {
                    if (trackSelectionJob === coroutineContext[Job]) {
                        trackSelectionJob = null
                    }
                }
            }
    }

    private fun applyPendingTrackSelections(active: DesktopVlcEngine): Boolean {
        var allResolved = true
        pendingAudioSelection?.let { selection ->
            val audioTracks = active.audioTracks()
            val singlePlannedAudioTrack = lastPlan?.embeddedAudioTracks?.singleOrNull()
            val confirmsSolePlannedAudioTrack =
                audioTracks.size <= 1 &&
                    nativePlayingGeneration == generation &&
                    singlePlannedAudioTrack?.jellyfinStreamIndex == selection.descriptor.jellyfinStreamIndex
            emitDesktopVlcProbe(
                DesktopVlcProbeRecord(
                    action = DesktopVlcProbeAction.AUDIO_COHORT,
                    nativeIds = audioTracks.map(DesktopVlcTrack::id),
                    count = lastPlan?.embeddedAudioTracks?.size,
                    ordinal = selection.descriptor.filteredContainerOrdinal,
                    accepted = confirmsSolePlannedAudioTrack,
                ),
            )
            if (confirmsSolePlannedAudioTrack) {
                pendingAudioSelection = null
                audioActivationConfirmation.confirm(selection.target)
                return@let
            }
            val exactCountOrdinalCandidate =
                audioTracks
                    .takeIf { tracks -> tracks.size == lastPlan?.embeddedAudioTracks?.size }
                    ?.getOrNull(selection.descriptor.filteredContainerOrdinal)
                    ?.id
            val resolution =
                exactCountOrdinalCandidate?.let { candidate ->
                    NativeTrackResolution(
                        candidate = candidate,
                        result = NativeTrackMappingResult.Active,
                        reason = NativeTrackMappingReason.Ordinal,
                    )
                } ?: resolveEmbeddedTrack(
                    descriptor = selection.descriptor,
                    orderedCandidates =
                        audioTracks.map { track ->
                            NativeTrackCandidate(
                                value = track.id,
                                stableSourceIndex = null,
                                codec = null,
                                language = null,
                                label = track.label,
                            )
                        },
                    trackKind = EmbeddedTrackKind.Audio,
                )
            if (
                resolution.result == NativeTrackMappingResult.Active &&
                resolution.candidate != null &&
                active.selectAudioTrack(resolution.candidate)
            ) {
                pendingAudioSelection = null
                audioActivationConfirmation.confirm(selection.target)
            } else {
                emitDesktopVlcProbe(
                    DesktopVlcProbeRecord(
                        action = DesktopVlcProbeAction.AUDIO_RESOLUTION,
                        result = DesktopProbeToken.from(resolution.result.name),
                        candidateId = resolution.candidate,
                        accepted = false,
                    ),
                )
                allResolved = false
            }
        }
        pendingSubtitleSelection?.let { selection ->
            val candidates = active.subtitleTracks()
            val plannedCohortSize = selection.descriptor.responseAuthoritativeCohortSize
            val exactCountOrdinalCandidate =
                if (
                    lastPlan?.streamMode == StreamMode.DirectPlay &&
                    nativePlayingGeneration == generation &&
                    plannedCohortSize != null &&
                    candidates.size == plannedCohortSize
                ) {
                    candidates
                        .getOrNull(selection.descriptor.filteredContainerOrdinal)
                        ?.id
                } else {
                    null
                }
            val resolution =
                exactCountOrdinalCandidate?.let { candidate ->
                    NativeTrackResolution(
                        candidate = candidate,
                        result = NativeTrackMappingResult.Active,
                        reason = NativeTrackMappingReason.Ordinal,
                    )
                } ?: resolveEmbeddedTrack(
                    descriptor = selection.descriptor,
                    orderedCandidates =
                        candidates.map { track ->
                            NativeTrackCandidate(
                                value = track.id,
                                stableSourceIndex = null,
                                codec = null,
                                language = null,
                                label = track.label,
                            )
                        },
                    trackKind = EmbeddedTrackKind.Subtitle,
                )
            val accepted =
                resolution.candidate
                    ?.takeIf { resolution.result == NativeTrackMappingResult.Active }
                    ?.let(active::selectSubtitleTrack) == true
            val mappingReason =
                if (exactCountOrdinalCandidate != null) {
                    "exact-response-cohort-ordinal"
                } else {
                    resolution.reason.name
                }
            emitDesktopVlcProbe(
                DesktopVlcProbeRecord(
                    action = DesktopVlcProbeAction.SUBTITLE_MAPPING,
                    nativeIds = candidates.map(DesktopVlcTrack::id),
                    ordinal = selection.descriptor.filteredContainerOrdinal,
                    count = plannedCohortSize,
                    detail = DesktopProbeToken.from(mappingReason),
                    result = DesktopProbeToken.from(resolution.result.name),
                    candidateId = resolution.candidate,
                    accepted = accepted,
                ),
            )
            if (accepted) {
                pendingSubtitleSelection = null
                subtitleActivationConfirmation.confirm(selection.target)
            } else {
                allResolved = false
            }
        }
        return allResolved
    }

    private fun startPublicationConsumer() {
        stateScope.launch {
            for (publication in publications) {
                when (publication) {
                    is DesktopVlcPublication.Native ->
                        handleEvent(publication.expectedGeneration, publication.event)
                    is DesktopVlcPublication.Poll ->
                        publishSnapshot(publication.expectedGeneration, publication.snapshot)
                }
            }
        }
    }

    private fun startPolling(expectedGeneration: Long) {
        pollJob?.cancel()
        pollJob =
            stateScope.launch {
                while (isCurrent(expectedGeneration)) {
                    delay(POLL_INTERVAL_MS)
                    val snapshot =
                        try {
                            withContext(nativeDispatcher) { engine?.snapshot() }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            null
                        }
                    if (snapshot == null) {
                        resetDroppedFrameBaseline()
                        continue
                    }
                    publications.trySend(DesktopVlcPublication.Poll(expectedGeneration, snapshot))
                }
            }
    }

    private fun publishSnapshot(
        expectedGeneration: Long,
        snapshot: DesktopVlcSnapshot,
    ) {
        if (!isCurrent(expectedGeneration)) return
        publishNativeState(expectedGeneration, snapshot.nativeState)
        publishVideoPresentationIfReady(expectedGeneration, snapshot)
        val currentStatus = _playbackState.value.status
        val pendingSeek = pendingUserSeekPositionMs
        val publishedPosition =
            if (pendingSeek == null) {
                snapshot.positionMs
            } else if (abs(snapshot.positionMs - pendingSeek) <= USER_SEEK_POSITION_TOLERANCE_MS) {
                pendingUserSeekPositionMs = null
                userSeekTimeoutJob?.cancel()
                userSeekTimeoutJob = null
                snapshot.positionMs
            } else {
                pendingSeek
            }
        endOfStream.onPositionSample(
            positionMs = publishedPosition,
            playing = currentStatus == PlaybackStatus.Playing,
            seekInFlight = startSeekJob?.isActive == true || pendingSeek != null,
        )
        _playbackState.update { current ->
            if (current.status in TERMINAL_STATUSES) {
                current
            } else {
                current.copy(
                    positionMs = publishedPosition,
                    durationMs = snapshot.durationMs ?: current.durationMs,
                    bufferedPositionMs =
                        if (pendingSeek == null) {
                            max(current.bufferedPositionMs, snapshot.positionMs)
                        } else {
                            current.bufferedPositionMs
                        },
                )
            }
        }
        _runtimeDiagnostics.value =
            PlaybackRuntimeDiagnostics(
                videoDecoderName = null,
                videoWidth = snapshot.width,
                videoHeight = snapshot.height,
                videoFrameRate = snapshot.frameRate,
                droppedVideoFrames = snapshot.lostPictures,
                bandwidthEstimateBps = null,
                bufferedAheadMs = null,
                decoderDroppedVideoFrames = null,
                outputDroppedVideoFrames = snapshot.lostPictures,
                prepareEpoch = expectedGeneration,
                presentationPath = DESKTOP_LIBVLC_PRESENTATION_PATH,
            )
        if (_playbackState.value.status == PlaybackStatus.Playing) {
            val previousCount = previousLostPictures
            val previousTimeMs = previousLostPictureSampleMs
            val poll =
                droppedFramePoll(
                    previousCount = previousCount,
                    previousTimeMs = previousTimeMs,
                    currentCount = snapshot.lostPictures,
                    nowMs = monotonicTimeNanos() / 1_000_000L,
                )
            previousLostPictures = poll.baselineCount
            previousLostPictureSampleMs = poll.baselineTimeMs
            if (poll.ratePerSecond != null && previousCount != null && previousTimeMs != null && snapshot.lostPictures != null) {
                val intervalMs = (poll.baselineTimeMs ?: previousTimeMs) - previousTimeMs
                val measurement = DroppedFrameMeasurement.create(snapshot.lostPictures - previousCount, intervalMs)
                if (measurement != null) {
                    droppedFrameMeasurementsChannel.trySend(measurement)
                }
            }
        } else {
            resetDroppedFrameBaseline()
        }
    }

    private fun publishNativeState(
        expectedGeneration: Long,
        state: DesktopVlcNativeState,
    ) {
        when (state) {
            DesktopVlcNativeState.Opening -> {
                if (playIntent) publishStatus(PlaybackStatus.Loading)
            }
            DesktopVlcNativeState.Buffering -> {
                if (playIntent) publishStatus(PlaybackStatus.Buffering)
            }
            DesktopVlcNativeState.Playing -> {
                if (!playIntent) return
                val firstPlayingSample = nativePlayingGeneration != expectedGeneration
                nativePlayingGeneration = expectedGeneration
                publishStatus(
                    if (_videoPresentationReady.value) {
                        PlaybackStatus.Playing
                    } else {
                        PlaybackStatus.Buffering
                    },
                )
                if (firstPlayingSample) scheduleTrackSelection()
            }
            DesktopVlcNativeState.Paused -> {
                if (!playIntent) publishStatus(PlaybackStatus.Paused)
            }
            DesktopVlcNativeState.Error ->
                failPlayback(
                    expectedGeneration = expectedGeneration,
                    error = PlaybackError.Unknown,
                    stage = DesktopVlcFailureStage.NativeState,
                )
            DesktopVlcNativeState.NothingSpecial,
            DesktopVlcNativeState.Stopped,
            DesktopVlcNativeState.Ended,
            DesktopVlcNativeState.Unknown,
            -> Unit
        }
    }

    private fun handleEvent(
        expectedGeneration: Long,
        event: DesktopVlcEvent,
    ) {
        if (!isCurrent(expectedGeneration)) return
        when (event) {
            DesktopVlcEvent.Opening -> {
                if (playIntent) publishStatus(PlaybackStatus.Loading)
            }
            DesktopVlcEvent.Buffering -> {
                if (playIntent) publishStatus(PlaybackStatus.Buffering)
            }
            DesktopVlcEvent.Playing -> {
                if (!playIntent) return
                nativePlayingGeneration = expectedGeneration
                publishStatus(
                    if (_videoPresentationReady.value) {
                        PlaybackStatus.Playing
                    } else {
                        PlaybackStatus.Buffering
                    },
                )
                nativeScope.launch {
                    val active = engine ?: return@launch
                    emitDesktopVlcProbe(
                        DesktopVlcProbeRecord(
                            action = DesktopVlcProbeAction.PLAYING_TRACKS,
                            nativeIds = active.subtitleTracks().map(DesktopVlcTrack::id),
                        ),
                    )
                }
                // A plan target attached as a slave at media open is active as soon as
                // playback starts; there is no separate native confirmation for it.
                lastPlan?.subtitleActivationTarget?.let { target ->
                    val pendingActivation = _playbackState.value.subtitleActivation
                    if (
                        pendingActivation == SubtitleActivationState.Pending(target) &&
                        pendingSubtitleSelection == null &&
                        !pendingSubtitleSelectionWasSet
                    ) {
                        subtitleActivationConfirmation.confirm(target)
                    }
                }
                scheduleTrackSelection()
            }
            DesktopVlcEvent.Paused -> {
                if (!playIntent) publishStatus(PlaybackStatus.Paused)
            }
            DesktopVlcEvent.Stopped -> resolveTerminalEvent(VlcTerminalEvent.Stopped)
            DesktopVlcEvent.EndReached -> resolveTerminalEvent(VlcTerminalEvent.EndReached)
            DesktopVlcEvent.EncounteredError ->
                failPlayback(
                    expectedGeneration = expectedGeneration,
                    error = PlaybackError.Unknown,
                    stage = DesktopVlcFailureStage.NativeEvent,
                )
            DesktopVlcEvent.SeekableChanged -> scheduleStartSeek(expectedGeneration)
            DesktopVlcEvent.TracksChanged -> scheduleTrackSelection()
        }
    }

    private fun resolveTerminalEvent(event: VlcTerminalEvent) {
        val current = _playbackState.value
        if (current.status in TERMINAL_STATUSES) return
        val decision =
            resolveVlcTerminalStatus(
                event = event,
                currentStatus = current.status,
                playIntent = playIntent,
                playbackEverProgressed = endOfStream.playbackEverProgressed,
                endRejectedAwaitingStopped = endOfStream.endRejectedAwaitingStopped,
                nativePositionMs = current.positionMs,
                lastPublishedPositionMs = current.positionMs,
                durationMs = current.durationMs,
            )
        if (decision.endRejected) endOfStream.onEndRejected()
        if (decision.consumeEndRejected) endOfStream.consumeEndRejected()
        decision.publishedStatus?.let { status ->
            if (status in TERMINAL_STATUSES) {
                pollJob?.cancel()
                pollJob = null
            }
            _playbackState.update { state ->
                state.copy(status = status, positionMs = decision.positionMs)
            }
        }
    }

    private fun publishStatus(status: PlaybackStatus) {
        _playbackState.update { current ->
            if (current.status in TERMINAL_STATUSES && status !in TERMINAL_STATUSES) current else current.copy(status = status)
        }
    }

    private fun publishVideoPresentationIfReady(
        expectedGeneration: Long,
        snapshot: DesktopVlcSnapshot,
    ) {
        if (!isCurrent(expectedGeneration) || _videoPresentationReady.value) return
        val atStartupTarget =
            startupTargetPositionMs == 0L ||
                abs(snapshot.positionMs - startupTargetPositionMs) <=
                START_POSITION_CONFIRM_TOLERANCE_MS
        val displayedPictures = snapshot.displayedPictures
        if (displayedPictures == null) {
            displayedPictureCounterUnavailable = true
            return
        }
        val baseline = startupDisplayedPicturesBaseline
        if (baseline == null || displayedPictures < baseline) {
            startupDisplayedPicturesBaseline = displayedPictures
            return
        }
        val hasFreshDisplayedPicture =
            displayedPictures - baseline >= MIN_STARTUP_DISPLAYED_PICTURE_ADVANCE
        if (!atStartupTarget || !hasFreshDisplayedPicture) return

        _videoPresentationReady.value = true
        videoOutputObservationsChannel.trySend(
            VideoOutputObservation(
                generation = expectedGeneration,
                presented = true,
                observedAtMs = System.nanoTime() / 1_000_000L,
            ),
        )
        startupAudioSuppressed = false
        if (playIntent) publishStatus(PlaybackStatus.Playing)
        nativeScope.launch {
            engine?.setMuted(_volumeState.value.muted)
        }
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.PRESENTATION_READY,
                positionMs = snapshot.positionMs,
                count = displayedPictures.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            ),
        )
    }

    private fun publishSubtitleUnavailable(
        expectedGeneration: Long,
        target: SubtitleActivationTarget,
    ) {
        if (!isCurrent(expectedGeneration)) return
        subtitleActivationConfirmation.fail(
            target,
            reason = SubtitleActivationFailureReason.DesktopVlcSidecarUnavailable,
        )
    }

    private fun failInitialization() {
        if (released.get()) return
        initializationError = PlaybackError.UnsupportedMedia
        logger.w { "failure stage=${DesktopVlcFailureStage.Initialization.name}" }
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.FAILURE,
                detail = DesktopProbeToken.from(DesktopVlcFailureStage.Initialization.name),
            ),
        )
        _playbackState.update { current ->
            current.copy(status = PlaybackStatus.Failed, error = PlaybackError.UnsupportedMedia)
        }
    }

    private fun launchEngineInitialization() {
        if (!initializationInFlight.compareAndSet(false, true)) return
        nativeScope.launch {
            try {
                initializeEngine()
            } catch (_: DesktopVlcInitializationException) {
                failInitialization()
            } catch (_: Throwable) {
                failInitialization()
            } finally {
                initializationInFlight.set(false)
            }
        }
    }

    private suspend fun initializeEngine() {
        val created = engineFactory()
        if (released.get()) {
            created.release()
            return
        }
        engine = created
        created.setVolume(_volumeState.value.volumePercent)
        created.setMuted(_volumeState.value.muted)
        surface?.let { current -> created.setDrawable(current.value) }
        val pendingPlan = lastPlan
        if (pendingPlan != null && preparedGeneration == null) {
            val pendingGeneration = generation
            val pendingSubtitleAsset = lastSubtitleAsset
            nativeScope.launch {
                prepareNative(pendingGeneration, pendingPlan, pendingSubtitleAsset, created)
            }
        }
    }

    private fun failPlayback(
        expectedGeneration: Long,
        error: PlaybackError,
        stage: DesktopVlcFailureStage,
    ) {
        if (!isCurrent(expectedGeneration)) return
        if (_playbackState.value.status in TERMINAL_STATUSES) return
        resetDroppedFrameBaseline()
        logger.w { "failure stage=${stage.name}" }
        emitDesktopVlcProbe(
            DesktopVlcProbeRecord(
                action = DesktopVlcProbeAction.FAILURE,
                detail = DesktopProbeToken.from(stage.name),
                result = DesktopProbeToken.from(error.diagnosticName()),
            ),
        )
        playIssued = false
        pollJob?.cancel()
        if (lastPlan?.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
            offlinePath = null
            offlineSidecarPath = null
            // Failure does not prove that libVLC has stopped reading the
            // file. Use the same exact native-stop completion as replacement
            // and stop rather than releasing the holder from this callback.
            enqueueNativeStopThenRelease(offlineLeaseHolder.detach())
        }
        _playbackState.update { current ->
            current.copy(status = PlaybackStatus.Failed, error = error)
        }
    }

    private fun resetDroppedFrameBaseline() {
        previousLostPictures = null
        previousLostPictureSampleMs = null
    }

    private fun isCurrent(expectedGeneration: Long): Boolean = !released.get() && generation == expectedGeneration
}

private sealed interface DesktopVlcPublication {
    data class Native(
        val expectedGeneration: Long,
        val event: DesktopVlcEvent,
    ) : DesktopVlcPublication

    data class Poll(
        val expectedGeneration: Long,
        val snapshot: DesktopVlcSnapshot,
    ) : DesktopVlcPublication
}

private enum class DesktopVlcFailureStage {
    Initialization,
    SurfaceUnavailable,
    Authorization,
    Prepare,
    PlayRejected,
    NativeEvent,
    NativeState,
}

private const val POLL_INTERVAL_MS = 250L
private const val START_SEEK_RETRY_MS = 250L
private const val START_SEEK_ATTEMPTS = 8
private const val START_POSITION_CONFIRM_TOLERANCE_MS = 1_500L
private const val MIN_STARTUP_DISPLAYED_PICTURE_ADVANCE = 2L

private enum class StartPositionResult {
    NotReady,
    Confirmed,
    FallbackSeekApplied,
}

private data class StartPositionAttempt(
    val result: StartPositionResult,
    val snapshot: DesktopVlcSnapshot?,
) {
    companion object {
        fun notReady(): StartPositionAttempt =
            StartPositionAttempt(
                result = StartPositionResult.NotReady,
                snapshot = null,
            )
    }
}

private const val TRACK_SELECTION_RETRY_MS = 250L
private const val TRACK_SELECTION_ATTEMPTS = 10
private const val USER_SEEK_TIMEOUT_MS = 2_000L
private const val USER_SEEK_POSITION_TOLERANCE_MS = 2_000L
private const val DESKTOP_LIBVLC_PRESENTATION_PATH = "LibVLC native NSView"
private val TERMINAL_STATUSES = setOf(PlaybackStatus.Failed, PlaybackStatus.Completed)
