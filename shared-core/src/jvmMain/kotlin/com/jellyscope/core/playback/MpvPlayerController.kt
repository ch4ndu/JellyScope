// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.DesktopPlayerVolumeStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DiagnosticReason
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlaybackCompletionReadinessReason
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticOperation
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.PlayerVolumeController
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.mpvDroppedFramePoll
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.nextPositiveGeneration
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong

class MpvPlayerController private constructor(
    private val session: Session,
    private val stateScope: CoroutineScope,
    private val loadMpv: () -> LibMpv,
    private val localSubtitleFileStore: LocalSubtitleFileStore?,
    private val volumeStore: DesktopPlayerVolumeStore?,
    private val presentationPreference: MpvPresentationPreference,
    private val engineLeaseClockNanos: () -> Long = System::nanoTime,
    private val engineLeaseWait: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) : PlayerController,
    PlayerVolumeController,
    MpvVideoOutput {
    constructor(
        session: Session,
        stateScope: CoroutineScope,
        localSubtitleFileStore: LocalSubtitleFileStore? = null,
        volumeStore: DesktopPlayerVolumeStore? = null,
        presentationPreference: MpvPresentationPreference = MpvPresentationPreference.Software,
        engineLeaseClockNanos: () -> Long = System::nanoTime,
        engineLeaseWait: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    ) : this(
        session,
        stateScope,
        { LibMpv.INSTANCE },
        localSubtitleFileStore,
        volumeStore,
        presentationPreference,
        engineLeaseClockNanos,
        engineLeaseWait,
    )

    internal constructor(
        session: Session,
        stateScope: CoroutineScope,
        mpv: LibMpv,
        localSubtitleFileStore: LocalSubtitleFileStore? = null,
        volumeStore: DesktopPlayerVolumeStore? = null,
        presentationPreference: MpvPresentationPreference = MpvPresentationPreference.Software,
        engineLeaseClockNanos: () -> Long = System::nanoTime,
        engineLeaseWait: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    ) : this(
        session,
        stateScope,
        { mpv },
        localSubtitleFileStore,
        volumeStore,
        presentationPreference,
        engineLeaseClockNanos,
        engineLeaseWait,
    )

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
    private val _volumeState = MutableStateFlow(volumeStore?.restoredState ?: PlayerVolumeState())
    override val volumeState: StateFlow<PlayerVolumeState> = _volumeState.asStateFlow()
    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    private val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    private val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> =
        droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> =
        videoOutputObservationsChannel.receiveAsFlow()
    private val _presentationState =
        MutableStateFlow<MpvPresentationState>(
            if (presentationPreference == MpvPresentationPreference.MacOsOpenGl) {
                MpvPresentationState.WaitingForOpenGlSurface
            } else {
                MpvPresentationState.SoftwareActive()
            },
        )
    override val presentationState: StateFlow<MpvPresentationState> = _presentationState.asStateFlow()
    override val platformPlayer: Any?
        get() = this
    override val activeBackend: PlayerBackend = PlayerBackend.Mpv
    override val playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = true,
            hasDroppedFrameMeasurements = true,
            // Software emits after Compose publishes a frame bitmap. The macOS
            // OpenGL path only observes libmpv rendering into an FBO, not a
            // compositor-presented frame, so it must not claim first output.
            hasReliableFirstVideoOutput = presentationPreference == MpvPresentationPreference.Software,
        )
    override val videoOutputMeasurementCapabilities =
        if (presentationPreference == MpvPresentationPreference.Software) {
            VideoOutputMeasurementCapabilities.NativeFirstOutput
        } else {
            VideoOutputMeasurementCapabilities.Unsupported
        }
    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = stateScope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(subtitleActivation = activation) }
            },
            platform = PlaybackDiagnosticPlatform.Desktop,
        )
    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = stateScope,
            platform = PlaybackDiagnosticPlatform.Desktop,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(audioActivation = activation) }
            },
            onActivated = ::releasePlayIntentAfterAudioActivation,
        )

    private val frameDirty = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val onFrameAvailable = AtomicReference<(() -> Unit)?>(null)
    private val renderLock = Any()
    private val frameFormatMemory = nativeString(SW_FRAME_FORMAT)
    private val renderUpdateCallback =
        LibMpv.MpvRenderUpdateFn {
            if (!released.get()) {
                if (openGlSurface != null) {
                    scheduleOpenGlRender()
                } else {
                    frameDirty.set(true)
                    onFrameAvailable.get()?.invoke()
                }
            }
        }

    @Volatile
    private var mpv: LibMpv? = null

    @Volatile
    private var ctx: Pointer? = null

    private var renderContextRef: PointerByReference? = null

    @Volatile
    private var renderCtx: Pointer? = null
    private var apiTypeMemory: Memory? = null

    @Volatile
    private var openGlSurface: MpvOpenGlRenderSurface? = null

    @Volatile
    private var requestedHardwareDecodePolicy = MPV_PRODUCTION_HARDWARE_DECODE_POLICY

    private var openGlGetProcAddressCallback: LibMpv.MpvOpenGlGetProcAddressFn? = null
    private var openGlInitParams: MpvOpenGlInitParams? = null

    @Volatile
    private var openGlRenderExecutor: ExecutorService? = null
    private val openGlRenderScheduled = AtomicBoolean(false)
    private val openGlForceRenderRequested = AtomicBoolean(false)
    private var targetWidth = 0
    private var targetHeight = 0
    private var renderGeneration = 0L

    @Volatile
    private var lastRenderedPrepareGeneration: Long? = null
    private var renderErrorLogged = false
    private val presentationMetrics = MpvPresentationMetrics()
    private val openGlLifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    @Volatile
    private var lastPlan: PlaybackPlan? = null

    @Volatile
    private var lastSubtitleAsset: SubtitleAsset? = null

    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()

    @Volatile
    private var offlinePath: String? = null

    @Volatile
    private var offlineSidecarPath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null

    /** Platform DI seam; the public controller constructor remains remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    @Volatile
    private var fillCrop = false

    /** Guarded by [lifecycleLock] so prepare/style application cannot restore a stale margin. */
    private var subtitleClearanceActive = false

    @Volatile
    private var pendingEmbeddedAudioSelection: EmbeddedAudioSelection? = null

    @Volatile
    private var pendingEmbeddedSubtitleSelection: EmbeddedSubtitleSelection? = null

    @Volatile
    private var resolvedAudioSelectorId: Long? = null

    @Volatile
    private var resolvedSubtitleSelectorId: Long? = null

    @Volatile
    private var expectedSubtitleTarget: SubtitleActivationTarget? = null

    @Volatile
    private var playIntent = false

    @Volatile
    private var initialAudioGate = false

    @Volatile
    private var externalSubtitleCommandAccepted = false

    @Volatile
    private var externalSubtitleTrackTitle: String? = null
    private val runtimeDiagnosticsPollTicks = AtomicInt(0)

    @Volatile
    private var previousOutputDroppedFrames: Long? = null

    @Volatile
    private var previousDecoderDroppedFrames: Long? = null

    @Volatile
    private var previousDroppedFrameSampleNanos: Long? = null

    private val lifecycleLock = Any()
    private val engineLock = Any()
    private var engineInitializationToken = 0L
    private var engineUseCount = 0

    @Volatile
    private var activeOpenGlSurfaceGeneration: Long? = null

    @Volatile
    private var fallbackReason: MpvOpenGlSurfaceUnavailableReason? = null
    private var fallbackJob: Job? = null
    private var initializationJob: Job? = null
    private var openGlTeardownJob: Job? = null
    private var openGlTeardownScheduled = false
    private val openGlDetachCallbacks = mutableListOf<MpvOpenGlDetachCallback>()
    private var softwareInitializationStarted = false

    @Volatile
    private var pendingSeekPositionMs: Long? = null

    @Volatile
    private var pendingSubtitleSelectionWasSet = false

    @Volatile
    private var pendingPrepareAwaitingEngine = false

    /** Exact generation leases whose native stop command failed; release only after engine destroy. */
    private val pendingOfflineLeaseReleases = mutableListOf<OfflineArtifactLease>()
    private var pendingReplacedOfflineLease: OfflineArtifactLease? = null

    @Volatile
    private var pendingPrepareGeneration: Long? = null

    @Volatile
    private var engineAcceptedItem = false
    private val prepareGeneration = AtomicLong(0L)
    private val completionReadiness = MpvCompletionReadiness()
    private var currentStartFilePlaylistEntryId: Long? = null
    private var pendingExternalSubtitleAttachment: PendingExternalSubtitleAttachment? = null

    // Do not reset this gate in prepare(): withEngineLease does not serialize admissions, so an
    // in-flight admission can straddle prepare() and re-log an outcome already logged after a reset.
    // Retaining outcomes is safe because activation request ids increase monotonically, so a new
    // session's key cannot collide with the previous session's key.
    private val trackResolutionDiagnostics = TrackResolutionDiagnosticGate()

    private data class PendingPrepareIntent(
        val generation: Long,
        val plan: PlaybackPlan,
        val subtitleAsset: SubtitleAsset?,
        val replacedOfflineLease: OfflineArtifactLease?,
        val audioSelection: EmbeddedAudioSelection?,
        val subtitleSelection: EmbeddedSubtitleSelection?,
        val subtitleSelectionWasSet: Boolean,
        val seekPositionMs: Long?,
        val requestedSpeed: Float,
        val requestedStyle: SubtitleStyle,
        val shouldPlay: Boolean,
    )

    private data class InitializationAdmission(
        val previousFallbackJob: Job?,
        val previousInitializationJob: Job?,
        val job: Job,
    )

    private data class EngineJobsToCancel(
        val fallbackJob: Job?,
        val initializationJob: Job?,
        val pollJob: Job?,
    )

    private data class OpenGlTeardownAdmission(
        val initializationJob: Job?,
    )

    private data class OpenGlRenderState(
        val renderContext: Pointer?,
        val renderContextRef: PointerByReference?,
        val apiTypeMemory: Memory?,
        val surface: MpvOpenGlRenderSurface?,
        val getProcAddressCallback: LibMpv.MpvOpenGlGetProcAddressFn?,
        val initParams: MpvOpenGlInitParams?,
    )

    private data class QuarantinedEngine(
        val lib: LibMpv,
        val context: Pointer,
        val renderState: OpenGlRenderState,
        val renderExecutor: ExecutorService?,
    )

    @Volatile
    private var quarantinedEngine: QuarantinedEngine? = null

    /**
     * An engine whose native destroy outlived the bounded lease drain: a
     * command (notably `sub-add`, which performs unbounded network I/O inside
     * libmpv) was still holding an engine-use lease when teardown gave up
     * waiting. Unlike [QuarantinedEngine] this is not a permanent leak — the
     * last lease release destroys it and only then reports `Terminated`.
     */
    private data class PendingEngineDestroy(
        val lib: LibMpv,
        val context: Pointer,
        val renderState: OpenGlRenderState,
        val finishesOpenGlTeardown: Boolean,
    )

    @Volatile
    private var engineAwaitingDestroy: PendingEngineDestroy? = null

    private enum class EngineTermination {
        Terminated,
        Quarantined,
        Deferred,
    }

    private data class ExternalSubtitleAttachmentDraft(
        val target: SubtitleActivationTarget,
        val resource: String,
        val trackTitle: String,
    )

    private data class PendingExternalSubtitleAttachment(
        val generation: Long,
        val playlistEntryId: Long,
        val target: SubtitleActivationTarget,
        val resource: String,
        val trackTitle: String,
    )

    private sealed interface MpvLifecycleEvent {
        data object None : MpvLifecycleEvent

        data class StartFile(
            val playlistEntryId: Long,
        ) : MpvLifecycleEvent

        data class EndFile(
            val reason: Int,
            val nativeError: Int,
            val playlistEntryId: Long,
        ) : MpvLifecycleEvent

        data object FileLoaded : MpvLifecycleEvent

        data object QueueOverflow : MpvLifecycleEvent

        data object Unknown : MpvLifecycleEvent
    }

    init {
        if (presentationPreference == MpvPresentationPreference.Software) {
            val token = nextEngineInitializationToken()
            if (!initializeEngine(MpvPresentationPreference.Software, surface = null, token = token)) {
                _presentationState.value = MpvPresentationState.Failed
                released.set(true)
            }
        }
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        val generation = beginPrepareGeneration() ?: return
        val replacedOfflineLease =
            if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlineLeaseHolder.detach()
            } else {
                null
            }
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            offlinePath = null
            offlineSidecarPath = null
        } else if (offlinePath == null) {
            _playbackState.update { current -> current.copy(status = PlaybackStatus.Failed) }
            return
        }
        prepare(generation, plan, subtitleAsset, replacedOfflineLease)
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (released.get()) {
            return OfflinePrepareResult.Unavailable(
                com.jellyscope.core.domain.playback.PlaybackError
                    .OfflinePlayerUnavailable(PlayerBackend.Mpv),
            )
        }
        val resolver =
            offlineArtifactResolver
                ?: return OfflinePrepareResult.Unavailable(com.jellyscope.core.domain.playback.PlaybackError.OfflineArtifactUnavailable)
        stop()
        val lease =
            when (val resolution = resolver.acquireForOfflinePlan(plan, session.accountIdentity())) {
                is OfflineArtifactResolution.Available -> resolution.lease
                is OfflineArtifactResolution.Unavailable ->
                    return OfflinePrepareResult.Unavailable(com.jellyscope.core.domain.playback.PlaybackError.OfflineArtifactUnavailable)
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
            val failedLease = offlineLeaseHolder.detach()
            stop()
            // stop() has already submitted the native stop under the engine
            // lease; release this detached generation only after that same
            // serialized boundary, never while mpv may still be unwinding it.
            releaseOfflineLeaseAfterNativeStop(failedLease)
            OfflinePrepareResult.Unavailable(
                com.jellyscope.core.domain.playback.PlaybackError
                    .OfflinePlayerUnavailable(PlayerBackend.Mpv),
            )
        }
    }

    private fun prepare(
        generation: Long,
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
        replacedOfflineLease: OfflineArtifactLease? = null,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (generation != prepareGeneration.load() || released.get()) return@synchronized false

            lastRenderedPrepareGeneration = null
            if (
                currentActiveMpv() == null &&
                (
                    _presentationState.value == MpvPresentationState.Failed ||
                        _presentationState.value == MpvPresentationState.Released
                )
            ) {
                replacedOfflineLease?.let {
                    synchronized(engineLock) { pendingOfflineLeaseReleases += it }
                }
                return@synchronized true
            }
            _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = generation)
            runtimeDiagnosticsPollTicks.store(0)
            resetDroppedFrameBaseline()
            presentationMetrics.reset()
            presentationMetrics.setInstalledFrameRate(plan.videoPresentation?.frameRate)
            if (currentActiveMpv() == null && !released.get()) {
                stagePendingPrepare(generation, plan, subtitleAsset, replacedOfflineLease)
                if (currentActiveMpv() != null) {
                    replayPendingInitializationIntent()
                    return@synchronized true
                }
                when (_presentationState.value) {
                    MpvPresentationState.WaitingForOpenGlSurface,
                    is MpvPresentationState.InitializingOpenGl,
                    -> startOpenGlSurfaceFallbackDeadline()
                    is MpvPresentationState.SoftwareActive ->
                        startSoftwareInitialization(
                            (_presentationState.value as MpvPresentationState.SoftwareActive).fallbackReason,
                        )
                    MpvPresentationState.Failed,
                    MpvPresentationState.Released,
                    is MpvPresentationState.OpenGlActive,
                    -> Unit
                }
                return@synchronized true
            }
            withEngineLease(PlayerOperation.Prepare) { active ->
                stopping.set(false)
                if (replacedOfflineLease != null) {
                    val stopResult = runCatching { active.lib.mpv_command(active.ctx, arrayOf("stop", null)) }.getOrDefault(-1)
                    if (stopResult == 0) {
                        replacedOfflineLease.release()
                    } else {
                        // Keep a failed-stop generation leased until engine destruction; the
                        // command did not prove that mpv released the old file.
                        pendingOfflineLeaseReleases += replacedOfflineLease
                    }
                }
                val streamUrl =
                    if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                        offlinePath
                    } else {
                        plan.streamUrl.takeIf { url -> url.isNotBlank() }
                    } ?: return@withEngineLease failPlayback("mpv prepare failed")
                synchronized(engineLock) {
                    pendingPrepareAwaitingEngine = false
                    pendingPrepareGeneration = null
                    engineAcceptedItem = true
                }
                loaded.set(false)
                installPrepareState(plan, subtitleAsset)

                // Keep Jellyfin auth out of URLs. `http-header-fields` is comma-separated,
                // so use the comma-free token-only MediaBrowser Authorization value instead of
                // the full comma-bearing client/device form. `http-header-fields` is a global
                // mpv option that persists across prepares on a reused context, so set it
                // deterministically every prepare: install the token only for a same-origin main
                // URL, and clear it otherwise so a server-supplied cross-origin (e.g. CDN) URL —
                // including one that follows a trusted item — never receives the token.
                val offline = plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
                if (!applyJellyfinAuthHeader(active, attachCredentials = !offline && streamUrl.isUnderServer(session.serverUrl))) {
                    return@withEngineLease
                }
                val playbackUrl =
                    if (offline) {
                        File(streamUrl).toURI().toString()
                    } else {
                        streamUrl.stripAuthQueryParams()
                    }

                if (!applyMpvStartPosition(active, plan.startPositionMs)) {
                    failPlayback("mpv prepare failed while applying start position")
                    return@withEngineLease
                }

                active.lib.mpv_set_property_string(active.ctx, "sid", "no")
                active.lib.mpv_set_property_string(active.ctx, "pause", "yes")
                active.lib.mpv_set_property_string(active.ctx, "speed", plan.playbackSpeed.toString())
                applySubtitleStyle(active.lib, active.ctx, plan.subtitleStyle)

                val externalAttachment =
                    prepareExternalSubtitleAttachment(
                        target = plan.subtitleActivationTarget,
                        subtitleAsset = subtitleAsset,
                        offline = offline,
                        trustedSidecarPath = offlineSidecarPath,
                    )
                val loadResult =
                    active.lib.mpv_command(active.ctx, arrayOf("loadfile", playbackUrl, "replace", null))
                var playlistEntryId: Long? = null
                if (loadResult == 0) {
                    playlistEntryId = currentPlaylistEntryId()
                    completionReadiness.bindPlaylistEntry(generation, playlistEntryId)
                    if (externalAttachment != null) {
                        playlistEntryId?.let { entryId ->
                            pendingExternalSubtitleAttachment =
                                PendingExternalSubtitleAttachment(
                                    generation = generation,
                                    playlistEntryId = entryId,
                                    target = externalAttachment.target,
                                    resource = externalAttachment.resource,
                                    trackTitle = externalAttachment.trackTitle,
                                )
                        }
                    }
                }
                if (loadResult != 0) {
                    val reason =
                        runCatching { active.lib.mpv_error_string(loadResult)?.getString(0) }
                            .getOrNull()
                    failPlayback("mpv prepare failed while loading media", detail = reason, nativeCode = loadResult.toLong())
                    return@withEngineLease
                }

                if (externalAttachment != null && playlistEntryId == null) {
                    logMpvDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                    subtitleActivationConfirmation.fail(
                        externalAttachment.target,
                        reason = SubtitleActivationFailureReason.PlaylistEntryUnresolved,
                    )
                }
            }
            true
        }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        pendingEmbeddedAudioSelection = selection
        if ((_playbackState.value.audioActivation as? AudioActivationState.Pending)?.target != selection.target) {
            audioActivationConfirmation.begin(selection.target)
        }
        withEngineLease { active -> applyPendingEmbeddedAudioSelection(active) }
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        pendingEmbeddedSubtitleSelection = selection
        pendingSubtitleSelectionWasSet = true
        if (selection == null) {
            expectedSubtitleTarget = null
            resolvedSubtitleSelectorId = null
            withEngineLease { active ->
                active.lib.mpv_set_property_string(active.ctx, "sid", "no")
            }
            subtitleActivationConfirmation.clear()
            return
        }
        expectedSubtitleTarget = selection.target
        subtitleActivationConfirmation.begin(selection.target)
        withEngineLease { active -> applyPendingEmbeddedSubtitleSelection(active) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        _playbackState.update { current -> current.copy(playbackSpeed = speed) }
        withEngineLease { active ->
            active.lib.mpv_set_property_string(active.ctx, "speed", speed.toString())
        }
    }

    override fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val next = _volumeState.value.copy(volumePercent = clamped)
        _volumeState.value = next
        // Submit cadence and settled values directly; the store coalesces them,
        // so no release edge or controller timer is needed.
        volumeStore?.submit(next)
        withEngineLease { active ->
            active.lib.mpv_set_property_string(active.ctx, "volume", clamped.toDouble().toString())
        }
    }

    override fun setMuted(muted: Boolean) {
        val next = _volumeState.value.copy(muted = muted)
        _volumeState.value = next
        volumeStore?.submit(next)
        withEngineLease { active ->
            active.lib.mpv_set_property_string(active.ctx, "mute", if (muted) "yes" else "no")
        }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        _playbackState.update { current -> current.copy(subtitleStyle = style) }
        synchronized(lifecycleLock) {
            withEngineLease { active -> applySubtitleStyle(active.lib, active.ctx, style) }
        }
    }

    override fun play() {
        playIntent = true
        withEngineLease { active ->
            if (initialAudioGate && _playbackState.value.audioActivation is AudioActivationState.Pending) {
                pollState()
                return@withEngineLease
            }
            active.lib.mpv_set_property_string(active.ctx, "pause", "no")
            pollState()
        }
    }

    override fun pause() {
        playIntent = false
        resetDroppedFrameBaseline()
        presentationMetrics.resetPublicationCadence()
        val paused =
            withEngineLease { active ->
                active.lib.mpv_set_property_string(active.ctx, "pause", "yes")
            }
        _playbackState.update { current -> current.copy(status = PlaybackStatus.Paused) }
        if (paused != null) pollState()
    }

    override fun seekTo(positionMs: Long) {
        val seekPosition = positionMs.coerceAtLeast(0L)
        resetDroppedFrameBaseline()
        pendingSeekPositionMs = seekPosition
        withEngineLease { active ->
            active.lib.mpv_command(
                active.ctx,
                arrayOf("seek", (seekPosition / MILLISECONDS_PER_SECOND).toString(), "absolute", null),
            )
        } ?: return
        pendingSeekPositionMs = null
        _playbackState.update { current -> current.copy(status = PlaybackStatus.Buffering) }
        pollState()
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            stopping.set(true)
            resetDroppedFrameBaseline()
            val fallbackJobToCancel =
                synchronized(engineLock) {
                    pendingPrepareAwaitingEngine = false
                    pendingPrepareGeneration = null
                    fallbackJob.also { fallbackJob = null }
                }
            fallbackJobToCancel?.cancel()
            pendingSeekPositionMs = null
            presentationMetrics.reset()
            invalidateExternalSubtitleAttachmentLocked()
            expectedSubtitleTarget = null
            subtitleActivationConfirmation.clear()
            val nativeStopSucceeded =
                withEngineLease(PlayerOperation.Stop) { active ->
                    active.lib.mpv_command(active.ctx, arrayOf("stop", null)) == 0
                }
            loaded.set(false)
            playIntent = false
            initialAudioGate = false
            pendingEmbeddedAudioSelection = null
            pendingEmbeddedSubtitleSelection = null
            resolvedAudioSelectorId = null
            resolvedSubtitleSelectorId = null
            audioActivationConfirmation.clear()
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
            val detachedOfflineLease = offlineLeaseHolder.detach()
            completeOfflineLeaseTeardown(detachedOfflineLease, nativeStopSucceeded)
        }
    }

    override fun retry() {
        if (released.get()) return warnReleased(PlayerOperation.Retry)
        val embeddedSelection = pendingEmbeddedSubtitleSelection
        val audioSelection = pendingEmbeddedAudioSelection
        val shouldPlay = playIntent
        lastPlan?.let { plan ->
            val selectionDecision =
                retrySelectionDecision(
                    audioSelection = audioSelection,
                    subtitleIntent =
                        embeddedSelection
                            ?.let(RetrySubtitleIntent::Selection)
                            ?: RetrySubtitleIntent.Unspecified,
                    planSubtitleTarget = plan.subtitleActivationTarget,
                )
            prepare(
                plan,
                if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                    null
                } else {
                    lastSubtitleAsset ?: plan.subtitleAsset
                },
            )
            selectionDecision.audioSelection?.let(::selectEmbeddedAudio)
            when (selectionDecision) {
                is RetrySelectionDecision.RestorePlan -> Unit
                is RetrySelectionDecision.ReassertSubtitleOff -> selectEmbeddedSubtitle(null)
                is RetrySelectionDecision.SelectSubtitle -> selectEmbeddedSubtitle(selectionDecision.selection)
            }
            if (shouldPlay) play()
        }
    }

    override fun release() {
        val jobsToCancel =
            synchronized(lifecycleLock) {
                if (released.get()) return
                released.set(true)
                stopping.set(true)
                invalidateExternalSubtitleAttachmentLocked()
                synchronized(engineLock) {
                    pendingPrepareAwaitingEngine = false
                    pendingPrepareGeneration = null
                    _presentationState.value = MpvPresentationState.Released
                    engineInitializationToken = nextPositiveGeneration(engineInitializationToken)
                    EngineJobsToCancel(
                        fallbackJob = fallbackJob.also { fallbackJob = null },
                        initializationJob = initializationJob,
                        pollJob = pollJob.also { pollJob = null },
                    )
                }
            }
        jobsToCancel.fallbackJob?.cancel()
        jobsToCancel.initializationJob?.cancel()
        jobsToCancel.pollJob?.cancel()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        resetDroppedFrameBaseline()
        presentationMetrics.reset()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        pendingSubtitleSelectionWasSet = false
        pendingSeekPositionMs = null
        resolvedAudioSelectorId = null
        resolvedSubtitleSelectorId = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
        subtitleActivationConfirmation.clear()
        onFrameAvailable.set(null)
        droppedFrameMeasurementsChannel.close()
        videoOutputObservationsChannel.close()

        // Every presentation path tears the engine down off the caller thread:
        // `mpv_render_context_free`/`mpv_terminate_destroy` are unbounded, and
        // UI disposal calls release() directly on the platform UI thread while
        // Windows/Linux always select the software presentation.
        val detachedOfflineLease = offlineLeaseHolder.detach()
        synchronized(engineLock) {
            pendingReplacedOfflineLease?.let(pendingOfflineLeaseReleases::add)
            pendingReplacedOfflineLease = null
        }
        scheduleOpenGlTeardown(
            MpvOpenGlDetachCallback {
                detachedOfflineLease?.release()
                releasePendingOfflineLeaseReleases()
            },
        )
        offlinePath = null
        offlineSidecarPath = null
    }

    override fun attachOpenGlSurface(surface: MpvOpenGlRenderSurface) {
        val admission =
            synchronized(engineLock) {
                if (
                    presentationPreference != MpvPresentationPreference.MacOsOpenGl ||
                    released.get() ||
                    mpv != null ||
                    ctx != null ||
                    engineAcceptedItem ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    _presentationState.value != MpvPresentationState.WaitingForOpenGlSurface ||
                    openGlTeardownScheduled ||
                    activeOpenGlSurfaceGeneration != null
                ) {
                    return@synchronized null
                }
                activeOpenGlSurfaceGeneration = surface.generation
                engineInitializationToken = nextPositiveGeneration(engineInitializationToken)
                val token = engineInitializationToken
                _presentationState.value = MpvPresentationState.InitializingOpenGl(surface.generation)
                val previousFallbackJob = fallbackJob.also { fallbackJob = null }
                val previousInitializationJob = initializationJob
                val job =
                    stateScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        val initialized =
                            initializeEngine(
                                preference = MpvPresentationPreference.MacOsOpenGl,
                                surface = surface,
                                token = token,
                            )
                        val becameActive =
                            synchronized(engineLock) {
                                if (
                                    initialized &&
                                    !released.get() &&
                                    token == engineInitializationToken &&
                                    activeOpenGlSurfaceGeneration == surface.generation &&
                                    ctx != null &&
                                    !openGlTeardownScheduled &&
                                    quarantinedEngine == null &&
                                    engineAwaitingDestroy == null
                                ) {
                                    _presentationState.value = MpvPresentationState.OpenGlActive(surface.generation)
                                    true
                                } else {
                                    false
                                }
                            }
                        if (becameActive) {
                            replayPendingInitializationIntent()
                        } else if (initialized) {
                            terminateInstalledEngine()
                        } else if (isCurrentInitialization(token) && !released.get()) {
                            startSoftwareInitialization(MpvOpenGlSurfaceUnavailableReason.OpenGlInitializationFailed)
                        }
                    }
                initializationJob = job
                InitializationAdmission(
                    previousFallbackJob = previousFallbackJob,
                    previousInitializationJob = previousInitializationJob,
                    job = job,
                )
            } ?: return
        admission.previousFallbackJob?.cancel()
        admission.previousInitializationJob?.cancel()
        admission.job.start()
    }

    override fun openGlSurfaceUnavailable(
        generation: Long,
        reason: MpvOpenGlSurfaceUnavailableReason,
    ) {
        val shouldStartSoftware =
            synchronized(engineLock) {
                if (
                    presentationPreference != MpvPresentationPreference.MacOsOpenGl ||
                    released.get() ||
                    mpv != null ||
                    ctx != null ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    openGlTeardownScheduled ||
                    (
                        activeOpenGlSurfaceGeneration != null &&
                            activeOpenGlSurfaceGeneration != generation
                    )
                ) {
                    return@synchronized null
                }
                activeOpenGlSurfaceGeneration = generation
                fallbackReason = reason
                if (pendingPrepareAwaitingEngine) {
                    true
                } else {
                    _presentationState.value = MpvPresentationState.SoftwareActive(reason)
                    false
                }
            } ?: return
        if (shouldStartSoftware) {
            startSoftwareInitialization(reason)
        }
    }

    override fun detachOpenGlSurface(
        generation: Long,
        onDetached: MpvOpenGlDetachCallback,
    ) {
        val shouldDetach =
            synchronized(engineLock) {
                val matches = activeOpenGlSurfaceGeneration == generation
                if (matches) {
                    activeOpenGlSurfaceGeneration = null
                    engineInitializationToken = nextPositiveGeneration(engineInitializationToken)
                }
                matches
            }
        if (!shouldDetach) {
            onDetached(
                if (quarantinedEngine != null) {
                    MpvOpenGlDetachDisposition.Quarantined
                } else {
                    MpvOpenGlDetachDisposition.Terminated
                },
            )
            return
        }
        scheduleOpenGlTeardown(
            MpvOpenGlDetachCallback { disposition ->
                synchronized(engineLock) {
                    if (!released.get()) {
                        _presentationState.value = MpvPresentationState.Failed
                    }
                }
                onDetached(disposition)
            },
        )
    }

    override fun requestOpenGlRender(generation: Long) {
        if (released.get() || activeOpenGlSurfaceGeneration != generation) return
        openGlForceRenderRequested.set(true)
        scheduleOpenGlRender()
    }

    override fun resize(
        widthPx: Int,
        heightPx: Int,
    ): Long {
        val width = widthPx.coerceAtLeast(0)
        val height = heightPx.coerceAtLeast(0)
        return synchronized(renderLock) {
            if (width == targetWidth && height == targetHeight) return@synchronized renderGeneration
            targetWidth = width
            targetHeight = height
            renderGeneration = nextPositiveGeneration(renderGeneration)
            presentationMetrics.reset()
            frameDirty.set(true)
            renderGeneration
        }
    }

    override fun setFillCrop(crop: Boolean) {
        fillCrop = crop
        withEngineLease { active -> applyFillCrop(active.lib, active.ctx) }
    }

    override fun setSubtitleClearanceActive(active: Boolean) {
        synchronized(lifecycleLock) {
            subtitleClearanceActive = active
            withEngineLease { current ->
                applySubtitleClearance(current.lib, current.ctx)
            }
        }
    }

    override fun renderFrameIfNeeded(target: MpvRenderTarget): MpvRenderResult? =
        synchronized(renderLock) {
            if (released.get()) return null
            val active = activeMpv(PlayerOperation.RenderFrameIfNeeded) ?: return null
            val renderContext = renderCtx ?: return null
            if (!target.isValidFor(targetWidth, targetHeight, renderGeneration)) {
                frameDirty.set(true)
                return null
            }

            val updateFlags =
                runCatching { active.lib.mpv_render_context_update(renderContext) }
                    .getOrElse { throwable ->
                        logMpvDiagnostic(PlaybackDiagnosticStage.Render, PlaybackDiagnosticEvent.Failed, throwable)
                        0L
                    }
            val needsRender = updateFlags and LibMpv.RENDER_UPDATE_FRAME != 0L || frameDirty.getAndSet(false)
            if (!needsRender) return null

            val sizeMemory =
                Memory(SW_SIZE_BYTES).apply {
                    setInt(0, target.width)
                    setInt(Int.SIZE_BYTES.toLong(), target.height)
                }
            val strideMemory =
                Memory(NativeLong.SIZE.toLong()).apply {
                    setNativeLong(0, NativeLong(target.stride.toLong()))
                }
            val pixelPointer = Pointer(target.pixelAddress)
            val params =
                renderParams(
                    LibMpv.RENDER_PARAM_SW_SIZE to sizeMemory,
                    LibMpv.RENDER_PARAM_SW_FORMAT to frameFormatMemory,
                    LibMpv.RENDER_PARAM_SW_STRIDE to strideMemory,
                    LibMpv.RENDER_PARAM_SW_POINTER to pixelPointer,
                )

            val renderStartedAtNanos = System.nanoTime()
            val renderResult =
                runCatching { active.lib.mpv_render_context_render(renderContext, params) }
                    .getOrElse { throwable ->
                        logMpvDiagnostic(PlaybackDiagnosticStage.Render, PlaybackDiagnosticEvent.Failed, throwable)
                        frameDirty.set(true)
                        return null
                    }

            if (renderResult != 0) {
                if (!renderErrorLogged) {
                    renderErrorLogged = true
                    logMpvDiagnostic(
                        PlaybackDiagnosticStage.Render,
                        PlaybackDiagnosticEvent.Failed,
                        nativeCode = renderResult.toLong(),
                    )
                }
                frameDirty.set(true)
                return null
            }
            renderErrorLogged = false
            val durationNanos = (System.nanoTime() - renderStartedAtNanos).coerceAtLeast(0L)
            presentationMetrics.recordRenderDuration(durationNanos)
            lastRenderedPrepareGeneration = prepareGeneration.load()
            MpvRenderResult(
                generation = target.generation,
                renderDurationNanos = durationNanos,
            )
        }

    override fun recordFramePublished(publishedAtNanos: Long) {
        if (presentationPreference != MpvPresentationPreference.Software) return
        if (_playbackState.value.status == PlaybackStatus.Playing) {
            presentationMetrics.recordPublication(publishedAtNanos)
            val generation = lastRenderedPrepareGeneration
            if (generation != null && generation == prepareGeneration.load() && !released.get()) {
                videoOutputObservationsChannel.trySend(
                    VideoOutputObservation(
                        generation = generation,
                        presented = true,
                        observedAtMs = publishedAtNanos / 1_000_000L,
                    ),
                )
                lastRenderedPrepareGeneration = null
            }
        } else {
            presentationMetrics.resetPublicationCadence()
        }
    }

    override fun setOnFrameAvailable(listener: (() -> Unit)?) {
        onFrameAvailable.set(listener)
    }

    private fun initializeEngine(
        preference: MpvPresentationPreference,
        surface: MpvOpenGlRenderSurface?,
        token: Long,
    ): Boolean {
        if (quarantinedEngine != null || engineAwaitingDestroy != null) return false
        val lib =
            runCatching { loadMpv() }
                .getOrElse { throwable ->
                    failEngineInitialization(preference, "libmpv could not be loaded", throwable)
                    return false
                }
        val context =
            runCatching { lib.mpv_create() }
                .getOrElse { throwable ->
                    failEngineInitialization(preference, "libmpv failed to create a player context", throwable)
                    return false
                }
                ?: run {
                    failEngineInitialization(preference, "libmpv failed to create a player context")
                    return false
                }

        val hardwareDecodePolicy =
            resolveMpvHardwareDecodePolicy(
                osName = System.getProperty("os.name").orEmpty(),
                surfaceKind = surface?.surfaceKind,
            )

        val options =
            when (preference) {
                MpvPresentationPreference.MacOsOpenGl -> {
                    if (surface == null) {
                        lib.mpv_terminate_destroy(context)
                        return false
                    }
                    MPV_COMMON_INIT_OPTIONS + MPV_VIDEO_OUTPUT_INIT_OPTIONS
                }
                MpvPresentationPreference.Software -> MPV_COMMON_INIT_OPTIONS + MPV_VIDEO_OUTPUT_INIT_OPTIONS
            }
        val resolvedOptions =
            options.map { (name, value) ->
                if (name == "hwdec") {
                    name to hardwareDecodePolicy.optionValue
                } else {
                    name to value
                }
            }
        val optionFailure =
            resolvedOptions.firstOrNull { (name, value) ->
                lib.mpv_set_option_string(context, name, value) != 0
            }
        if (optionFailure != null) {
            failEngineInitialization(preference, "mpv initialization failed while setting ${optionFailure.first}")
            lib.mpv_terminate_destroy(context)
            return false
        }

        MPV_BEST_EFFORT_OPTIONS.forEach { (name, value) ->
            setBestEffortMpvOption(lib, context, name, value)
        }

        if (lib.mpv_initialize(context) != 0) {
            failEngineInitialization(preference, "mpv initialization failed")
            lib.mpv_terminate_destroy(context)
            return false
        }

        if (
            preference == MpvPresentationPreference.MacOsOpenGl &&
            surface?.withCurrentContext { false } != true
        ) {
            failEngineInitialization(preference, "macOS OpenGL context is unavailable")
            lib.mpv_terminate_destroy(context)
            return false
        }

        var createdRenderContext: Pointer? = null
        var createdRenderContextRef: PointerByReference? = null
        var createdApiMemory: Memory? = null
        var createdOpenGlCallback: LibMpv.MpvOpenGlGetProcAddressFn? = null
        var createdOpenGlInitParams: MpvOpenGlInitParams? = null
        createdApiMemory =
            nativeString(
                if (preference == MpvPresentationPreference.MacOsOpenGl) {
                    OPENGL_RENDER_API
                } else {
                    SW_RENDER_API
                },
            )
        val renderParamEntries = mutableListOf<Pair<Int, Pointer?>>(LibMpv.RENDER_PARAM_API_TYPE to createdApiMemory)
        if (preference == MpvPresentationPreference.MacOsOpenGl) {
            val glSurface =
                surface
                    ?: run {
                        lib.mpv_terminate_destroy(context)
                        return false
                    }
            createdOpenGlCallback =
                LibMpv.MpvOpenGlGetProcAddressFn { _, name ->
                    name
                        ?.let(glSurface::resolveGlSymbol)
                        ?.takeIf { address -> address > 0L }
                        ?.let(::Pointer)
                }
            createdOpenGlInitParams =
                MpvOpenGlInitParams(
                    getProcAddress = createdOpenGlCallback,
                    getProcAddressCtx = null,
                ).apply { write() }
            renderParamEntries +=
                LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS to createdOpenGlInitParams.pointer
        }
        val params = renderParams(*renderParamEntries.toTypedArray())
        createdRenderContextRef = PointerByReference()
        var createResult = Int.MIN_VALUE
        if (preference == MpvPresentationPreference.MacOsOpenGl) {
            var createInvoked = false
            surface?.withCurrentContext {
                createInvoked = true
                createResult = lib.mpv_render_context_create(createdRenderContextRef, context, params)
                false
            }
            if (!createInvoked) {
                failEngineInitialization(preference, "OpenGL context could not be made current")
                lib.mpv_terminate_destroy(context)
                return false
            }
        } else {
            createResult = lib.mpv_render_context_create(createdRenderContextRef, context, params)
        }
        createdRenderContext = createdRenderContextRef.value
        if (createResult != 0 || createdRenderContext == null) {
            failEngineInitialization(preference, "mpv render context creation failed")
            lib.mpv_terminate_destroy(context)
            return false
        }

        val installed =
            synchronized(engineLock) {
                if (
                    released.get() ||
                    token != engineInitializationToken ||
                    (
                        preference == MpvPresentationPreference.MacOsOpenGl &&
                            activeOpenGlSurfaceGeneration != surface?.generation
                    ) ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    openGlTeardownScheduled ||
                    ctx != null
                ) {
                    false
                } else {
                    mpv = lib
                    ctx = context
                    renderCtx = createdRenderContext
                    renderContextRef = createdRenderContextRef
                    apiTypeMemory = createdApiMemory
                    openGlSurface = surface
                    requestedHardwareDecodePolicy = hardwareDecodePolicy
                    openGlGetProcAddressCallback = createdOpenGlCallback
                    openGlInitParams = createdOpenGlInitParams
                    if (preference == MpvPresentationPreference.MacOsOpenGl) {
                        openGlRenderExecutor =
                            Executors.newSingleThreadExecutor { runnable ->
                                Thread(runnable, "jellyscope-mpv-opengl-render").apply { isDaemon = true }
                            }
                    }
                    true
                }
            }
        if (!installed) {
            createdRenderContext.let { renderContext ->
                if (preference == MpvPresentationPreference.MacOsOpenGl) {
                    surface?.withCurrentContext {
                        lib.mpv_render_context_free(renderContext)
                        false
                    }
                } else {
                    lib.mpv_render_context_free(renderContext)
                }
            }
            lib.mpv_terminate_destroy(context)
            return false
        }

        lib.mpv_render_context_set_update_callback(createdRenderContext, renderUpdateCallback, null)
        if (preference == MpvPresentationPreference.Software) {
            val published =
                synchronized(engineLock) {
                    if (
                        released.get() ||
                        token != engineInitializationToken ||
                        ctx !== context ||
                        quarantinedEngine != null ||
                        engineAwaitingDestroy != null ||
                        openGlTeardownScheduled
                    ) {
                        false
                    } else {
                        _presentationState.value = MpvPresentationState.SoftwareActive(fallbackReason)
                        true
                    }
                }
            if (!published) {
                terminateInstalledEngine()
                return false
            }
            frameDirty.set(true)
        } else {
            scheduleOpenGlRender()
        }
        applyFillCrop(lib, context)
        applyVolumeState(lib, context, _volumeState.value)
        startStatePolling(token)
        return true
    }

    private fun failEngineInitialization(
        preference: MpvPresentationPreference,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (preference == MpvPresentationPreference.Software) {
            synchronized(engineLock) {
                if (!released.get() && !openGlTeardownScheduled) {
                    _presentationState.value = MpvPresentationState.Failed
                }
            }
            failPlayback(message, throwable)
        } else {
            logMpvDiagnostic(
                PlaybackDiagnosticStage.NativePlayer,
                PlaybackDiagnosticEvent.Failed,
                throwable,
            )
        }
    }

    private fun startStatePolling(token: Long) {
        val admission =
            synchronized(engineLock) {
                if (
                    released.get() ||
                    token != engineInitializationToken ||
                    mpv == null ||
                    ctx == null ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    openGlTeardownScheduled
                ) {
                    return@synchronized null
                }
                val previousJob = pollJob
                val job =
                    stateScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        while (isActive && !released.get()) {
                            // One lease per iteration: event handling and the property
                            // reads below both touch the native context, and the
                            // subtitle attach issued from FILE_LOADED can outlive this
                            // iteration's start by the length of a network fetch.
                            withEngineLease(PlayerOperation.EventDrain) { active ->
                                drainMpvEvents(active)
                                pollState()
                            }
                            delay(STATE_POLL_INTERVAL_MS)
                        }
                    }
                pollJob = job
                previousJob to job
            } ?: return
        admission.first?.cancel()
        admission.second.start()
    }

    private fun stagePendingPrepare(
        generation: Long,
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
        replacedOfflineLease: OfflineArtifactLease?,
    ) {
        stopping.set(false)
        synchronized(engineLock) {
            engineAcceptedItem = false
            pendingReplacedOfflineLease = replacedOfflineLease
        }
        installPrepareState(plan, subtitleAsset)
        // Publish the pending marker last. The volatile write makes the staged
        // plan and intent fields visible to the background initializer before
        // it attempts replay.
        synchronized(engineLock) {
            pendingPrepareGeneration = generation
            pendingPrepareAwaitingEngine = true
        }
    }

    /**
     * Installs the state shared by the installed-engine and pending-engine
     * prepare branches. Native admission, commands, leases, and replay remain
     * in their respective branches; this fragment only keeps the published
     * prepare state in lockstep.
     */
    private fun installPrepareState(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        lastPlan = plan
        lastSubtitleAsset = subtitleAsset
        playIntent = false
        pendingSeekPositionMs = null
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        pendingSubtitleSelectionWasSet = false
        resolvedAudioSelectorId = null
        resolvedSubtitleSelectorId = null
        expectedSubtitleTarget = plan.subtitleActivationTarget
        val initialAudioActivation = initialAudioActivationFor(plan)
        initialAudioGate = initialAudioActivation is InitialAudioActivation.AwaitNativeMapping
        audioActivationConfirmation.applyInitial(initialAudioActivation)
        subtitleActivationConfirmation.beginForPlan(plan, subtitleAsset)
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Loading,
                positionMs = plan.clampedStartPositionMs(),
                durationMs = null,
                bufferedPositionMs = 0L,
                playbackSpeed = plan.playbackSpeed,
                subtitleStyle = plan.subtitleStyle,
                error = null,
            )
        }
    }

    private fun startOpenGlSurfaceFallbackDeadline() {
        val admission =
            synchronized(engineLock) {
                if (
                    presentationPreference != MpvPresentationPreference.MacOsOpenGl ||
                    released.get() ||
                    mpv != null ||
                    ctx != null ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    openGlTeardownScheduled ||
                    fallbackJob?.isCompleted == false
                ) {
                    return@synchronized null
                }
                val previousJob = fallbackJob
                val job =
                    stateScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        delay(OPENGL_SURFACE_FALLBACK_TIMEOUT_MS)
                        // "Am I still the registered deadline?" — cancellation cannot stop
                        // non-suspending code already past delay(), and every clear site
                        // (attach, stop, release, software init) nulls or replaces
                        // `fallbackJob` under the lock while cancelling only afterwards.
                        // Without this identity check a stale deadline slips into that
                        // window and starts a software engine: during OpenGL init
                        // `mpv`/`ctx` are still null and the state is `InitializingOpenGl`,
                        // and right after `stop()` the pending-prepare flag is still set,
                        // so every other condition below passes. An init token is not
                        // sufficient — `stop()` deliberately does not bump one.
                        val self = coroutineContext[Job]
                        val shouldStartSoftware =
                            synchronized(engineLock) {
                                fallbackJob === self &&
                                    !released.get() &&
                                    mpv == null &&
                                    ctx == null &&
                                    quarantinedEngine == null &&
                                    engineAwaitingDestroy == null &&
                                    !openGlTeardownScheduled &&
                                    pendingPrepareAwaitingEngine &&
                                    _presentationState.value !is MpvPresentationState.OpenGlActive
                            }
                        if (shouldStartSoftware) {
                            startSoftwareInitialization(MpvOpenGlSurfaceUnavailableReason.SurfaceTimeout)
                        }
                    }
                fallbackJob = job
                previousJob to job
            } ?: return
        admission.first?.cancel()
        admission.second.start()
    }

    private fun startSoftwareInitialization(reason: MpvOpenGlSurfaceUnavailableReason?) {
        // Fast path only — the locked predicate below remains authoritative, so a
        // stale read here can at worst cost one rejected admission. The locked
        // admission preserves accumulated presentation stats for an installed or
        // released controller.
        if (released.get() || mpv != null || ctx != null) return
        presentationMetrics.reset()
        val admission =
            synchronized(engineLock) {
                if (
                    released.get() ||
                    mpv != null ||
                    ctx != null ||
                    quarantinedEngine != null ||
                    engineAwaitingDestroy != null ||
                    openGlTeardownScheduled ||
                    softwareInitializationStarted
                ) {
                    return@synchronized null
                }
                softwareInitializationStarted = true
                fallbackReason = reason
                activeOpenGlSurfaceGeneration = null
                engineInitializationToken = nextPositiveGeneration(engineInitializationToken)
                val token = engineInitializationToken
                _presentationState.value = MpvPresentationState.SoftwareActive(reason)
                val previousFallbackJob = fallbackJob.also { fallbackJob = null }
                val previousInitializationJob = initializationJob
                val job =
                    stateScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        val initialized =
                            initializeEngine(
                                preference = MpvPresentationPreference.Software,
                                surface = null,
                                token = token,
                            )
                        synchronized(engineLock) {
                            softwareInitializationStarted = false
                        }
                        if (initialized) {
                            replayPendingInitializationIntent()
                        } else {
                            synchronized(engineLock) {
                                if (
                                    !released.get() &&
                                    token == engineInitializationToken &&
                                    !openGlTeardownScheduled
                                ) {
                                    _presentationState.value = MpvPresentationState.Failed
                                }
                            }
                        }
                    }
                initializationJob = job
                InitializationAdmission(
                    previousFallbackJob = previousFallbackJob,
                    previousInitializationJob = previousInitializationJob,
                    job = job,
                )
            } ?: return
        admission.previousFallbackJob?.cancel()
        admission.previousInitializationJob?.cancel()
        admission.job.start()
    }

    private fun replayPendingInitializationIntent() {
        val intent = claimPendingPrepareIntent() ?: return
        replayPendingInitializationIntent(intent)
    }

    private fun replayPendingInitializationIntent(intent: PendingPrepareIntent) {
        val replayPlan =
            intent.seekPositionMs?.let { positionMs ->
                intent.plan.copy(startPositionMs = positionMs)
            } ?: intent.plan

        if (!prepare(intent.generation, replayPlan, intent.subtitleAsset, intent.replacedOfflineLease)) return
        synchronized(lifecycleLock) {
            if (intent.generation != prepareGeneration.load() || released.get()) return@synchronized
            intent.audioSelection?.let(::selectEmbeddedAudio)
            if (intent.subtitleSelectionWasSet) {
                selectEmbeddedSubtitle(intent.subtitleSelection)
            }
            setPlaybackSpeed(intent.requestedSpeed)
            setSubtitleStyle(intent.requestedStyle)
            if (intent.shouldPlay) {
                play()
            }
        }
    }

    private fun claimPendingPrepareIntent(): PendingPrepareIntent? =
        synchronized(engineLock) {
            if (engineAcceptedItem || !pendingPrepareAwaitingEngine) return@synchronized null
            val plan = lastPlan ?: return@synchronized null
            val generation = pendingPrepareGeneration ?: return@synchronized null
            PendingPrepareIntent(
                generation = generation,
                plan = plan,
                subtitleAsset = lastSubtitleAsset,
                replacedOfflineLease = pendingReplacedOfflineLease,
                audioSelection = pendingEmbeddedAudioSelection,
                subtitleSelection = pendingEmbeddedSubtitleSelection,
                subtitleSelectionWasSet = pendingSubtitleSelectionWasSet,
                seekPositionMs = pendingSeekPositionMs,
                requestedSpeed = _playbackState.value.playbackSpeed,
                requestedStyle = _playbackState.value.subtitleStyle,
                shouldPlay = playIntent,
            ).also {
                pendingPrepareAwaitingEngine = false
                pendingPrepareGeneration = null
                pendingReplacedOfflineLease = null
                pendingSeekPositionMs = null
                pendingSubtitleSelectionWasSet = false
            }
        }

    private fun terminateInstalledEngine(finishesOpenGlTeardown: Boolean = false): EngineTermination {
        if (quarantinedEngine != null) return EngineTermination.Quarantined
        var pollJobToCancel: Job? = null
        val active =
            synchronized(lifecycleLock) {
                completionReadiness.invalidate()
                synchronized(engineLock) {
                    if (engineAwaitingDestroy != null) return EngineTermination.Deferred
                    val lib = mpv
                    val context = ctx
                    // Clearing the installed context under `engineLock` is what
                    // stops new leases: `acquireEngineLease` reads the context and
                    // takes the lease under the same lock, so no command can start
                    // against an engine whose teardown has begun.
                    mpv = null
                    ctx = null
                    pollJobToCancel = pollJob.also { pollJob = null }
                    if (lib != null && context != null) ActiveMpv(lib, context) else null
                }
            } ?: run {
                releasePendingOfflineLeaseReleases()
                return EngineTermination.Terminated
            }
        pollJobToCancel?.cancel()
        onFrameAvailable.set(null)

        val renderExecutor =
            synchronized(engineLock) {
                openGlRenderExecutor.also { openGlRenderExecutor = null }
            }
        var renderDrained = true
        renderExecutor?.let { executor ->
            executor.shutdown()
            renderDrained = awaitRenderExecutorTermination(executor)
            if (!renderDrained) {
                executor.shutdownNow()
                renderDrained = awaitRenderExecutorTermination(executor)
            }
        }
        openGlRenderScheduled.set(false)
        openGlForceRenderRequested.set(false)

        val renderState =
            if (renderDrained) {
                synchronized(renderLock) {
                    OpenGlRenderState(
                        renderContext = renderCtx,
                        renderContextRef = renderContextRef,
                        apiTypeMemory = apiTypeMemory,
                        surface = openGlSurface,
                        getProcAddressCallback = openGlGetProcAddressCallback,
                        initParams = openGlInitParams,
                    ).also {
                        renderCtx = null
                        renderContextRef = null
                        apiTypeMemory = null
                        openGlSurface = null
                        openGlGetProcAddressCallback = null
                        openGlInitParams = null
                    }
                }
            } else {
                // A hung render may still own renderLock. Do not acquire it on
                // the quarantine path; the holder keeps these references alive
                // while the native/render thread finishes at process exit.
                OpenGlRenderState(
                    renderContext = renderCtx,
                    renderContextRef = renderContextRef,
                    apiTypeMemory = apiTypeMemory,
                    surface = openGlSurface,
                    getProcAddressCallback = openGlGetProcAddressCallback,
                    initParams = openGlInitParams,
                ).also {
                    renderCtx = null
                    renderContextRef = null
                    apiTypeMemory = null
                    openGlSurface = null
                    openGlGetProcAddressCallback = null
                    openGlInitParams = null
                }
            }
        if (!renderDrained) {
            synchronized(engineLock) {
                quarantinedEngine =
                    QuarantinedEngine(
                        lib = active.lib,
                        context = active.ctx,
                        renderState = renderState,
                        renderExecutor = renderExecutor,
                    )
            }
            logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Failed)
            return EngineTermination.Quarantined
        }

        val pending =
            PendingEngineDestroy(
                lib = active.lib,
                context = active.ctx,
                renderState = renderState,
                finishesOpenGlTeardown = finishesOpenGlTeardown,
            )
        if (!drainEngineLeasesOrDefer(pending)) {
            // A command is still inside libmpv. Never free under a live caller;
            // the last lease release destroys the engine and only then reports
            // the detach as terminated. Quarantine stays reserved for a render
            // executor that never drains.
            logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Timeout)
            return EngineTermination.Deferred
        }
        destroyEngine(pending)
        releasePendingOfflineLeaseReleases()
        return EngineTermination.Terminated
    }

    /**
     * Waits — bounded, in the same shape as the render-executor drain — for the
     * outstanding engine-use leases, and records [pending] for deferred
     * destruction when the wait expires. No new lease can be taken while this
     * runs because the installed context was already cleared.
     */
    private fun drainEngineLeasesOrDefer(pending: PendingEngineDestroy): Boolean {
        val deadlineNanos = engineLeaseClockNanos() + TimeUnit.MILLISECONDS.toNanos(ENGINE_LEASE_DRAIN_TIMEOUT_MS)
        var interrupted = false
        while (!interrupted && engineLeaseClockNanos() < deadlineNanos) {
            if (synchronized(engineLock) { engineUseCount == 0 }) break
            try {
                engineLeaseWait(ENGINE_LEASE_DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
            }
        }
        return synchronized(engineLock) {
            if (engineUseCount == 0) {
                true
            } else {
                engineAwaitingDestroy = pending
                false
            }
        }
    }

    private fun destroyEngine(pending: PendingEngineDestroy) {
        pending.renderState.renderContext?.let { renderContext ->
            runCatching { pending.lib.mpv_render_context_set_update_callback(renderContext, null, null) }
                .onFailure { throwable ->
                    logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Failed, throwable)
                }
        }
        pending.renderState.renderContext?.let { renderContext ->
            runCatching {
                val surface = pending.renderState.surface
                if (surface != null) {
                    var freeInvoked = false
                    surface.withCurrentContext {
                        freeInvoked = true
                        pending.lib.mpv_render_context_free(renderContext)
                        false
                    }
                    check(freeInvoked) { "OpenGL context was unavailable during mpv teardown" }
                } else {
                    pending.lib.mpv_render_context_free(renderContext)
                }
            }.onFailure { throwable ->
                logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Failed, throwable)
            }
        }
        runCatching { pending.lib.mpv_terminate_destroy(pending.context) }
            .onFailure { throwable ->
                logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Failed, throwable)
            }
    }

    /**
     * Takes an engine-use lease together with the installed context.
     *
     * `lifecycleLock` does not protect the native context from teardown, so
     * every native command outside the render protocol (which is serialized by
     * `renderLock` and the render-executor drain) runs under this lease.
     * Returns null once teardown has begun, so no command reaches an engine
     * that is awaiting destruction.
     */
    private fun acquireEngineLease(): ActiveMpv? =
        synchronized(engineLock) {
            if (released.get() || quarantinedEngine != null || engineAwaitingDestroy != null) {
                return@synchronized null
            }
            val lib = mpv ?: return@synchronized null
            val context = ctx ?: return@synchronized null
            engineUseCount += 1
            ActiveMpv(lib, context)
        }

    private fun releaseEngineLease() {
        val pending =
            synchronized(engineLock) {
                if (engineUseCount > 0) engineUseCount -= 1
                if (engineUseCount == 0) {
                    engineAwaitingDestroy?.also { engineAwaitingDestroy = null }
                } else {
                    null
                }
            } ?: return
        destroyEngine(pending)
        releasePendingOfflineLeaseReleases()
        if (pending.finishesOpenGlTeardown) {
            completeOpenGlTeardown(pending.renderState.surface, MpvOpenGlDetachDisposition.Terminated)
        }
    }

    private inline fun <T> withEngineLease(block: (ActiveMpv) -> T): T? {
        val active = acquireEngineLease() ?: return null
        return try {
            block(active)
        } finally {
            releaseEngineLease()
        }
    }

    /** [withEngineLease] plus the released-state diagnostic that [activeMpv] emits. */
    private inline fun <T> withEngineLease(
        operation: PlayerOperation,
        block: (ActiveMpv) -> T,
    ): T? {
        if (released.get()) {
            warnReleased(operation)
            return null
        }
        val active = acquireEngineLease() ?: return null
        return try {
            block(active)
        } finally {
            releaseEngineLease()
        }
    }

    /**
     * Releases one detached generation only after mpv accepts its stop command or after the
     * controller proves that no native engine/teardown still owns a file. A null command result
     * is not success: it can mean that teardown is already in flight, so that generation remains
     * queued until [terminateInstalledEngine] has destroyed that exact engine.
     */
    private fun releaseOfflineLeaseAfterNativeStop(lease: OfflineArtifactLease?) {
        if (lease == null) return
        synchronized(lifecycleLock) {
            val stopSucceeded =
                withEngineLease(PlayerOperation.Stop) { active ->
                    active.lib.mpv_command(active.ctx, arrayOf("stop", null)) == 0
                }
            completeOfflineLeaseTeardown(lease, stopSucceeded)
        }
    }

    private fun completeOfflineLeaseTeardown(
        lease: OfflineArtifactLease?,
        nativeStopSucceeded: Boolean?,
    ) {
        if (lease == null) return
        if (nativeStopSucceeded == true || (nativeStopSucceeded == null && !nativeEngineStillOwnsFiles())) {
            lease.release()
        } else {
            synchronized(engineLock) { pendingOfflineLeaseReleases += lease }
        }
    }

    private fun nativeEngineStillOwnsFiles(): Boolean =
        synchronized(engineLock) {
            mpv != null ||
                ctx != null ||
                quarantinedEngine != null ||
                engineAwaitingDestroy != null
        }

    private fun awaitRenderExecutorTermination(executor: ExecutorService): Boolean =
        try {
            executor.awaitTermination(OPENGL_RENDER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun scheduleOpenGlTeardown(onComplete: MpvOpenGlDetachCallback? = null) {
        val admission =
            synchronized(engineLock) {
                if (onComplete != null) {
                    openGlDetachCallbacks += onComplete
                }
                if (openGlTeardownScheduled) {
                    null
                } else {
                    openGlTeardownScheduled = true
                    OpenGlTeardownAdmission(initializationJob)
                }
            }
        if (admission == null) return
        openGlTeardownJob =
            openGlLifecycleScope.launch {
                admission.initializationJob?.join()
                val surface = openGlSurface
                when (terminateInstalledEngine(finishesOpenGlTeardown = true)) {
                    // The destroy is pending on an in-flight native command:
                    // hold the surface open and keep the detach callbacks
                    // queued until the last lease release completes it.
                    EngineTermination.Deferred -> Unit
                    EngineTermination.Terminated ->
                        completeOpenGlTeardown(surface, MpvOpenGlDetachDisposition.Terminated)
                    EngineTermination.Quarantined ->
                        completeOpenGlTeardown(surface, MpvOpenGlDetachDisposition.Quarantined)
                }
            }
    }

    private fun completeOpenGlTeardown(
        surface: MpvOpenGlRenderSurface?,
        disposition: MpvOpenGlDetachDisposition,
    ) {
        if (disposition == MpvOpenGlDetachDisposition.Terminated) {
            runCatching { surface?.close() }
                .onFailure { throwable ->
                    logMpvDiagnostic(PlaybackDiagnosticStage.Release, PlaybackDiagnosticEvent.Failed, throwable)
                }
        }
        val callbacks =
            synchronized(engineLock) {
                activeOpenGlSurfaceGeneration = null
                initializationJob = null
                openGlTeardownJob = null
                openGlTeardownScheduled = false
                openGlDetachCallbacks.toList().also { openGlDetachCallbacks.clear() }
            }
        callbacks.forEach { callback -> callback(disposition) }
        if (released.get()) {
            openGlLifecycleScope.cancel()
        }
    }

    private fun releasePendingOfflineLeaseReleases() {
        val leases =
            synchronized(engineLock) {
                pendingOfflineLeaseReleases.toList().also { pendingOfflineLeaseReleases.clear() }
            }
        leases.forEach(OfflineArtifactLease::release)
    }

    private fun scheduleOpenGlRender() {
        val executor = openGlRenderExecutor ?: return
        if (!openGlRenderScheduled.compareAndSet(false, true)) return
        runCatching {
            executor.execute {
                do {
                    openGlRenderScheduled.set(false)
                    renderOpenGlFrame()
                } while (openGlRenderScheduled.getAndSet(false))
            }
        }.onFailure {
            openGlRenderScheduled.set(false)
        }
    }

    private fun renderOpenGlFrame() {
        if (released.get()) return
        val active = currentActiveMpv() ?: return
        val renderContext = renderCtx ?: return
        val surface = openGlSurface ?: return
        surface.withCurrentContext { framebuffer ->
            if (
                released.get() ||
                framebuffer.widthPx <= 0 ||
                framebuffer.heightPx <= 0 ||
                renderCtx !== renderContext ||
                openGlSurface !== surface
            ) {
                return@withCurrentContext false
            }
            synchronized(renderLock) {
                val updateFlags =
                    runCatching { active.lib.mpv_render_context_update(renderContext) }
                        .getOrElse { throwable ->
                            logMpvDiagnostic(PlaybackDiagnosticStage.Render, PlaybackDiagnosticEvent.Failed, throwable)
                            return@synchronized false
                        }
                val forced = openGlForceRenderRequested.getAndSet(false)
                if (updateFlags and LibMpv.RENDER_UPDATE_FRAME == 0L && !forced) {
                    return@synchronized false
                }

                val fbo =
                    MpvOpenGlFbo(
                        fbo = framebuffer.fboId,
                        width = framebuffer.widthPx,
                        height = framebuffer.heightPx,
                        internalFormat = 0,
                    ).apply { write() }
                val flipY =
                    Memory(Int.SIZE_BYTES.toLong()).apply {
                        setInt(0, if (framebuffer.flipY) 1 else 0)
                    }
                val params =
                    renderParams(
                        LibMpv.RENDER_PARAM_OPENGL_FBO to fbo.pointer,
                        LibMpv.RENDER_PARAM_FLIP_Y to flipY,
                    )
                val result =
                    runCatching { active.lib.mpv_render_context_render(renderContext, params) }
                        .getOrElse { throwable ->
                            logMpvDiagnostic(PlaybackDiagnosticStage.Render, PlaybackDiagnosticEvent.Failed, throwable)
                            return@synchronized false
                        }
                if (result != 0 && !renderErrorLogged) {
                    renderErrorLogged = true
                    logMpvDiagnostic(
                        PlaybackDiagnosticStage.Render,
                        PlaybackDiagnosticEvent.Failed,
                        nativeCode = result.toLong(),
                    )
                } else if (result == 0) {
                    renderErrorLogged = false
                }
                result == 0
            }
        }
    }

    private fun nextEngineInitializationToken(): Long =
        synchronized(engineLock) {
            engineInitializationToken = nextPositiveGeneration(engineInitializationToken)
            engineInitializationToken
        }

    private fun isCurrentInitialization(token: Long): Boolean =
        synchronized(engineLock) {
            token == engineInitializationToken &&
                !released.get() &&
                !openGlTeardownScheduled
        }

    private fun beginPrepareGeneration(): Long? =
        synchronized(lifecycleLock) {
            if (released.get()) return@synchronized null
            val generation = prepareGeneration.addAndFetch(1L)
            completionReadiness.beginPrepare(generation)
            clearPendingExternalSubtitleAttachmentLocked()
            externalSubtitleCommandAccepted = false
            externalSubtitleTrackTitle = null
            generation
        }

    private fun invalidateExternalSubtitleAttachmentLocked() {
        prepareGeneration.addAndFetch(1L)
        completionReadiness.invalidate()
        clearPendingExternalSubtitleAttachmentLocked()
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
    }

    private fun setBestEffortMpvOption(
        lib: LibMpv,
        context: Pointer,
        name: String,
        value: String,
    ) {
        val result =
            runCatching { lib.mpv_set_option_string(context, name, value) }
                .getOrElse { throwable ->
                    logMpvDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Rejected, throwable)
                    return
                }
        if (result != 0) {
            logMpvDiagnostic(
                PlaybackDiagnosticStage.NativePlayer,
                PlaybackDiagnosticEvent.Rejected,
                nativeCode = result.toLong(),
            )
        }
    }

    private fun clearPendingExternalSubtitleAttachmentLocked() {
        pendingExternalSubtitleAttachment = null
        currentStartFilePlaylistEntryId = null
    }

    private fun prepareExternalSubtitleAttachment(
        target: SubtitleActivationTarget?,
        subtitleAsset: SubtitleAsset?,
        offline: Boolean,
        trustedSidecarPath: String?,
    ): ExternalSubtitleAttachmentDraft? {
        val externalTarget =
            target?.takeIf { candidate -> candidate.kind == LocalSubtitleKind.ExternalText }
                ?: return null
        if (offline) {
            val path = trustedSidecarPath
            if (path == null) {
                subtitleActivationConfirmation.fail(
                    externalTarget,
                    reason = SubtitleActivationFailureReason.MissingExternalResource,
                )
                return null
            }
            return ExternalSubtitleAttachmentDraft(
                target = externalTarget,
                resource = File(path).toURI().toString(),
                trackTitle = externalTarget.mpvSubtitleTitle(),
            )
        }
        val subtitle = subtitleAsset ?: return null
        val resource =
            when (subtitle) {
                is SubtitleAsset.JellyfinRemote -> subtitle.url.takeIf(String::isNotBlank)?.stripAuthQueryParams()
                is SubtitleAsset.LocalFile -> localSubtitleFileStore?.resolvePath(subtitle.fileId)
            }
        if (resource == null) {
            subtitleActivationConfirmation.fail(
                externalTarget,
                reason = SubtitleActivationFailureReason.MissingExternalResource,
            )
            return null
        }
        if (subtitle !is SubtitleAsset.LocalFile && !resource.isUnderServer(session.serverUrl)) {
            logMpvDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
            subtitleActivationConfirmation.fail(
                externalTarget,
                reason = SubtitleActivationFailureReason.ResourceOriginRejected,
            )
            return null
        }
        return ExternalSubtitleAttachmentDraft(
            target = externalTarget,
            resource = resource,
            trackTitle = externalTarget.mpvSubtitleTitle(),
        )
    }

    private fun currentPlaylistEntryId(): Long? {
        val playlistPosition = readInt64Property("playlist-pos") ?: return null
        if (playlistPosition < 0L) return null
        return readInt64Property("playlist/$playlistPosition/id")?.takeIf { entryId -> entryId > 0L }
    }

    private fun drainMpvEvents(active: ActiveMpv) {
        while (!released.get()) {
            var claimedAttachment: PendingExternalSubtitleAttachment? = null
            val queueEmpty =
                synchronized(lifecycleLock) {
                    if (released.get()) {
                        true
                    } else {
                        when (val event = nextMpvLifecycleEvent(active)) {
                            MpvLifecycleEvent.None -> true
                            else -> {
                                claimedAttachment = handleMpvLifecycleEventLocked(active, event)
                                false
                            }
                        }
                    }
                }
            // `sub-add` opens the subtitle stream synchronously inside libmpv,
            // so it runs after the lock is released; prepare()/stop() take the
            // same lock on the UI thread.
            claimedAttachment?.let(::attachClaimedExternalSubtitle)
            if (queueEmpty) return
        }
    }

    private fun nextMpvLifecycleEvent(active: ActiveMpv): MpvLifecycleEvent {
        val eventPointer =
            runCatching { active.lib.mpv_wait_event(active.ctx, MPV_EVENT_POLL_TIMEOUT_SECONDS) }
                .getOrElse { throwable ->
                    logMpvDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Failed, throwable)
                    return MpvLifecycleEvent.None
                } ?: return MpvLifecycleEvent.None
        val event = MpvEvent(eventPointer)
        event.read()
        return when (event.eventId) {
            LibMpv.EVENT_NONE -> MpvLifecycleEvent.None
            LibMpv.EVENT_START_FILE -> {
                val data = event.data ?: return MpvLifecycleEvent.Unknown
                val startFile = MpvEventStartFile(data)
                startFile.read()
                MpvLifecycleEvent.StartFile(startFile.playlistEntryId)
            }
            LibMpv.EVENT_END_FILE -> {
                val data = event.data ?: return MpvLifecycleEvent.Unknown
                val endFile = MpvEventEndFile(data)
                endFile.read()
                MpvLifecycleEvent.EndFile(
                    reason = endFile.reason,
                    nativeError = endFile.error,
                    playlistEntryId = endFile.playlistEntryId,
                )
            }
            LibMpv.EVENT_FILE_LOADED -> MpvLifecycleEvent.FileLoaded
            LibMpv.EVENT_QUEUE_OVERFLOW -> MpvLifecycleEvent.QueueOverflow
            else -> MpvLifecycleEvent.Unknown
        }
    }

    /** Returns the attachment whose `sub-add` must be issued outside [lifecycleLock]. */
    private fun handleMpvLifecycleEventLocked(
        active: ActiveMpv,
        event: MpvLifecycleEvent,
    ): PendingExternalSubtitleAttachment? {
        when (event) {
            is MpvLifecycleEvent.StartFile -> {
                currentStartFilePlaylistEntryId = event.playlistEntryId
                completionReadiness.onStartFile(
                    generation = prepareGeneration.load(),
                    playlistEntryId = event.playlistEntryId,
                )
            }
            is MpvLifecycleEvent.EndFile -> handleMpvEndFileLocked(active, event)
            MpvLifecycleEvent.FileLoaded -> {
                completionReadiness.onFileLoaded(
                    generation = prepareGeneration.load(),
                    currentPlaylistEntryId = currentPlaylistEntryId(),
                )
                val claimed = claimPendingExternalSubtitleLocked()
                applyDeferredDefaultTrackSelection(active)
                return claimed
            }
            MpvLifecycleEvent.QueueOverflow -> failPendingExternalSubtitleForEventOverflowLocked()
            MpvLifecycleEvent.None,
            MpvLifecycleEvent.Unknown,
            -> Unit
        }
        return null
    }

    private fun claimPendingExternalSubtitleLocked(): PendingExternalSubtitleAttachment? {
        val pending = pendingExternalSubtitleAttachment ?: return null
        if (
            pending.generation != prepareGeneration.load() ||
            currentStartFilePlaylistEntryId != pending.playlistEntryId ||
            expectedSubtitleTarget != pending.target
        ) {
            return null
        }
        pendingExternalSubtitleAttachment = null
        return pending
    }

    /**
     * Issues the claimed `sub-add` without [lifecycleLock] — libmpv opens the
     * subtitle stream synchronously, so a remote sidecar is unbounded network
     * I/O — and re-validates the correlation before recording acceptance: a
     * prepare, stop or END_FILE can invalidate the attachment while the command
     * is in flight.
     */
    private fun attachClaimedExternalSubtitle(attachment: PendingExternalSubtitleAttachment) {
        val commandResult =
            withEngineLease { active ->
                active.lib.mpv_command(
                    active.ctx,
                    arrayOf("sub-add", attachment.resource, "select", attachment.trackTitle, null),
                )
            }
        val superseded =
            synchronized(lifecycleLock) {
                val stale =
                    released.get() ||
                        attachment.generation != prepareGeneration.load() ||
                        currentStartFilePlaylistEntryId != attachment.playlistEntryId ||
                        expectedSubtitleTarget != attachment.target
                if (!stale) {
                    externalSubtitleTrackTitle = attachment.trackTitle
                    externalSubtitleCommandAccepted = commandResult == 0
                }
                stale
            }
        if (superseded) {
            logMpvDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
            subtitleActivationConfirmation.fail(
                attachment.target,
                reason = SubtitleActivationFailureReason.MpvSubAddSuperseded,
            )
            return
        }
        if (commandResult != 0) {
            logMpvDiagnostic(
                PlaybackDiagnosticStage.Mapping,
                PlaybackDiagnosticEvent.Rejected,
                nativeCode = commandResult?.toLong(),
            )
            subtitleActivationConfirmation.fail(
                attachment.target,
                reason = SubtitleActivationFailureReason.MpvSubAddRejected,
            )
        }
    }

    private fun handleMpvEndFileLocked(
        active: ActiveMpv,
        event: MpvLifecycleEvent.EndFile,
    ) {
        val isCurrentEntry = currentStartFilePlaylistEntryId == event.playlistEntryId
        if (isCurrentEntry) {
            currentStartFilePlaylistEntryId = null
        }

        if (
            event.reason == LibMpv.END_FILE_REASON_ERROR &&
            isCurrentEntry &&
            loaded.get() &&
            !released.get() &&
            !stopping.get() &&
            _playbackState.value.status != PlaybackStatus.Failed
        ) {
            failPlayback(
                message = "mpv playback failed",
                detail = readMpvErrorString(active.lib, event.nativeError),
                nativeCode = event.nativeError.toLong(),
            )
        }

        val pending =
            pendingExternalSubtitleAttachment
                ?.takeIf { attachment -> attachment.playlistEntryId == event.playlistEntryId }
                ?: return
        if (
            event.reason != LibMpv.END_FILE_REASON_EOF &&
            event.reason != LibMpv.END_FILE_REASON_ERROR &&
            event.reason != LibMpv.END_FILE_REASON_REDIRECT
        ) {
            return
        }
        pendingExternalSubtitleAttachment = null
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
        logMpvDiagnostic(
            PlaybackDiagnosticStage.Mapping,
            PlaybackDiagnosticEvent.Failed,
            nativeCode = if (event.nativeError != 0) event.nativeError.toLong() else event.reason.toLong(),
        )
        subtitleActivationConfirmation.fail(
            pending.target,
            reason = SubtitleActivationFailureReason.MpvLoadEndedBeforeSubAdd,
        )
    }

    private fun readMpvErrorString(
        lib: LibMpv,
        error: Int,
    ): String? =
        runCatching { lib.mpv_error_string(error)?.getString(0) }
            .getOrNull()

    private fun applyDeferredDefaultTrackSelection(active: ActiveMpv) {
        val trackCount = readInt64Property("track-list/count")?.toInt()?.coerceAtLeast(0) ?: return
        if (trackCount == 0) return

        val tracks =
            (0 until trackCount).mapNotNull { index ->
                val selectorId = readInt64Property("track-list/$index/id") ?: return@mapNotNull null
                val type = readStringProperty("track-list/$index/type") ?: return@mapNotNull null
                selectorId to (type to (readFlagProperty("track-list/$index/external") == true))
            }
        val firstVideoIndex =
            (0 until trackCount).firstOrNull { index ->
                val type = readStringProperty("track-list/$index/type")
                type != null && type.equals(MPV_TRACK_TYPE_VIDEO, ignoreCase = true)
            }
        firstVideoIndex?.let { index ->
            readInt64Property("track-list/$index/id")?.let { selectorId ->
                active.lib.mpv_set_property_string(active.ctx, "vid", selectorId.toString())
            }
        }

        val pendingAudioSelectorId =
            pendingEmbeddedAudioSelection?.let { selection ->
                val candidateCount = mpvEmbeddedTrackCount(MPV_TRACK_TYPE_AUDIO)
                val resolution = resolveMpvTrack(selection.descriptor, MPV_TRACK_TYPE_AUDIO)
                logTrackResolution(
                    kind = PlaybackDiagnosticTrackKind.Audio,
                    target = selection.target,
                    candidateCount = candidateCount,
                    resolution = resolution,
                )
                resolution.candidate
                    ?.selectorId
                    ?.takeIf { resolution.result == NativeTrackMappingResult.Active }
                    ?.also { selectorId -> resolvedAudioSelectorId = selectorId }
            }
        val firstAudioId =
            tracks
                .firstOrNull { (_, typeAndExternal) ->
                    typeAndExternal.first.equals(MPV_TRACK_TYPE_AUDIO, ignoreCase = true) && !typeAndExternal.second
                }?.first
        (pendingAudioSelectorId ?: firstAudioId)?.let { selectorId ->
            active.lib.mpv_set_property_string(active.ctx, "aid", selectorId.toString())
        }
    }

    private fun failPendingExternalSubtitleForEventOverflowLocked() {
        val pending = pendingExternalSubtitleAttachment
        clearPendingExternalSubtitleAttachmentLocked()
        if (pending == null) return
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
        logMpvDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
        subtitleActivationConfirmation.fail(
            pending.target,
            reason = SubtitleActivationFailureReason.MpvEventQueueOverflow,
        )
    }

    private fun pollState() {
        if (released.get() || ctx == null || mpv == null) return
        val generation =
            synchronized(lifecycleLock) {
                val currentGeneration = prepareGeneration.load()
                currentGeneration.takeIf { candidate ->
                    !released.get() &&
                        !stopping.get() &&
                        completionReadiness.isCurrentGeneration(candidate)
                }
            } ?: return
        val previous = _playbackState.value
        if (previous.status == PlaybackStatus.Failed) return

        val positionMs = readDoubleProperty("time-pos")?.toPlaybackMillis() ?: previous.positionMs
        val durationResult = readDoubleProperty("duration")
        val durationMs = durationResult?.takeIf { value -> value > 0.0 }?.toPlaybackMillis()
        val trackCount = readInt64Property("track-list/count")?.toInt()?.coerceAtLeast(0) ?: 0
        val paused = readFlagProperty("pause") ?: (previous.status == PlaybackStatus.Paused)
        val buffering = readFlagProperty("paused-for-cache") ?: false
        val seeking = readFlagProperty("seeking") ?: false
        val coreIdle = readFlagProperty("core-idle") ?: false
        val eofReached = readFlagProperty("eof-reached")
        val idle = readFlagProperty("idle-active") ?: false
        val completionObservation =
            synchronized(lifecycleLock) {
                if (released.get() || stopping.get()) {
                    null
                } else {
                    completionReadiness.observeEof(generation, eofReached)
                }
            } ?: return
        val bufferedPositionMs =
            readDoubleProperty("demuxer-cache-time")
                ?.toPlaybackMillis()
                ?.coerceAtLeast(positionMs)
                ?: 0L
        val committed =
            synchronized(lifecycleLock) {
                if (
                    released.get() ||
                    stopping.get() ||
                    !completionReadiness.isCurrent(completionObservation)
                ) {
                    false
                } else {
                    if (completionObservation.unresolvedEntryFallbackArmed) {
                        logMpvDiagnostic(
                            stage = PlaybackDiagnosticStage.NativePlayer,
                            event = PlaybackDiagnosticEvent.Fallback,
                            reason = PlaybackCompletionReadinessReason.PlaylistEntryUnresolved,
                            candidateCount = 1,
                        )
                    }
                    if (trackCount > 0 && completionReadiness.isArmed(generation)) {
                        loaded.set(true)
                        withEngineLease(PlayerOperation.SubtitleActivation) { active ->
                            applyPendingEmbeddedAudioSelection(active)
                            applyPendingEmbeddedSubtitleSelection(active)
                            updateAudioActivation()
                            updateSubtitleActivation()
                        }
                    }
                    val completed = completionObservation.completed
                    val status =
                        when {
                            completed -> PlaybackStatus.Completed
                            !loaded.get() -> if (idle) PlaybackStatus.Idle else PlaybackStatus.Loading
                            buffering || seeking || (coreIdle && !paused && loaded.get() && !completed) ->
                                PlaybackStatus.Buffering
                            paused -> PlaybackStatus.Paused
                            else -> PlaybackStatus.Playing
                        }

                    if (status != PlaybackStatus.Playing && previous.status == PlaybackStatus.Playing) {
                        presentationMetrics.resetPublicationCadence()
                    }
                    _playbackState.update { current ->
                        current.copy(
                            status = status,
                            positionMs = positionMs,
                            durationMs = durationMs ?: current.durationMs,
                            bufferedPositionMs = bufferedPositionMs,
                        )
                    }
                    true
                }
            }
        if (!committed) return
        updateRuntimeDiagnosticsIfDue()
    }

    private fun updateRuntimeDiagnosticsIfDue() {
        if (!loaded.get()) {
            runtimeDiagnosticsPollTicks.store(0)
            resetDroppedFrameBaseline()
            return
        }
        if (_playbackState.value.status != PlaybackStatus.Playing) {
            resetDroppedFrameBaseline()
        }
        // Decide decrement-vs-sample atomically, and let exactly one caller own the
        // sample: this runs from both the background poll and UI-triggered paths, so a
        // plain load-then-act lets two callers consume the same tick, or both reset and
        // both publish the same tick's native reads.
        while (true) {
            val remainingTicks = runtimeDiagnosticsPollTicks.load()
            if (remainingTicks > 0) {
                if (runtimeDiagnosticsPollTicks.compareAndSet(remainingTicks, remainingTicks - 1)) return
                continue
            }
            // Claim the sample by winning the reset; a loser leaves it to the winner.
            if (!runtimeDiagnosticsPollTicks.compareAndSet(remainingTicks, RUNTIME_DIAGNOSTICS_POLL_TICKS)) return
            break
        }
        val frameRate = readDoubleProperty("estimated-vf-fps")
        val hardwareDecoder = readStringProperty("hwdec-current")
        val state = _presentationState.value
        val softwarePresentation = state is MpvPresentationState.SoftwareActive
        if (softwarePresentation) {
            presentationMetrics.setInstalledFrameRate(frameRate ?: lastPlan?.videoPresentation?.frameRate)
        }
        val droppedFrames = readInt64Property("frame-drop-count")
        val decoderDroppedFrames = readInt64Property("decoder-frame-drop-count")
        val diagnostics =
            mpvRuntimeDiagnostics(
                videoCodec = readStringProperty("video-codec"),
                hardwareDecoder = hardwareDecoder,
                width = readInt64Property("video-params/w")?.toPositiveIntOrNull(),
                height = readInt64Property("video-params/h")?.toPositiveIntOrNull(),
                frameRate = frameRate,
                droppedFrames = droppedFrames,
                cacheSpeedBytesPerSecond = readInt64Property("cache-speed"),
                decoderDroppedFrames = decoderDroppedFrames,
                presentation = if (softwarePresentation) presentationMetrics.snapshot() else null,
                presentationPath =
                    when (state) {
                        is MpvPresentationState.OpenGlActive ->
                            openGlSurface?.presentationLabel ?: MPV_PRESENTATION_OPENGL
                        is MpvPresentationState.SoftwareActive -> MPV_PRESENTATION_SOFTWARE
                        else -> null
                    },
            )
        _runtimeDiagnostics.value = diagnostics.copy(prepareEpoch = prepareGeneration.load())
        if (_playbackState.value.status == PlaybackStatus.Playing) {
            val poll =
                mpvDroppedFramePoll(
                    previousOutputCount = previousOutputDroppedFrames,
                    previousDecoderCount = previousDecoderDroppedFrames,
                    previousTimeNanos = previousDroppedFrameSampleNanos,
                    currentOutputCount = droppedFrames,
                    currentDecoderCount = decoderDroppedFrames,
                    nowNanos = System.nanoTime(),
                )
            previousOutputDroppedFrames = poll.outputBaselineCount
            previousDecoderDroppedFrames = poll.decoderBaselineCount
            previousDroppedFrameSampleNanos = poll.baselineTimeNanos
            poll.measurement?.let { measurement -> droppedFrameMeasurementsChannel.trySend(measurement) }
        }
        if (DesktopPlaybackProbe.isEnabled) {
            val playback = _playbackState.value
            DesktopPlaybackProbe.emit(
                MpvStatusProbeRecord(
                    status = DesktopProbeToken.from(playback.status),
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    streamMode = DesktopProbeToken.from(lastPlan?.streamMode),
                    presentation = DesktopProbeToken.from(diagnostics.presentationPath),
                    hardwareDecodeRequested = DesktopProbeToken.from(requestedHardwareDecodePolicy.optionValue),
                    hardwareDecodeResolved = DesktopProbeToken.from(hardwareDecoder),
                    decoder = DesktopProbeToken.from(diagnostics.videoDecoderName),
                    width = diagnostics.videoWidth,
                    height = diagnostics.videoHeight,
                    frameRate = diagnostics.videoFrameRate,
                    droppedFrames = diagnostics.droppedVideoFrames,
                    decoderDroppedFrames = diagnostics.decoderDroppedVideoFrames,
                    outputDroppedFrames = diagnostics.outputDroppedVideoFrames,
                ),
            )
        }
    }

    private fun resetDroppedFrameBaseline() {
        previousOutputDroppedFrames = null
        previousDecoderDroppedFrames = null
        previousDroppedFrameSampleNanos = null
    }

    private fun readDoubleProperty(name: String): Double? {
        val lib = mpv ?: return null
        val context = ctx ?: return null
        val memory = Memory(DOUBLE_BYTES)
        return runCatching {
            if (lib.mpv_get_property(context, name, LibMpv.FORMAT_DOUBLE, memory) == 0) {
                memory.getDouble(0)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun readFlagProperty(name: String): Boolean? {
        val lib = mpv ?: return null
        val context = ctx ?: return null
        val memory = Memory(FLAG_BYTES)
        return runCatching {
            if (lib.mpv_get_property(context, name, LibMpv.FORMAT_FLAG, memory) == 0) {
                memory.getInt(0) != 0
            } else {
                null
            }
        }.getOrNull()
    }

    private fun readInt64Property(name: String): Long? {
        val lib = mpv ?: return null
        val context = ctx ?: return null
        val memory = Memory(Long.SIZE_BYTES.toLong())
        return runCatching {
            if (lib.mpv_get_property(context, name, LibMpv.FORMAT_INT64, memory) == 0) {
                memory.getLong(0)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun readStringProperty(name: String): String? {
        val lib = mpv ?: return null
        val context = ctx ?: return null
        return runCatching {
            val value = lib.mpv_get_property_string(context, name) ?: return@runCatching null
            try {
                value.getString(0)
            } finally {
                lib.mpv_free(value)
            }
        }.getOrNull()
    }

    private fun applyVolumeState(
        lib: LibMpv,
        context: Pointer,
        state: PlayerVolumeState,
    ) {
        lib.mpv_set_property_string(context, "volume", state.volumePercent.toDouble().toString())
        lib.mpv_set_property_string(context, "mute", if (state.muted) "yes" else "no")
    }

    private fun applyPendingEmbeddedSubtitleSelection(active: ActiveMpv) {
        if (!loaded.get()) return
        val selection = pendingEmbeddedSubtitleSelection ?: return
        if (expectedSubtitleTarget != selection.target) return
        val candidateCount = mpvEmbeddedTrackCount(MPV_TRACK_TYPE_SUBTITLE)
        val resolution = resolveMpvTrack(selection.descriptor, MPV_TRACK_TYPE_SUBTITLE)
        logTrackResolution(
            kind = PlaybackDiagnosticTrackKind.Subtitle,
            target = selection.target,
            candidateCount = candidateCount,
            resolution = resolution,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val selectorId = resolution.candidate?.selectorId ?: return
                resolvedSubtitleSelectorId = selectorId
                active.lib.mpv_set_property_string(active.ctx, "sid", selectorId.toString())
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            ->
                subtitleActivationConfirmation.fail(
                    selection.target,
                    result = resolution.result,
                    candidateCount = candidateCount,
                )
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> Unit
        }
    }

    private fun applyPendingEmbeddedAudioSelection(active: ActiveMpv) {
        if (!loaded.get()) return
        val selection = pendingEmbeddedAudioSelection ?: return
        val candidateCount = mpvEmbeddedTrackCount(MPV_TRACK_TYPE_AUDIO)
        val resolution = resolveMpvTrack(selection.descriptor, MPV_TRACK_TYPE_AUDIO)
        logTrackResolution(
            kind = PlaybackDiagnosticTrackKind.Audio,
            target = selection.target,
            candidateCount = candidateCount,
            resolution = resolution,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val selectorId = resolution.candidate?.selectorId ?: return
                resolvedAudioSelectorId = selectorId
                active.lib.mpv_set_property_string(active.ctx, "aid", selectorId.toString())
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> audioActivationConfirmation.fail(selection.target, resolution.result, candidateCount)
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> Unit
        }
    }

    private fun updateAudioActivation() {
        val selection = pendingEmbeddedAudioSelection ?: return
        val selectorId = resolvedAudioSelectorId
        if (selectorId != null && readInt64Property("aid") == selectorId) {
            initialAudioGate = false
            audioActivationConfirmation.confirm(selection.target)
        } else if (loaded.get()) {
            audioActivationConfirmation.armTimeout(selection.target, mpvEmbeddedTrackCount(MPV_TRACK_TYPE_AUDIO))
        }
    }

    private fun releasePlayIntentAfterAudioActivation() {
        initialAudioGate = false
        if (playIntent) {
            withEngineLease(PlayerOperation.AudioActivation) { active ->
                active.lib.mpv_set_property_string(active.ctx, "pause", "no")
            }
        }
    }

    private fun updateSubtitleActivation() {
        val target = expectedSubtitleTarget
        if (target == null) {
            subtitleActivationConfirmation.clear()
            return
        }
        val activeTarget =
            when (target.kind) {
                LocalSubtitleKind.EmbeddedText,
                LocalSubtitleKind.EmbeddedBitmap,
                LocalSubtitleKind.HlsText,
                -> {
                    val selection = pendingEmbeddedSubtitleSelection?.takeIf { pending -> pending.target == target }
                    selection != null &&
                        resolvedSubtitleSelectorId != null &&
                        readInt64Property("sid") == resolvedSubtitleSelectorId
                }
                LocalSubtitleKind.ExternalText ->
                    hasAcceptedExternalSubtitleCommand(target) && selectedExternalSubtitleMatches(target)
            }
        if (activeTarget) {
            subtitleActivationConfirmation.confirm(target)
        } else if (
            loaded.get() &&
            (target.kind != LocalSubtitleKind.ExternalText || hasAcceptedExternalSubtitleCommand(target))
        ) {
            subtitleActivationConfirmation.armTimeout(target, mpvEmbeddedTrackCount(MPV_TRACK_TYPE_SUBTITLE))
        }
    }

    private fun resolveMpvTrack(
        descriptor: com.jellyscope.core.domain.playback.PlannedEmbeddedTrack,
        type: String,
    ): NativeTrackResolution<MpvTrackDescriptor> {
        val trackCount = readInt64Property("track-list/count")?.toInt()?.coerceAtLeast(0) ?: 0
        val tracks =
            (0 until trackCount).mapNotNull { index ->
                val selectorId = readInt64Property("track-list/$index/id") ?: return@mapNotNull null
                MpvTrackDescriptor(
                    selectorId = selectorId,
                    type = readStringProperty("track-list/$index/type"),
                    external = readFlagProperty("track-list/$index/external") == true,
                    ffIndex = readInt64Property("track-list/$index/ff-index")?.toInt(),
                    codec = readStringProperty("track-list/$index/codec"),
                    language = readStringProperty("track-list/$index/lang"),
                    label = readStringProperty("track-list/$index/title"),
                )
            }
        return resolveMpvTrackDescriptor(descriptor, tracks, type)
    }

    private fun mpvEmbeddedTrackCount(type: String): Int {
        val trackCount = readInt64Property("track-list/count")?.toInt()?.coerceAtLeast(0) ?: return 0
        return (0 until trackCount).count { index ->
            readStringProperty("track-list/$index/type").equals(type, ignoreCase = true) &&
                readFlagProperty("track-list/$index/external") != true
        }
    }

    private fun selectedExternalSubtitleMatches(target: SubtitleActivationTarget): Boolean {
        val expectedTitle =
            synchronized(lifecycleLock) {
                externalSubtitleTrackTitle
            } ?: return false
        if (expectedTitle != target.mpvSubtitleTitle()) return false
        val trackCount = readInt64Property("track-list/count")?.toInt()?.coerceAtLeast(0) ?: return false
        return (0 until trackCount).any { index ->
            readFlagProperty("track-list/$index/selected") == true &&
                readFlagProperty("track-list/$index/external") == true &&
                readStringProperty("track-list/$index/title") == expectedTitle
        }
    }

    private fun hasAcceptedExternalSubtitleCommand(target: SubtitleActivationTarget): Boolean =
        synchronized(lifecycleLock) {
            externalSubtitleCommandAccepted && expectedSubtitleTarget == target
        }

    private fun applySubtitleStyle(
        lib: LibMpv,
        context: Pointer,
        style: SubtitleStyle,
    ) {
        style
            .toMpvSubtitleProperties(
                scaledPixelMargin = resolveMpvSubtitleBottomMargin(subtitleClearanceActive),
            ).forEach { (name, value) ->
                lib.mpv_set_property_string(context, name, value)
            }
    }

    private fun applySubtitleClearance(
        lib: LibMpv,
        context: Pointer,
    ) {
        lib.mpv_set_property_string(
            context,
            "sub-margin-y",
            resolveMpvSubtitleBottomMargin(subtitleClearanceActive).toString(),
        )
    }

    private fun applyFillCrop(
        lib: LibMpv,
        context: Pointer,
    ) {
        lib.mpv_set_property_string(
            context,
            "panscan",
            if (fillCrop) {
                "1.0"
            } else {
                "0"
            },
        )
    }

    private fun applyJellyfinAuthHeader(
        active: ActiveMpv,
        attachCredentials: Boolean,
    ): Boolean {
        // Always write the option (empty when not attaching) so a stale header from a
        // previous same-origin prepare cannot leak to a later cross-origin URL.
        val tokenOnlyAuthorization =
            if (attachCredentials) {
                AuthHeaderBuilder.buildTokenOnly(session.accessToken)
            } else {
                null
            }
        val headerValue =
            tokenOnlyAuthorization
                ?.let { value -> "$JELLYFIN_AUTHORIZATION_HEADER_NAME: $value" }
                .orEmpty()
        val result =
            active.lib.mpv_set_option_string(
                active.ctx,
                "http-header-fields",
                headerValue,
            )
        if (result != 0) {
            failPlayback("mpv prepare failed while applying Jellyfin auth header")
            return false
        }
        return true
    }

    private fun activeMpv(operation: PlayerOperation): ActiveMpv? {
        if (released.get()) {
            warnReleased(operation)
            return null
        }
        return currentActiveMpv()
    }

    /**
     * The installed engine, or null once release or teardown has begun. Callers
     * that issue native commands must take a lease instead ([withEngineLease]);
     * this is the presence check used by the presentation state machine and by
     * the render paths, whose lifetime is governed by `renderLock` and the
     * render-executor drain.
     */
    private fun currentActiveMpv(): ActiveMpv? {
        if (released.get() || quarantinedEngine != null || engineAwaitingDestroy != null) return null
        val lib = mpv ?: return null
        val context = ctx ?: return null
        return ActiveMpv(lib, context)
    }

    private fun warnReleased(operation: PlayerOperation) {
        logMpvDiagnostic(
            PlaybackDiagnosticStage.Release,
            PlaybackDiagnosticEvent.Rejected,
            operation = operation,
        )
    }

    private fun failPlayback(
        message: String,
        throwable: Throwable? = null,
        detail: String? = throwable?.toString(),
        nativeCode: Long? = null,
    ) {
        presentationMetrics.resetPublicationCadence()
        synchronized(lifecycleLock) {
            completionReadiness.invalidate()
        }
        logMpvDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Failed, throwable, nativeCode)
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Failed,
                bufferedPositionMs = 0L,
                error = classifyMpvPlaybackError(message = message, detail = detail, nativeCode = nativeCode),
            )
        }
    }

    private fun logMpvDiagnostic(
        stage: PlaybackDiagnosticStage,
        event: PlaybackDiagnosticEvent,
        throwable: Throwable? = null,
        nativeCode: Long? = null,
        operation: PlaybackDiagnosticOperation? = null,
        reason: DiagnosticReason? = null,
        candidateCount: Int? = null,
    ) {
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = stage,
                    event = event,
                    platform = PlaybackDiagnosticPlatform.Desktop,
                    exceptionType = throwable?.playbackExceptionType(),
                    nativeCode = nativeCode,
                    operation = operation,
                    reason = reason,
                    candidateCount = candidateCount,
                ),
            )
        }
    }

    private fun logTrackResolution(
        kind: PlaybackDiagnosticTrackKind,
        target: Any?,
        candidateCount: Int,
        resolution: NativeTrackResolution<MpvTrackDescriptor>,
    ) {
        if (
            !trackResolutionDiagnostics.admit(
                kind = kind,
                target = target,
                candidateCount = candidateCount,
                result = resolution.result,
                reason = resolution.reason,
            )
        ) {
            return
        }
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Resolved,
                    platform = PlaybackDiagnosticPlatform.Desktop,
                    trackKind = kind,
                    candidateCount = candidateCount,
                    mappingResult = resolution.result,
                    mappingReason = resolution.reason,
                ),
            )
        }
    }
}

internal fun mpvRuntimeDiagnostics(
    videoCodec: String?,
    hardwareDecoder: String?,
    width: Int?,
    height: Int?,
    frameRate: Double?,
    droppedFrames: Long?,
    cacheSpeedBytesPerSecond: Long?,
    decoderDroppedFrames: Long? = null,
    presentation: MpvPresentationMetricsSnapshot? = null,
    presentationPath: String? = null,
): PlaybackRuntimeDiagnostics =
    PlaybackRuntimeDiagnostics(
        videoDecoderName =
            listOfNotNull(videoCodec?.takeIf(String::isNotBlank), hardwareDecoder?.takeIf(String::isNotBlank))
                .joinToString(" · ")
                .takeIf(String::isNotBlank),
        videoWidth = width?.takeIf { value -> value > 0 },
        videoHeight = height?.takeIf { value -> value > 0 },
        videoFrameRate = frameRate?.takeIf { value -> value.isFinite() && value > 0.0 },
        droppedVideoFrames = droppedFrames?.takeIf { count -> count >= 0L },
        decoderDroppedVideoFrames = decoderDroppedFrames?.takeIf { count -> count >= 0L },
        outputDroppedVideoFrames = droppedFrames?.takeIf { count -> count >= 0L },
        recentVideoRenderP95Ms = presentation?.renderP95Ms,
        recentPresentedFrameRate = presentation?.presentedFrameRate,
        presentationGapCount = presentation?.presentationGapCount,
        presentationPath = presentationPath,
        bandwidthEstimateBps =
            cacheSpeedBytesPerSecond
                ?.takeIf { speed -> speed > 0L && speed <= Long.MAX_VALUE / BITS_PER_BYTE }
                ?.times(BITS_PER_BYTE),
    )

private fun Long.toPositiveIntOrNull(): Int? =
    takeIf { value -> value in 1L..Int.MAX_VALUE.toLong() }
        ?.toInt()

private fun SubtitleActivationTarget.mpvSubtitleTitle(): String = "jellyscope-external-$requestId-$streamIndex"

private const val MPV_EVENT_POLL_TIMEOUT_SECONDS = 0.0
