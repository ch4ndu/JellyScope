// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.view.Surface
import android.view.View
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import com.jellyscope.core.domain.playback.PlaybackBufferPolicy
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackNativeAudioOutput
import com.jellyscope.core.domain.playback.PlaybackNativeCommandShape
import com.jellyscope.core.domain.playback.PlaybackNativeFailureReason
import com.jellyscope.core.domain.playback.PlaybackNativePlayerMilestone
import com.jellyscope.core.domain.playback.PlaybackNativeSampleCause
import com.jellyscope.core.domain.playback.PlaybackNativeVideoFormat
import com.jellyscope.core.domain.playback.PlaybackNativeVideoOutput
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.mpvDroppedFramePoll
import com.jellyscope.core.domain.playback.mpvVideoDecodingMode
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.playback.AndroidMpvLifecycleDecision.Completed
import com.jellyscope.core.playback.AndroidMpvLifecycleDecision.Failed
import com.jellyscope.core.playback.AndroidMpvLifecycleDecision.Ready
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.EndFile
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.EofReached
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.FileLoaded
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.NativeFailure
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.Release
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.StartupTimeout
import com.jellyscope.core.playback.AndroidMpvLifecycleEvent.Stop
import com.jellyscope.core.playback.toMpvSubtitleProperties
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class AndroidMpvInitializationException(
    val constructionStage: PlaybackBackendConstructionStage,
    val configurationKey: String? = null,
    val nativeFailureCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException("mpv native initialization failed", cause)

internal fun androidMpvLoadCommand(
    streamUrl: String,
    startPositionMs: Long,
): Array<String> {
    val normalizedPositionMs = startPositionMs.coerceAtLeast(0L)
    val wholeSeconds = normalizedPositionMs / 1_000L
    val milliseconds = (normalizedPositionMs % 1_000L).toString().padStart(3, '0')
    return arrayOf(
        "loadfile",
        streamUrl,
        "replace",
        "-1",
        "start=$wholeSeconds.$milliseconds",
    )
}

internal fun androidMpvSurfaceOptionFailureDiagnostic(
    prepareSequence: Long?,
    nativeCode: Int,
): String =
    formatPlaybackDiagnostic(
        PlaybackDiagnostic(
            stage = PlaybackDiagnosticStage.NativePlayer,
            event = PlaybackDiagnosticEvent.NativeLifecycle,
            platform = PlaybackDiagnosticPlatform.Android,
            backend = PlayerBackend.Mpv,
            prepareSequence = prepareSequence,
            backendConfigurationKey = "force-window",
            nativeCode = nativeCode.toLong(),
        ),
    )

/** Android in-process mpv controller. Native work is serialized off Main. */
internal class AndroidMpvPlayerController(
    context: Context,
    private val session: Session,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val audioFocusCoordinator: AndroidAudioFocusCoordinator? = null,
    private val engineFactory: AndroidMpvEngineFactory = AndroidMpvDefaultEngineFactory,
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    nativeDispatcherIsAlreadySerial: Boolean = false,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val startupTimeout: Duration = 15.seconds,
    private val seekConfirmationTimeout: Duration = 5.seconds,
    private val networkPolicy: AndroidMpvNetworkPolicyContract =
        AndroidMpvNetworkPolicy(
            context = context.applicationContext,
            session = session,
            localSubtitleFileStore = localSubtitleFileStore,
        ),
    private val enginePolicy: AndroidMpvEnginePolicy =
        androidMpvEnginePolicy(androidMpvDeviceFacts(context.applicationContext)),
    /**
     * mpv's verbose log-file is written only while diagnostics collection is
     * enabled here; absent (tests, missed wiring) means never write to disk.
     */
    private val logCollectionPreferences: LogCollectionPreferenceStore? = null,
    /** Non-null only when construction is already running on the worker dispatcher. */
    initialCaBundlePath: String? = null,
) : PlayerController,
    AndroidPlayerSurfaceBridge {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val nativeScope =
        CoroutineScope(
            SupervisorJob() +
                if (nativeDispatcherIsAlreadySerial) {
                    nativeDispatcher
                } else {
                    nativeDispatcher.limitedParallelism(1)
                },
        )
    private val nativeMutex = Mutex()
    private val releasedFlag = AtomicBoolean(false)
    private val nativeEventGeneration = AtomicLong(NO_NATIVE_EVENT_GENERATION)
    private val nativeLoadOutstanding = AtomicBoolean(false)
    private val expectedReplacementEndFiles = AtomicInteger(0)
    private val hotClockSample = AtomicReference<AndroidMpvHotClockSample?>(null)
    private val hotDiagnosticSample = AtomicReference<AndroidMpvHotDiagnosticSample?>(null)
    private val hotClockSignal = Channel<Unit>(Channel.CONFLATED)
    private val hotDiagnosticSignal = Channel<Unit>(Channel.CONFLATED)
    private val hotSampleEpoch =
        AtomicReference<AndroidMpvHotSampleEpoch>(AndroidMpvHotSampleEpoch.Inactive)
    private val immediateClockGeneration = AtomicLong(NO_NATIVE_EVENT_GENERATION)
    private val immediateClockRequested = AtomicBoolean(false)

    private var engine: AndroidMpvEngine? = null

    /** Written on the native queue, read from Main to gate the destroy barrier. */
    @Volatile
    private var engineAttachedSurface: Surface? = null

    /**
     * Surface attach/detach jobs enqueued but not yet completed. The destroy
     * barrier must also stand while an attach is still queued behind a busy
     * native queue — [engineAttachedSurface] only reflects executed attaches.
     */
    private val outstandingSurfaceOps = AtomicInteger(0)

    @Volatile
    private var nativeReady = false

    @Volatile
    private var generation = 0L
    private var lastPlan: PlaybackPlan? = null
    private var lastSubtitleAsset: SubtitleAsset? = null
    private var playIntent = false
    private var playbackFocusAdmitted = audioFocusCoordinator == null
    private var resumeConfirmationFromPositionMs: Long? = null
    private var pendingSeekFromPositionMs: Long? = null
    private var pendingSeekTargetPositionMs: Long? = null
    private var pendingSeekWatchdog: Job? = null
    private var resumeAfterTransientFocusLoss = false
    private val isDucked = AtomicBoolean(false)
    private val volumeBeforeDuck = AtomicReference<Double?>(null)
    private var surfaceView: AndroidMpvSurfaceView? = null
    private var attachedSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var presentation = AndroidSurfacePresentation(AndroidSurfaceResizeMode.Fit)
    private var pendingAudioSelection: EmbeddedAudioSelection? = null
    private var pendingSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var subtitleDisabledExplicitly = false
    private var pendingAudioSelectorId: Long? = null
    private var pendingSubtitleSelectorId: Long? = null
    private var trackDescriptors: List<MpvTrackDescriptor> = emptyList()
    private var trackListObserved = false
    private var externalSubtitleCommandAccepted = false
    private var externalSubtitleTrackTitle: String? = null
    private var networkRequest: AndroidMpvNetworkRequest? = null
    private var startupWatchdog: Job? = null
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()

    /**
     * Leases detached by an enqueued native stop. A stop callback may race release(): callbacks
     * leave the lease here once release has claimed the controller, and the destroy job drains it
     * only after the engine has actually stopped and been destroyed.
     */
    private val pendingNativeStopLeases = mutableListOf<OfflineArtifactLease>()
    private val offlineLeaseLock = Any()
    private var offlinePath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null
    private var offlineSidecarPath: String? = null

    /** Platform DI seam; the public controller construction remains remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    /** Written from construction/IO/Main paths, read from Main by the watchdog. */
    @Volatile
    private var diagnosticLogPath: String? = null
    private var diagnosticLogWatchdog: Job? = null
    private var initialAudioGate = false
    private var tvSurfaceLossGeneration: Long? = null
    private var tvSurfaceReloadAttemptedGeneration: Long? = null
    private var playbackFacts = PlaybackFacts()
    private var outputDropBaseline: Long? = null
    private var decoderDropBaseline: Long? = null
    private var latestOutputDropCount: Long? = null
    private var latestDecoderDropCount: Long? = null
    private var dropBaselineNanos: Long? = null
    private val diagnosticControllerSequence = nextDiagnosticControllerSequence.incrementAndGet()
    private val diagnosticStartedNanos = System.nanoTime()
    private var dropDiagnosticJob: Job? = null
    private var lastDiagnosticDropCounts: Pair<Long?, Long?>? = null
    private val reportedNativeFailures = mutableSetOf<Pair<AndroidMpvLogCategory, PlaybackNativeFailureReason>>()
    private var lastNativeError: PlaybackError? = null
    private var pendingVideoFailure: PlaybackNativeFailureReason? = null
    private var nativeStartObserved = false
    private val reportedVideoFormats = mutableSetOf<PlaybackNativeVideoFormat>()
    private val lifecycle = AndroidMpvLifecycleKernel()
    private val timing = AndroidMpvTimingController(::enqueueTimingOffset)
    private val trackResolutionDiagnostics = TrackResolutionDiagnosticGate()
    private val seekCoalescer =
        SeekCoalescer(
            scope = scope,
            commit = ::commitSeek,
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
    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    private val droppedFrameChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    private val videoOutputChannel = Channel<com.jellyscope.core.domain.playback.VideoOutputObservation>(Channel.BUFFERED)

    private val engineObserver =
        object : AndroidMpvEngineObserver {
            override fun onEvent(event: AndroidMpvEvent) {
                // Native callbacks must only enqueue.  They never read a
                // property, call a command, or wait for Main.
                val eventGeneration = nativeEventGeneration.get()
                if (event is AndroidMpvEvent.NativeEvent) {
                    when (event.id) {
                        AndroidMpvNativeEventIds.START_FILE -> activateReplacementHotSamples(eventGeneration)
                        AndroidMpvNativeEventIds.END_FILE -> {
                            if (consumeExpectedReplacementEndFile()) return
                            nativeLoadOutstanding.set(false)
                        }
                    }
                }
                if (offerHotNativeEvent(eventGeneration, event)) return
                scope.launch { handleNativeEvent(eventGeneration, event) }
            }
        }

    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = scope,
            platform = com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform.Android,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation -> _playbackState.update { current -> current.copy(audioActivation = activation) } },
            onActivated = {
                initialAudioGate = false
                tryStartIfReady()
                publishDerivedState()
            },
        )

    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = scope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation -> _playbackState.update { current -> current.copy(subtitleActivation = activation) } },
            platform = com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform.Android,
        )

    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> = droppedFrameChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<com.jellyscope.core.domain.playback.VideoOutputObservation> =
        videoOutputChannel.receiveAsFlow()
    override val videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.Unsupported
    override val platformPlayer: Any? = this
    override val activeBackend: PlayerBackend = PlayerBackend.Mpv
    override val timingController: AndroidMpvTimingController = timing
    private val directVideoOutput = enginePolicy.videoOutput == "mediacodec_embed"
    override val appliesSubtitleStyle: Boolean = !directVideoOutput
    override val supportsVideoSizing: Boolean = !directVideoOutput
    override val playbackHealthMeasurementCapabilities = PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames

    init {
        scope.launch { drainHotClockSamples() }
        scope.launch { drainHotDiagnosticSamples() }
        if (initialCaBundlePath != null) {
            try {
                initializeEngine(initialCaBundlePath)
            } catch (throwable: Throwable) {
                releasedFlag.set(true)
                nativeScope.cancel()
                scope.cancel()
                throw throwable
            }
        }
    }

    override fun createSurfaceView(context: Context): View =
        AndroidMpvSurfaceView(
            context = context,
            callbacks =
                object : AndroidMpvSurfaceCallbacks {
                    override fun onSurfaceCreated(
                        owner: AndroidMpvSurfaceView,
                        surface: Surface,
                        width: Int,
                        height: Int,
                    ) {
                        handleSurfaceCreated(owner, surface, width, height)
                    }

                    override fun onSurfaceSizeChanged(
                        owner: AndroidMpvSurfaceView,
                        width: Int,
                        height: Int,
                    ) {
                        handleSurfaceSizeChanged(owner, width, height)
                    }

                    override fun onSurfaceDestroyed(owner: AndroidMpvSurfaceView) {
                        handleSurfaceDestroyed(owner)
                    }
                },
        )

    override fun attachSurface(view: View) {
        val mpvView = view as? AndroidMpvSurfaceView ?: return
        if (surfaceView !== mpvView) {
            val replacingActiveHost = surfaceView != null
            surfaceView = mpvView
            attachedSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
            if (replacingActiveHost) enqueueNativeSurfaceDetach()
        }
        val currentSurface = mpvView.currentSurface()
        if (currentSurface != null && attachedSurface !== currentSurface) {
            handleSurfaceCreated(
                owner = mpvView,
                surface = currentSurface,
                width = mpvView.currentSurfaceWidth(),
                height = mpvView.currentSurfaceHeight(),
            )
            return
        }
        val surface = attachedSurface ?: return
        enqueueNativeSurfaceAttach(surface)
        tryStartIfReady()
    }

    override fun detachSurface() {
        surfaceView = null
        detachActiveSurface()
    }

    override fun detachSurface(view: View) {
        val mpvView = view as? AndroidMpvSurfaceView ?: return
        if (surfaceView !== mpvView) {
            logNativeMilestone(PlaybackNativePlayerMilestone.StaleSurfaceReleaseIgnored)
            return
        }
        surfaceView = null
        detachActiveSurface()
    }

    private fun detachActiveSurface() {
        attachedSurface = null
        tvSurfaceLossGeneration = null
        enqueueNativeSurfaceDetach()
        logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceDetached)
    }

    override fun updatePresentation(presentation: AndroidSurfacePresentation) {
        if (this.presentation == presentation) return
        this.presentation = presentation
        enqueuePresentationUpdate()
    }

    internal fun simulateSurfaceCreatedForTest(
        surface: Surface,
        width: Int = 1_920,
        height: Int = 1_080,
        owner: View? = surfaceView,
    ) {
        val mpvView = owner as? AndroidMpvSurfaceView
        if (mpvView == null) {
            handleActiveSurfaceCreated(surface, width, height)
        } else {
            handleSurfaceCreated(mpvView, surface, width, height)
        }
    }

    internal fun simulateSurfaceDestroyedForTest(owner: View? = surfaceView) {
        val mpvView = owner as? AndroidMpvSurfaceView
        if (mpvView == null) {
            handleActiveSurfaceDestroyed()
        } else {
            handleSurfaceDestroyed(mpvView)
        }
    }

    private fun handleSurfaceCreated(
        owner: AndroidMpvSurfaceView,
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        if (releasedFlag.get()) return
        if (surfaceView !== owner) {
            logNativeMilestone(PlaybackNativePlayerMilestone.StaleSurfaceCallbackIgnored)
            return
        }
        handleActiveSurfaceCreated(surface, width, height)
    }

    private fun handleActiveSurfaceCreated(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        if (releasedFlag.get()) return
        val shouldReloadForTvSurface =
            enginePolicy.deviceClass == AndroidMpvDeviceClass.Television &&
                tvSurfaceLossGeneration == generation &&
                tvSurfaceReloadAttemptedGeneration != generation &&
                lifecycle.state.loaded &&
                lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Active &&
                lastPlan != null
        attachedSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        enqueueNativeSurfaceAttach(surface)
        logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceAttached)
        val reloadGeneration =
            if (shouldReloadForTvSurface) {
                beginTvSurfaceReload()
            } else {
                null
            }
        if (reloadGeneration != null) {
            enqueueNativeSurfaceReload(reloadGeneration)
        }
        tryStartIfReady()
        publishDerivedState()
    }

    private fun handleSurfaceSizeChanged(
        owner: AndroidMpvSurfaceView,
        width: Int,
        height: Int,
    ) {
        if (surfaceView !== owner || width <= 0 || height <= 0) {
            if (surfaceView !== owner) {
                logNativeMilestone(PlaybackNativePlayerMilestone.StaleSurfaceCallbackIgnored)
            }
            return
        }
        if (surfaceWidth == width && surfaceHeight == height) return
        surfaceWidth = width
        surfaceHeight = height
        enqueuePresentationUpdate()
        logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceSizeChanged)
    }

    private fun handleSurfaceDestroyed(owner: AndroidMpvSurfaceView) {
        if (surfaceView !== owner) {
            logNativeMilestone(PlaybackNativePlayerMilestone.StaleSurfaceCallbackIgnored)
            // A logical attach or detach for this surface may still sit in the
            // serialized native queue. The framework disconnects the Surface as soon
            // as this callback returns, and mpv's VO racing that disconnect deadlocks
            // inside the GL driver against the main thread's buffer teardown (Shield
            // ANR). Hold the callback until the queue has drained past any pending
            // surface work — but only while such work exists or the engine still
            // holds a surface; after release() the teardown detaches it, and blocking
            // behind mpv destroy() would stall player close.
            if (!releasedFlag.get() && surfaceWorkPending()) {
                awaitNativeQueueBounded(nativeScope.launch { nativeMutex.withLock {} })
            }
            return
        }
        handleActiveSurfaceDestroyed()
    }

    private fun surfaceWorkPending(): Boolean = outstandingSurfaceOps.get() > 0 || engineAttachedSurface != null

    private fun handleActiveSurfaceDestroyed() {
        if (
            enginePolicy.deviceClass == AndroidMpvDeviceClass.Television &&
            lifecycle.state.loaded &&
            lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Active &&
            lastPlan != null
        ) {
            tvSurfaceLossGeneration = generation
        }
        attachedSurface = null
        if (releasedFlag.get()) return
        // Must complete before surfaceDestroyed returns; see handleSurfaceDestroyed.
        val workPending = surfaceWorkPending()
        val detach = enqueueNativeSurfaceDetach()
        if (workPending) awaitNativeQueueBounded(detach)
        logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceDetached)
    }

    private fun awaitNativeQueueBounded(job: Job) {
        val released = CountDownLatch(1)
        job.invokeOnCompletion { released.countDown() }
        if (!released.await(NATIVE_SURFACE_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceReleaseTimedOut, warning = true)
        }
    }

    /** Starts one bounded TV zero-copy recovery for the surface loss just observed. */
    private fun beginTvSurfaceReload(): Long? {
        if (releasedFlag.get() || enginePolicy.deviceClass != AndroidMpvDeviceClass.Television) return null
        val plan = lastPlan ?: return null
        if (lifecycle.state.outcome != AndroidMpvLifecycleOutcome.Active || !lifecycle.state.loaded) return null

        val outgoingGeneration = generation
        val reloadGeneration = outgoingGeneration + 1L
        generation = reloadGeneration
        awaitReplacementHotSamples(reloadGeneration)
        nativeEventGeneration.set(NO_NATIVE_EVENT_GENERATION)
        tvSurfaceLossGeneration = null
        tvSurfaceReloadAttemptedGeneration = outgoingGeneration
        startupWatchdog?.cancel()
        startupWatchdog = null
        seekCoalescer.cancel()
        clearPendingSeekState()
        lifecycle.prepare(reloadGeneration)
        nativeReady = false
        pendingAudioSelectorId = null
        pendingSubtitleSelectorId = null
        trackDescriptors = emptyList()
        trackListObserved = false
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
        lastNativeError = null
        pendingVideoFailure = null
        nativeStartObserved = false
        reportedVideoFormats.clear()
        playbackFacts = playbackFacts.copy(durationMs = null, paused = true, seeking = false)
        resumeConfirmationFromPositionMs = null
        audioActivationConfirmation.applyInitial(initialAudioActivationFor(plan))
        initialAudioGate = initialAudioActivationFor(plan) is InitialAudioActivation.AwaitNativeMapping
        pendingAudioSelection?.let { selection -> audioActivationConfirmation.begin(selection.target) }
        subtitleActivationConfirmation.beginForPlan(plan, lastSubtitleAsset)
        pendingSubtitleSelection?.let { selection -> subtitleActivationConfirmation.begin(selection.target) }
        publishState(PlaybackStatus.Loading)
        return reloadGeneration
    }

    private fun enqueueNativeSurfaceReload(requestGeneration: Long) {
        val plan = lastPlan ?: return
        nativeScope.launch {
            nativeMutex.withLock {
                if (releasedFlag.get() || requestGeneration != generation) return@withLock
                val native = engine ?: return@withLock
                val request =
                    networkRequest ?: run {
                        postLifecycleFailure(requestGeneration, PlaybackError.Network)
                        return@withLock
                    }
                var replacementEndExpected = false
                val failure =
                    runCatching {
                        native.setPropertyString("http-header-fields", "")
                        native.setPropertyString("http-header-fields", request.authorizationHeader.orEmpty())
                        native.setPropertyBoolean("pause", true)
                        native.setPropertyDouble("speed", _playbackState.value.playbackSpeed.toDouble())
                        applyTimingOffsets(native)
                        applyPresentation(native)
                        attachedSurface?.takeIf(Surface::isValid)?.let { surface ->
                            ensureNativeSurfaceAttached(native, surface, logRetained = true)
                        }
                        replacementEndExpected = markExpectedReplacementEndFile()
                        nativeReady = true
                        nativeEventGeneration.set(requestGeneration)
                        native.command(androidMpvLoadCommand(request.url, playbackFacts.positionMs))
                        logNativeMilestone(
                            milestone = PlaybackNativePlayerMilestone.LoadCommandDispatched,
                            commandShape = PlaybackNativeCommandShape.LoadFileUrlFlagsIndexOptions,
                        )
                    }.exceptionOrNull()
                if (failure != null) {
                    nativeReady = false
                    unwindFailedLoadCommand(replacementEndExpected)
                    nativeEventGeneration.compareAndSet(requestGeneration, NO_NATIVE_EVENT_GENERATION)
                    runCatching { native.setPropertyString("http-header-fields", "") }
                    postLifecycleFailure(requestGeneration, PlaybackError.Unknown)
                    return@withLock
                }
                postMain {
                    if (requestGeneration == generation && !releasedFlag.get()) {
                        armStartupWatchdog(requestGeneration)
                        request.subtitleRejection?.let { rejection ->
                            failRejectedExternalSubtitle(rejection, requestGeneration)
                        }
                    }
                }
            }
        }
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.Prepare)
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            offlinePath = null
            offlineSidecarPath = null
        } else if (offlinePath == null) {
            _playbackState.update { current ->
                current.copy(status = PlaybackStatus.Failed, error = PlaybackError.OfflineArtifactUnavailable)
            }
            return
        }
        startupWatchdog?.cancel()
        startupWatchdog = null
        seekCoalescer.cancel()
        nativeEventGeneration.set(NO_NATIVE_EVENT_GENERATION)
        val replacingCurrentPlan = lastPlan != null
        generation += 1L
        if (replacingCurrentPlan) {
            awaitReplacementHotSamples(generation)
        } else {
            activateHotSamples(generation)
        }
        lifecycle.prepare(generation)
        lastPlan = plan
        lastSubtitleAsset = subtitleAsset
        networkRequest = null
        nativeReady = false
        playIntent = false
        resumeConfirmationFromPositionMs = null
        clearPendingSeekState()
        resumeAfterTransientFocusLoss = false
        pendingAudioSelection = null
        pendingSubtitleSelection = null
        subtitleDisabledExplicitly = false
        pendingAudioSelectorId = null
        pendingSubtitleSelectorId = null
        trackDescriptors = emptyList()
        trackListObserved = false
        externalSubtitleCommandAccepted = false
        externalSubtitleTrackTitle = null
        tvSurfaceLossGeneration = null
        tvSurfaceReloadAttemptedGeneration = null
        playbackFacts = PlaybackFacts(positionMs = plan.startPositionMs.coerceAtLeast(0L))
        outputDropBaseline = null
        decoderDropBaseline = null
        latestOutputDropCount = null
        latestDecoderDropCount = null
        dropBaselineNanos = null
        dropDiagnosticJob?.cancel()
        dropDiagnosticJob = null
        lastDiagnosticDropCounts = null
        reportedNativeFailures.clear()
        lastNativeError = null
        pendingVideoFailure = null
        nativeStartObserved = false
        timing.clearForDiscontinuity()
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = audioFocusCoordinator == null
        isDucked.set(false)
        volumeBeforeDuck.set(null)
        audioActivationConfirmation.applyInitial(initialAudioActivationFor(plan))
        subtitleActivationConfirmation.beginForPlan(plan, subtitleAsset)
        initialAudioGate = initialAudioActivationFor(plan) is InitialAudioActivation.AwaitNativeMapping
        _runtimeDiagnostics.value =
            PlaybackRuntimeDiagnostics.EMPTY.copy(
                prepareEpoch = generation,
                bufferPolicy = PlaybackBufferPolicy.RegularCandidate,
                targetBufferBytes = enginePolicy.maxForwardCacheBytes,
            )
        _playbackState.update { current ->
            current.copy(
                playbackSpeed = plan.playbackSpeed.coerceIn(0.1f, 5f),
                subtitleStyle = plan.subtitleStyle,
                error = null,
                audioUnavailable = false,
            )
        }
        publishState(PlaybackStatus.Loading, positionMs = playbackFacts.positionMs, durationMs = null)
        val requestGeneration = generation
        nativeScope.launch {
            prepareNative(requestGeneration, plan, subtitleAsset)
        }
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (releasedFlag.get()) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.Mpv))
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
            releaseOfflineLeaseAfterNativeStop(offlineLeaseHolder.detach())
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.Mpv))
        }
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.SelectEmbeddedAudio)
        pendingAudioSelection = selection
        audioActivationConfirmation.begin(selection.target)
        applyPendingAudioSelection()
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.SelectEmbeddedSubtitle)
        pendingSubtitleSelection = selection
        pendingSubtitleSelectorId = null
        syncPlanSubtitleTarget(selection?.target)
        if (selection == null) {
            subtitleDisabledExplicitly = true
            externalSubtitleCommandAccepted = false
            externalSubtitleTrackTitle = null
            subtitleActivationConfirmation.clear()
            enqueueNative { native -> native.setPropertyString("sid", "no") }
        } else {
            subtitleDisabledExplicitly = false
            subtitleActivationConfirmation.begin(selection.target)
            applyPendingSubtitleSelection()
        }
    }

    /** Keeps controller-owned reloads aligned with an in-player subtitle switch. */
    private fun syncPlanSubtitleTarget(target: SubtitleActivationTarget?) {
        val currentPlan = lastPlan ?: return
        val keepsExternalAsset = target?.kind == LocalSubtitleKind.ExternalText
        lastPlan =
            currentPlan.copy(
                subtitleActivationTarget = target,
                subtitleAsset = currentPlan.subtitleAsset.takeIf { keepsExternalAsset },
            )
        if (!keepsExternalAsset) {
            lastSubtitleAsset = null
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.SetPlaybackSpeed)
        val normalized = speed.coerceIn(0.1f, 5f)
        _playbackState.update { current -> current.copy(playbackSpeed = normalized) }
        enqueueNative { native -> native.setPropertyDouble("speed", normalized.toDouble()) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.SetSubtitleStyle)
        _playbackState.update { current -> current.copy(subtitleStyle = style) }
        enqueueSubtitleStyle()
    }

    override fun play() {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.Play)
        playIntent = true
        val admitted =
            PlaybackFocusAdmission.admit(
                requestFocus = { audioFocusCoordinator?.request(generation, ::onAudioFocusEvent) ?: true },
                nativeStart = {
                    playbackFocusAdmitted = true
                    tryStartIfReady()
                },
            )
        if (!admitted) {
            playIntent = false
            playbackFocusAdmitted = false
            publishDerivedState()
            return
        }
        publishDerivedState()
    }

    override fun pause() {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.Pause)
        playIntent = false
        playbackFocusAdmitted = false
        resumeConfirmationFromPositionMs = null
        refreshImmediateClockGeneration()
        resumeAfterTransientFocusLoss = false
        seekCoalescer.flush()
        playbackFacts = playbackFacts.copy(paused = true)
        recordNativeSnapshot(PlaybackNativeSampleCause.PauseRequested)
        enqueueNative { native -> native.setPropertyBoolean("pause", true) }
        publishDerivedState()
    }

    override fun seekTo(positionMs: Long) {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.SeekTo)
        val target = positionMs.coerceAtLeast(0L)
        val origin = pendingSeekFromPositionMs ?: playbackFacts.positionMs
        pendingSeekFromPositionMs = origin
        pendingSeekTargetPositionMs = target
        refreshImmediateClockGeneration()
        logSeekDiagnostic(
            event = PlaybackDiagnosticEvent.SeekStarted,
            originPositionMs = origin,
            targetPositionMs = target,
        )
        playbackFacts = playbackFacts.copy(positionMs = target, seeking = true)
        armPendingSeekWatchdog(generation, target)
        publishDerivedState()
        seekCoalescer.request(target)
    }

    private fun commitSeek(target: Long) {
        if (releasedFlag.get()) return
        enqueueNative { native -> native.command(arrayOf("seek", (target / 1_000.0).toString(), "absolute+exact")) }
    }

    override fun stop() {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.Stop)
        playIntent = false
        resumeConfirmationFromPositionMs = null
        startupWatchdog?.cancel()
        startupWatchdog = null
        seekCoalescer.cancel()
        clearPendingSeekState()
        lifecycle.transition(Stop(generation))
        nativeEventGeneration.set(NO_NATIVE_EVENT_GENERATION)
        invalidateHotSamples()
        audioFocusCoordinator?.abandon(generation)
        playbackFocusAdmitted = false
        isDucked.set(false)
        volumeBeforeDuck.set(null)
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        val detachedOfflineLease = offlineLeaseHolder.detach()
        detachedOfflineLease?.let(::queueNativeStopLease)
        enqueueNative(
            action = { native ->
                native.setPropertyString("http-header-fields", "")
                native.command(arrayOf("stop"))
            },
            onComplete = detachedOfflineLease?.let { lease -> { completeNativeStopLease(lease) } },
        )
        publishState(PlaybackStatus.Idle)
        offlinePath = null
        offlineSidecarPath = null
    }

    override fun retry() {
        if (releasedFlag.get()) return warnReleased(PlayerOperation.Retry)
        val plan = lastPlan ?: return
        val shouldPlay = playIntent
        val audioSelection = pendingAudioSelection
        val subtitleSelection = pendingSubtitleSelection
        val subtitleOff = subtitleDisabledExplicitly
        val selectionDecision =
            retrySelectionDecision(
                audioSelection = audioSelection,
                subtitleIntent =
                    when {
                        subtitleOff -> RetrySubtitleIntent.ExplicitOff
                        subtitleSelection != null -> RetrySubtitleIntent.Selection(subtitleSelection)
                        else -> RetrySubtitleIntent.Unspecified
                    },
                planSubtitleTarget = plan.subtitleActivationTarget,
            )
        val timingState = timing.timingState.value
        val position = _playbackState.value.positionMs
        prepare(
            plan.copy(
                startPositionMs = position,
                playbackSpeed = _playbackState.value.playbackSpeed,
                subtitleStyle = _playbackState.value.subtitleStyle,
            ),
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                null
            } else {
                lastSubtitleAsset ?: plan.subtitleAsset
            },
        )
        timing.restoreForRetry(timingState)
        selectionDecision.audioSelection?.let(::selectEmbeddedAudio)
        when (selectionDecision) {
            is RetrySelectionDecision.RestorePlan -> Unit
            is RetrySelectionDecision.ReassertSubtitleOff -> selectEmbeddedSubtitle(null)
            is RetrySelectionDecision.SelectSubtitle -> selectEmbeddedSubtitle(selectionDecision.selection)
        }
        if (shouldPlay) play()
    }

    override fun release() {
        if (!releasedFlag.compareAndSet(false, true)) return
        startupWatchdog?.cancel()
        startupWatchdog = null
        diagnosticLogWatchdog?.cancel()
        diagnosticLogWatchdog = null
        seekCoalescer.cancel()
        lifecycle.transition(Release(generation))
        nativeEventGeneration.set(NO_NATIVE_EVENT_GENERATION)
        invalidateHotSamples()
        audioFocusCoordinator?.abandon(generation)
        playbackFocusAdmitted = false
        isDucked.set(false)
        volumeBeforeDuck.set(null)
        resumeConfirmationFromPositionMs = null
        clearPendingSeekState()
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        val observer = engineObserver
        val detachedOfflineLease = offlineLeaseHolder.detach()
        val pendingNativeLeases =
            synchronized(offlineLeaseLock) {
                pendingNativeStopLeases.toList().also { pendingNativeStopLeases.clear() }
            }
        nativeScope.launch {
            nativeMutex.withLock {
                runCatching { engine?.setPropertyString("http-header-fields", "") }
                engine?.let { native ->
                    runCatching {
                        logRuntimeSurfaceOptionResult(
                            nativeCode = native.setOptionString("force-window", "no"),
                        )
                    }
                    runCatching { native.detachSurface() }
                }
                engineAttachedSurface = null
                runCatching { engine?.removeObserver(observer) }
                runCatching { engine?.destroy() }
                engine = null
                detachedOfflineLease?.release()
                pendingNativeLeases.forEach(OfflineArtifactLease::release)
            }
            nativeScope.cancel()
        }
        offlinePath = null
        offlineSidecarPath = null
        droppedFrameChannel.close()
        videoOutputChannel.close()
        scope.cancel()
    }

    override fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) = Unit

    private suspend fun prepareNative(
        requestGeneration: Long,
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        nativeMutex.withLock {
            if (releasedFlag.get() || requestGeneration != generation) return
            // Explicitly end the previous native item before a new loadfile. The deferred lease
            // is released only after this native stop command has been submitted under the same
            // mutex, never when stop() merely enqueues work from Main.
            val stopSucceeded =
                runCatching {
                    engine?.command(arrayOf("stop"))
                    true
                }.getOrDefault(false)
            if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlineLeaseHolder.detach()?.let { lease ->
                    if (stopSucceeded) {
                        lease.release()
                    } else {
                        // Do not load the replacement while the old engine has not accepted a
                        // stop. Queue the exact lease and retry the stop after this mutex turn;
                        // continuing would let that queued stop terminate the new item instead.
                        releaseOfflineLeaseAfterNativeStop(lease)
                        postLifecycleFailure(requestGeneration, PlaybackError.Unknown)
                        return
                    }
                }
            }
            runCatching {
                engine?.setPropertyBoolean("pause", true)
                engine?.setPropertyString("http-header-fields", "")
            }.onFailure {
                postLifecycleFailure(requestGeneration, PlaybackError.Unknown)
                return
            }
            val trustedPath = offlinePath
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                val path =
                    trustedPath
                        ?: run {
                            postLifecycleFailure(requestGeneration, PlaybackError.OfflineArtifactUnavailable)
                            return
                        }
                val native =
                    runCatching {
                        ensureEngine()
                        engine
                    }.getOrNull()
                if (native == null) {
                    postLifecycleFailure(requestGeneration, PlaybackError.OfflinePlayerUnavailable(PlayerBackend.Mpv))
                    return
                }
                var replacementEndExpected = false
                runCatching {
                    native.setPropertyString("http-header-fields", "")
                    native.setPropertyBoolean("pause", true)
                    native.setPropertyDouble("speed", plan.playbackSpeed.coerceIn(0.1f, 5f).toDouble())
                    applyTimingOffsets(native)
                    applyPresentation(native)
                    applySubtitleStyle(native)
                    attachedSurface?.takeIf(Surface::isValid)?.let { surface ->
                        ensureNativeSurfaceAttached(native, surface, logRetained = true)
                    }
                    replacementEndExpected = markExpectedReplacementEndFile()
                    nativeReady = true
                    nativeEventGeneration.set(requestGeneration)
                    // mpv accepts this resolver-owned absolute path; Java file:/ URIs are not mpv file URLs.
                    native.command(androidMpvLoadCommand(path, plan.startPositionMs))
                    logNativeMilestone(
                        milestone = PlaybackNativePlayerMilestone.LoadCommandDispatched,
                        commandShape = PlaybackNativeCommandShape.LoadFileUrlFlagsIndexOptions,
                    )
                }.onFailure {
                    nativeReady = false
                    unwindFailedLoadCommand(replacementEndExpected)
                    nativeEventGeneration.compareAndSet(requestGeneration, NO_NATIVE_EVENT_GENERATION)
                    runCatching { native.setPropertyString("http-header-fields", "") }
                    postLifecycleFailure(requestGeneration, PlaybackError.OfflinePlayerUnavailable(PlayerBackend.Mpv))
                    return
                }
                postMain {
                    if (requestGeneration == generation && !releasedFlag.get()) {
                        armStartupWatchdog(requestGeneration)
                    }
                }
                return
            }
            val request =
                runCatching { networkPolicy.loadRequest(plan, subtitleAsset) }
                    .getOrElse {
                        postLifecycleFailure(requestGeneration, PlaybackError.Network)
                        return
                    }
            val native =
                runCatching {
                    ensureEngine()
                    engine
                }.getOrNull()
            if (native == null) {
                postLifecycleFailure(requestGeneration, PlaybackError.Decoder)
                return
            }
            if (request is AndroidMpvNetworkResult.Rejected) {
                runCatching { native.setPropertyString("http-header-fields", "") }
                postLifecycleFailure(requestGeneration, PlaybackError.Network)
                return
            }
            val accepted = request as AndroidMpvNetworkResult.Accepted
            networkRequest = accepted.request
            logNativeMilestone(PlaybackNativePlayerMilestone.NetworkRequestAccepted)
            var replacementEndExpected = false
            runCatching {
                native.setPropertyString("http-header-fields", "")
                native.setPropertyString("http-header-fields", accepted.request.authorizationHeader.orEmpty())
                native.setPropertyBoolean("pause", true)
                native.setPropertyDouble("speed", plan.playbackSpeed.coerceIn(0.1f, 5f).toDouble())
                applyTimingOffsets(native)
                applyPresentation(native)
                applySubtitleStyle(native)
                attachedSurface?.takeIf(Surface::isValid)?.let { surface ->
                    ensureNativeSurfaceAttached(native, surface, logRetained = true)
                }
                replacementEndExpected = markExpectedReplacementEndFile()
                nativeReady = true
                nativeEventGeneration.set(requestGeneration)
                native.command(androidMpvLoadCommand(accepted.request.url, plan.startPositionMs))
                logNativeMilestone(
                    milestone = PlaybackNativePlayerMilestone.LoadCommandDispatched,
                    commandShape = PlaybackNativeCommandShape.LoadFileUrlFlagsIndexOptions,
                )
            }.onFailure {
                nativeReady = false
                unwindFailedLoadCommand(replacementEndExpected)
                nativeEventGeneration.compareAndSet(requestGeneration, NO_NATIVE_EVENT_GENERATION)
                runCatching { native.setPropertyString("http-header-fields", "") }
                postLifecycleFailure(requestGeneration, PlaybackError.Unknown)
                return
            }
            postMain {
                if (requestGeneration == generation && !releasedFlag.get()) {
                    armStartupWatchdog(requestGeneration)
                    accepted.request.subtitleRejection?.let { rejection ->
                        failRejectedExternalSubtitle(rejection, requestGeneration)
                    }
                }
            }
        }
    }

    private suspend fun ensureEngine() {
        if (engine != null) return
        val trustBundle = networkPolicy.ensureTrustBundle()
        initializeEngine(trustBundle.absolutePath)
    }

    /**
     * mpv's own verbose log is the primary evidence for user-reported playback
     * defects; the in-process observer seam stays reduced to sanitized
     * categories on purpose. One file per engine instance with the previous
     * one retained, in app-internal storage. The RAW file contains secrets —
     * mpv echoes the Authorization header via `http-header-fields` at verbose
     * level (device-verified) — so it never enters client-log uploads and is
     * deleted when diagnostic collection is disabled.
     */
    private fun prepareMpvDiagnosticLog(): String? {
        if (logCollectionPreferences?.enabled?.value != true) return null
        val directory = androidMpvDiagnosticLogDirectory(appContext) ?: return null
        val current = File(directory, ANDROID_MPV_DIAGNOSTIC_LOG_NAME)
        runCatching {
            if (current.exists()) {
                val previous = File(directory, ANDROID_MPV_DIAGNOSTIC_LOG_PREVIOUS_NAME)
                previous.delete()
                current.renameTo(previous)
            }
        }
        diagnosticLogPath = current.absolutePath
        return diagnosticLogPath
    }

    /** Follows mid-session toggles: off closes and forgets the file, on reopens fresh. */
    private fun observeDiagnosticLogPreference() {
        val store = logCollectionPreferences ?: return
        scope.launch {
            store.enabled.drop(1).collect { enabled ->
                enqueueNative { native ->
                    val result = runCatching { requestDiagnosticLogMessages(native, enabled) }.getOrDefault(-1)
                    if (result < 0) {
                        postMain { logNativeMilestone(PlaybackNativePlayerMilestone.VideoFormatCaptureFailed, warning = true) }
                    }
                }
                if (enabled) {
                    val path =
                        withContext(Dispatchers.IO) { prepareMpvDiagnosticLog() }
                            ?: return@collect
                    enqueueNative { native -> native.setOptionString("log-file", path) }
                    startDiagnosticLogWatchdog()
                } else {
                    diagnosticLogWatchdog?.cancel()
                    diagnosticLogWatchdog = null
                    diagnosticLogPath = null
                    enqueueNative { native -> native.setOptionString("log-file", "") }
                }
            }
        }
    }

    /** Optional format capture must not prevent playback or replace ordinary error reporting. */
    private fun requestDiagnosticLogMessages(
        native: AndroidMpvEngine,
        enabled: Boolean,
    ): Int {
        if (enabled) {
            val result =
                runCatching { native.requestLogMessages(AndroidMpvLogRequestLevel.VideoFormat) }.getOrDefault(-1)
            postMain {
                logNativeMilestone(
                    if (result >= 0) {
                        PlaybackNativePlayerMilestone.VideoFormatCaptureEnabled
                    } else {
                        PlaybackNativePlayerMilestone.VideoFormatCaptureFailed
                    },
                    warning = result < 0,
                )
            }
            if (result >= 0) return result
        }
        val result = native.requestLogMessages(AndroidMpvLogRequestLevel.Error)
        if (!enabled && result >= 0) {
            postMain { logNativeMilestone(PlaybackNativePlayerMilestone.VideoFormatCaptureDisabled) }
        }
        return result
    }

    private fun initializeEngine(caBundlePath: String) {
        if (engine != null) return
        val native =
            try {
                engineFactory.create(appContext)
            } catch (throwable: Throwable) {
                throw AndroidMpvInitializationException(
                    constructionStage = PlaybackBackendConstructionStage.CreateNativeEngine,
                    cause = throwable,
                )
            }
        try {
            val diagnosticLogOptions =
                prepareMpvDiagnosticLog()?.let { path -> mapOf("log-file" to path) }.orEmpty()
            (enginePolicy.options(caBundlePath) + diagnosticLogOptions).forEach { (name, value) ->
                val result =
                    try {
                        native.setOptionString(name, value)
                    } catch (throwable: Throwable) {
                        throw AndroidMpvInitializationException(
                            constructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                            configurationKey = name,
                            cause = throwable,
                        )
                    }
                if (result < 0) {
                    throw AndroidMpvInitializationException(
                        constructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                        configurationKey = name,
                        nativeFailureCode = result,
                    )
                }
            }
            try {
                native.addObserver(engineObserver)
            } catch (throwable: Throwable) {
                throw AndroidMpvInitializationException(
                    constructionStage = PlaybackBackendConstructionStage.RegisterNativeObservers,
                    cause = throwable,
                )
            }
            try {
                observeProperties(native)
            } catch (throwable: Throwable) {
                throw AndroidMpvInitializationException(
                    constructionStage = PlaybackBackendConstructionStage.ObserveNativeProperty,
                    cause = throwable,
                )
            }
            val logRequestResult =
                try {
                    requestDiagnosticLogMessages(native, logCollectionPreferences?.enabled?.value == true)
                } catch (throwable: Throwable) {
                    throw AndroidMpvInitializationException(
                        constructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                        configurationKey = "mpv-log-level",
                        cause = throwable,
                    )
                }
            if (logRequestResult < 0) {
                throw AndroidMpvInitializationException(
                    constructionStage = PlaybackBackendConstructionStage.ApplyNativeOption,
                    configurationKey = "mpv-log-level",
                    nativeFailureCode = logRequestResult,
                )
            }
            try {
                native.initialize()
            } catch (throwable: Throwable) {
                throw AndroidMpvInitializationException(
                    constructionStage = PlaybackBackendConstructionStage.InitializeNativeEngine,
                    cause = throwable,
                )
            }
            engine = native
            startDiagnosticLogWatchdog()
            observeDiagnosticLogPreference()
        } catch (throwable: Throwable) {
            runCatching { native.removeObserver(engineObserver) }
            runCatching { native.destroy() }
            throw throwable
        }
    }

    /**
     * mpv's log-file has no native size cap; a repeating per-frame error can
     * grow it by tens of MB per hour. Once the file crosses the cap, close it
     * (empty log-file option) and leave a final marker in app logs.
     */
    private fun startDiagnosticLogWatchdog() {
        val path = diagnosticLogPath ?: return
        diagnosticLogWatchdog?.cancel()
        diagnosticLogWatchdog =
            scope.launch {
                while (true) {
                    delay(DIAGNOSTIC_LOG_SIZE_POLL_MS)
                    val size =
                        withContext(Dispatchers.IO) {
                            runCatching { File(path).length() }.getOrDefault(0L)
                        }
                    if (size > DIAGNOSTIC_LOG_MAX_BYTES) {
                        enqueueNative { native -> native.setOptionString("log-file", "") }
                        androidMpvControllerLogger.w {
                            "mpv diagnostic log capped at $size bytes; further native logging disabled for this engine"
                        }
                        break
                    }
                }
            }
    }

    private fun observeProperties(native: AndroidMpvEngine) {
        native.observeProperty("track-list", AndroidMpvPropertyFormat.STRING)
        listOf(
            "pause",
            "paused-for-cache",
            "seeking",
            "core-idle",
            "idle-active",
            "eof-reached",
        ).forEach { name -> native.observeProperty(name, AndroidMpvPropertyFormat.FLAG) }
        listOf("time-pos", "duration", "demuxer-cache-time", "cache-speed", "speed", "container-fps").forEach { name ->
            native.observeProperty(name, AndroidMpvPropertyFormat.DOUBLE)
        }
        listOf(
            "aid",
            "sid",
            "width",
            "height",
            "frame-drop-count",
            "decoder-frame-drop-count",
        ).forEach { name -> native.observeProperty(name, AndroidMpvPropertyFormat.INT64) }
        listOf("video-codec", "hwdec-current", "video-format").forEach { name ->
            native.observeProperty(name, AndroidMpvPropertyFormat.STRING)
        }
    }

    private fun offerHotNativeEvent(
        eventGeneration: Long,
        event: AndroidMpvEvent,
    ): Boolean {
        val acceptsOffer =
            !releasedFlag.get() &&
                eventGeneration != NO_NATIVE_EVENT_GENERATION &&
                activeHotGeneration() == eventGeneration
        return when (event) {
            is AndroidMpvEvent.PropertyDouble ->
                when (event.name) {
                    "time-pos" -> {
                        if (acceptsOffer && event.value.isFinite()) {
                            if (
                                mergeHotClockSample(
                                    eventGeneration = eventGeneration,
                                    positionMs = (event.value * 1_000.0).roundToLong().coerceAtLeast(0L),
                                )
                            ) {
                                signalHotClock(eventGeneration, mayCompleteTransition = true)
                            }
                        }
                        true
                    }
                    "demuxer-cache-time" -> {
                        if (acceptsOffer && event.value.isFinite()) {
                            if (
                                mergeHotClockSample(
                                    eventGeneration = eventGeneration,
                                    bufferedPositionMs = (event.value * 1_000.0).roundToLong().coerceAtLeast(0L),
                                )
                            ) {
                                signalHotClock(eventGeneration, mayCompleteTransition = false)
                            }
                        }
                        true
                    }
                    "cache-speed" -> {
                        if (acceptsOffer && event.value.isFinite()) {
                            if (
                                mergeHotDiagnosticSample(eventGeneration) { current ->
                                    current.copy(cacheSpeedBytesPerSecond = event.value)
                                }
                            ) {
                                hotDiagnosticSignal.trySend(Unit)
                            }
                        }
                        true
                    }
                    "container-fps" -> {
                        if (acceptsOffer && event.value.isFinite()) {
                            if (
                                mergeHotDiagnosticSample(eventGeneration) { current ->
                                    current.copy(containerFps = event.value)
                                }
                            ) {
                                hotDiagnosticSignal.trySend(Unit)
                            }
                        }
                        true
                    }
                    else -> false
                }
            is AndroidMpvEvent.PropertyLong ->
                when (event.name) {
                    "frame-drop-count" -> {
                        if (
                            acceptsOffer &&
                            mergeHotDiagnosticSample(eventGeneration) { current ->
                                current.copy(outputDropCount = event.value)
                            }
                        ) {
                            hotDiagnosticSignal.trySend(Unit)
                        }
                        true
                    }
                    "decoder-frame-drop-count" -> {
                        if (
                            acceptsOffer &&
                            mergeHotDiagnosticSample(eventGeneration) { current ->
                                current.copy(decoderDropCount = event.value)
                            }
                        ) {
                            hotDiagnosticSignal.trySend(Unit)
                        }
                        true
                    }
                    else -> false
                }
            else -> false
        }
    }

    private fun signalHotClock(
        eventGeneration: Long,
        mayCompleteTransition: Boolean,
    ) {
        if (mayCompleteTransition && immediateClockGeneration.get() == eventGeneration) {
            immediateClockRequested.set(true)
        }
        hotClockSignal.trySend(Unit)
    }

    private fun signalBufferedAheadDiagnostic(eventGeneration: Long) {
        if (mergeHotDiagnosticSample(eventGeneration) { current -> current.copy(bufferedAheadDirty = true) }) {
            hotDiagnosticSignal.trySend(Unit)
        }
    }

    private fun mergeHotClockSample(
        eventGeneration: Long,
        positionMs: Long? = null,
        bufferedPositionMs: Long? = null,
    ): Boolean {
        while (true) {
            if (activeHotGeneration() != eventGeneration) return false
            val current = hotClockSample.get()
            if (current != null && current.generation > eventGeneration) return false
            val base =
                current?.takeIf { sample -> sample.generation == eventGeneration }
                    ?: AndroidMpvHotClockSample(generation = eventGeneration)
            val updated =
                base.copy(
                    positionMs = positionMs ?: base.positionMs,
                    bufferedPositionMs = bufferedPositionMs ?: base.bufferedPositionMs,
                )
            if (hotClockSample.compareAndSet(current, updated)) {
                if (activeHotGeneration() == eventGeneration) return true
                hotClockSample.compareAndSet(updated, null)
                return false
            }
        }
    }

    private fun mergeHotDiagnosticSample(
        eventGeneration: Long,
        transform: (AndroidMpvHotDiagnosticSample) -> AndroidMpvHotDiagnosticSample,
    ): Boolean {
        while (true) {
            if (activeHotGeneration() != eventGeneration) return false
            val current = hotDiagnosticSample.get()
            if (current != null && current.generation > eventGeneration) return false
            val base =
                current?.takeIf { sample -> sample.generation == eventGeneration }
                    ?: AndroidMpvHotDiagnosticSample(generation = eventGeneration)
            val updated = transform(base)
            if (hotDiagnosticSample.compareAndSet(current, updated)) {
                if (activeHotGeneration() == eventGeneration) return true
                hotDiagnosticSample.compareAndSet(updated, null)
                return false
            }
        }
    }

    private suspend fun drainHotClockSamples() =
        coroutineScope {
            var cooldown: Deferred<Unit>? = null
            var cadenceGeneration = NO_NATIVE_EVENT_GENERATION
            var pendingCadencedSample = false
            while (isActive) {
                val activeCooldown = cooldown
                if (activeCooldown == null) {
                    hotClockSignal.receive()
                    immediateClockRequested.set(false)
                    cadenceGeneration = activeHotGeneration()
                    val published = drainHotClockSample()
                    pendingCadencedSample = false
                    cooldown =
                        if (published) {
                            async { delay(HOT_CLOCK_PUBLICATION_INTERVAL_MS) }
                        } else {
                            null
                        }
                } else {
                    select<Unit> {
                        hotClockSignal.onReceive {
                            val signaledGeneration = activeHotGeneration()
                            if (signaledGeneration != cadenceGeneration) {
                                activeCooldown.cancel()
                                cadenceGeneration = signaledGeneration
                                immediateClockRequested.set(false)
                                val published = drainHotClockSample()
                                pendingCadencedSample = false
                                cooldown =
                                    if (published) {
                                        async { delay(HOT_CLOCK_PUBLICATION_INTERVAL_MS) }
                                    } else {
                                        null
                                    }
                            } else if (immediateClockRequested.compareAndSet(true, false)) {
                                activeCooldown.cancel()
                                val published = drainHotClockSample()
                                pendingCadencedSample = false
                                cooldown =
                                    if (published) {
                                        async { delay(HOT_CLOCK_PUBLICATION_INTERVAL_MS) }
                                    } else {
                                        null
                                    }
                            } else {
                                pendingCadencedSample = true
                            }
                        }
                        activeCooldown.onAwait {
                            cooldown = null
                            if (pendingCadencedSample) {
                                val published = drainHotClockSample()
                                pendingCadencedSample = false
                                cooldown =
                                    if (published) {
                                        async { delay(HOT_CLOCK_PUBLICATION_INTERVAL_MS) }
                                    } else {
                                        null
                                    }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun drainHotDiagnosticSamples() =
        coroutineScope {
            var cooldown: Deferred<Unit>? = null
            var cadenceGeneration = NO_NATIVE_EVENT_GENERATION
            var pendingCadencedSample = false
            while (isActive) {
                val activeCooldown = cooldown
                if (activeCooldown == null) {
                    hotDiagnosticSignal.receive()
                    cadenceGeneration = activeHotGeneration()
                    val published = drainHotDiagnosticSample()
                    pendingCadencedSample = false
                    cooldown =
                        if (published) {
                            async { delay(HOT_DIAGNOSTIC_PUBLICATION_INTERVAL_MS) }
                        } else {
                            null
                        }
                } else {
                    select<Unit> {
                        hotDiagnosticSignal.onReceive {
                            val signaledGeneration = activeHotGeneration()
                            if (signaledGeneration != cadenceGeneration) {
                                activeCooldown.cancel()
                                cadenceGeneration = signaledGeneration
                                val published = drainHotDiagnosticSample()
                                pendingCadencedSample = false
                                cooldown =
                                    if (published) {
                                        async { delay(HOT_DIAGNOSTIC_PUBLICATION_INTERVAL_MS) }
                                    } else {
                                        null
                                    }
                            } else {
                                pendingCadencedSample = true
                            }
                        }
                        activeCooldown.onAwait {
                            cooldown = null
                            if (pendingCadencedSample) {
                                val published = drainHotDiagnosticSample()
                                pendingCadencedSample = false
                                cooldown =
                                    if (published) {
                                        async { delay(HOT_DIAGNOSTIC_PUBLICATION_INTERVAL_MS) }
                                    } else {
                                        null
                                    }
                            }
                        }
                    }
                }
            }
        }

    private fun drainHotClockSample(): Boolean {
        val sample = hotClockSample.getAndSet(null) ?: return false
        if (!acceptsHotSample(sample.generation)) return false
        sample.positionMs?.let(::handleObservedPosition)
        sample.bufferedPositionMs?.let { bufferedPositionMs ->
            playbackFacts = playbackFacts.copy(bufferedPositionMs = bufferedPositionMs)
        }
        // The diagnostic drain reads playbackFacts, so signal it after applying
        // this sample; paused playback may supply no later clock update.
        signalBufferedAheadDiagnostic(sample.generation)
        publishDerivedState()
        return true
    }

    private fun drainHotDiagnosticSample(): Boolean {
        val sample = hotDiagnosticSample.getAndSet(null) ?: return false
        if (!acceptsHotSample(sample.generation)) return false
        val droppedFrames =
            if (sample.outputDropCount != null || sample.decoderDropCount != null) {
                observeDroppedFrames(sample.outputDropCount, sample.decoderDropCount)
            } else {
                null
            }
        updateDiagnostics { current ->
            var next = current
            droppedFrames?.let { dropped ->
                next =
                    next.copy(
                        droppedVideoFrames = dropped.total,
                        outputDroppedVideoFrames = dropped.output,
                        decoderDroppedVideoFrames = dropped.decoder,
                        droppedVideoFramesPerSecond = dropped.ratePerSecond ?: next.droppedVideoFramesPerSecond,
                    )
            }
            sample.cacheSpeedBytesPerSecond?.let { speed ->
                next = next.copy(bandwidthEstimateBps = (speed * 8.0).roundToLong().takeIf { value -> value > 0L })
            }
            sample.containerFps?.let { fps ->
                next = next.copy(videoFrameRate = fps.takeIf { value -> value > 0.0 })
            }
            if (sample.bufferedAheadDirty) {
                next =
                    next.copy(
                        bufferedAheadMs =
                            (playbackFacts.bufferedPositionMs - playbackFacts.positionMs).coerceAtLeast(0L),
                    )
            }
            next
        }
        return true
    }

    private fun acceptsHotSample(sampleGeneration: Long): Boolean =
        !releasedFlag.get() &&
            !lifecycle.state.stopped &&
            sampleGeneration != NO_NATIVE_EVENT_GENERATION &&
            sampleGeneration == activeHotGeneration() &&
            sampleGeneration == generation

    private fun activateHotSamples(sampleGeneration: Long) {
        invalidateHotSamples()
        hotSampleEpoch.set(AndroidMpvHotSampleEpoch.Active(sampleGeneration))
    }

    private fun awaitReplacementHotSamples(sampleGeneration: Long) {
        invalidateHotSamples()
        hotSampleEpoch.set(AndroidMpvHotSampleEpoch.AwaitingStart(sampleGeneration))
    }

    private fun activateReplacementHotSamples(sampleGeneration: Long) {
        while (true) {
            val epoch = hotSampleEpoch.get()
            if (
                epoch !is AndroidMpvHotSampleEpoch.AwaitingStart ||
                epoch.generation != sampleGeneration ||
                expectedReplacementEndFiles.get() > 0
            ) {
                return
            }
            if (
                hotSampleEpoch.compareAndSet(
                    epoch,
                    AndroidMpvHotSampleEpoch.Active(sampleGeneration),
                )
            ) {
                return
            }
        }
    }

    private fun invalidateHotSamples() {
        hotSampleEpoch.set(AndroidMpvHotSampleEpoch.Inactive)
        hotClockSample.set(null)
        hotDiagnosticSample.set(null)
        immediateClockGeneration.set(NO_NATIVE_EVENT_GENERATION)
        immediateClockRequested.set(false)
        while (hotClockSignal.tryReceive().isSuccess) {
            // Drain obsolete wake-ups; an in-flight drain is still generation-checked.
        }
        while (hotDiagnosticSignal.tryReceive().isSuccess) {
            // Drain obsolete wake-ups; an in-flight drain is still generation-checked.
        }
        hotClockSignal.trySend(Unit)
        hotDiagnosticSignal.trySend(Unit)
    }

    private fun activeHotGeneration(): Long =
        (hotSampleEpoch.get() as? AndroidMpvHotSampleEpoch.Active)?.generation
            ?: NO_NATIVE_EVENT_GENERATION

    private suspend fun handleNativeEvent(
        eventGeneration: Long,
        event: AndroidMpvEvent,
    ) {
        if (
            releasedFlag.get() ||
            lifecycle.state.stopped ||
            eventGeneration == NO_NATIVE_EVENT_GENERATION ||
            eventGeneration != generation
        ) {
            return
        }
        when (event) {
            is AndroidMpvEvent.VideoFormat -> {
                if (
                    logCollectionPreferences?.enabled?.value != true ||
                    reportedVideoFormats.size >= 16 ||
                    !reportedVideoFormats.add(event.format)
                ) {
                    return
                }
                androidMpvControllerLogger.i {
                    formatPlaybackDiagnostic(
                        PlaybackDiagnostic(
                            stage = PlaybackDiagnosticStage.NativePlayer,
                            event = PlaybackDiagnosticEvent.NativeVideoFormat,
                            platform = PlaybackDiagnosticPlatform.Android,
                            backend = PlayerBackend.Mpv,
                            prepareSequence = eventGeneration,
                            nativeVideoFormat = event.format,
                            nativeSurfaceWidthPx = surfaceWidth.takeIf { it > 0 },
                            nativeSurfaceHeightPx = surfaceHeight.takeIf { it > 0 },
                        ),
                    )
                }
                return
            }
            is AndroidMpvEvent.PropertyBoolean -> handleBooleanProperty(event.name, event.value)
            is AndroidMpvEvent.PropertyLong -> handleLongProperty(event.name, event.value)
            is AndroidMpvEvent.PropertyDouble -> handleDoubleProperty(event.name, event.value)
            is AndroidMpvEvent.PropertyString -> {
                handleStringProperty(event.name, event.value)
                if (event.name == "track-list") confirmVideoFailure()
                if (event.name in setOf("video-codec", "hwdec-current", "video-format")) {
                    recordNativeSnapshot(PlaybackNativeSampleCause.VideoStateChanged)
                }
            }
            is AndroidMpvEvent.PropertyCleared -> {
                when (event.name) {
                    "hwdec-current" -> updateDiagnostics { it.copy(videoDecodingMode = null, presentationPath = null) }
                    "video-codec" -> updateDiagnostics { it.copy(videoDecoderName = null) }
                    "width" -> updateDiagnostics { it.copy(videoWidth = null) }
                    "height" -> updateDiagnostics { it.copy(videoHeight = null) }
                    else -> return
                }
                recordNativeSnapshot(PlaybackNativeSampleCause.VideoStateChanged)
            }
            is AndroidMpvEvent.NativeEvent -> handleNativeEventId(eventGeneration, event.id)
            is AndroidMpvEvent.SanitizedLog ->
                if (event.severity == AndroidMpvLogSeverity.Error) {
                    lastNativeError = event.category.toPlaybackError()
                    if (event.reason != PlaybackNativeFailureReason.Other) {
                        pendingVideoFailure = event.reason
                        confirmVideoFailure()
                    }
                    if (reportedNativeFailures.add(event.category to event.reason)) {
                        androidMpvControllerLogger.w {
                            formatPlaybackDiagnostic(
                                PlaybackDiagnostic(
                                    stage = PlaybackDiagnosticStage.NativePlayer,
                                    event = PlaybackDiagnosticEvent.ErrorLog,
                                    platform = PlaybackDiagnosticPlatform.Android,
                                    backend = PlayerBackend.Mpv,
                                    prepareSequence = eventGeneration,
                                    nativeControllerSequence = diagnosticControllerSequence,
                                    nativeSampleElapsedMs = (System.nanoTime() - diagnosticStartedNanos) / 1_000_000L,
                                    errorCategory = lastNativeError,
                                    nativeFailureReason = event.reason,
                                ),
                            )
                        }
                        recordNativeSnapshot(PlaybackNativeSampleCause.NativeError)
                    }
                    return
                }
        }
        publishDerivedState()
    }

    private fun handleNativeEventId(
        eventGeneration: Long,
        id: Int,
    ) {
        when (id) {
            AndroidMpvNativeEventIds.START_FILE -> {
                nativeStartObserved = true
                logNativeMilestone(PlaybackNativePlayerMilestone.StartFile)
            }
            AndroidMpvNativeEventIds.FILE_LOADED -> {
                if (!nativeReady) return
                if (lifecycle.state.outcome != AndroidMpvLifecycleOutcome.Active) return
                lastNativeError = null
                applyLifecycle(FileLoaded(eventGeneration))
                logNativeMilestone(PlaybackNativePlayerMilestone.FileLoaded)
                recordNativeSnapshot(PlaybackNativeSampleCause.FileLoaded)
                confirmVideoFailure()
                startupWatchdog?.cancel()
                startupWatchdog = null
                applyPendingAudioSelection()
                applyPendingSubtitleSelection()
                if (subtitleDisabledExplicitly || lastPlan?.subtitleActivationTarget == null) {
                    enqueueNative { native -> native.setPropertyString("sid", "no") }
                }
                attachExternalSubtitleIfNeeded()
                tryStartIfReady()
            }
            AndroidMpvNativeEventIds.END_FILE -> {
                logNativeMilestone(PlaybackNativePlayerMilestone.EndFile, warning = true)
                nativeScope.launch {
                    val eof = nativeMutex.withLock { engine?.getPropertyBoolean("eof-reached") == true }
                    postMain {
                        if (eventGeneration == generation && eof) {
                            applyLifecycle(EofReached(eventGeneration, true))
                        }
                        if (eventGeneration == generation) {
                            if (eof) {
                                applyLifecycle(EndFile(eventGeneration))
                            } else {
                                applyLifecycle(NativeFailure(eventGeneration, lastNativeError ?: PlaybackError.Unknown))
                            }
                        }
                    }
                }
            }
            AndroidMpvNativeEventIds.SHUTDOWN -> {
                logNativeMilestone(PlaybackNativePlayerMilestone.Shutdown, warning = true)
                applyLifecycle(NativeFailure(eventGeneration, lastNativeError ?: PlaybackError.Unknown))
            }
        }
    }

    private fun handleBooleanProperty(
        name: String,
        value: Boolean,
    ) {
        when (name) {
            "pause" -> {
                playbackFacts = playbackFacts.copy(paused = value)
                recordNativeSnapshot(
                    if (value) PlaybackNativeSampleCause.PauseObserved else PlaybackNativeSampleCause.ResumeObserved,
                )
            }
            "paused-for-cache" -> playbackFacts = playbackFacts.copy(pausedForCache = value)
            "seeking" ->
                playbackFacts =
                    playbackFacts.copy(
                        seeking = value || pendingSeekTargetPositionMs != null,
                    )
            "core-idle" -> playbackFacts = playbackFacts.copy(coreIdle = value)
            "idle-active" -> playbackFacts = playbackFacts.copy(idleActive = value)
            "eof-reached" -> applyLifecycle(EofReached(generation, value))
        }
    }

    private fun handleLongProperty(
        name: String,
        value: Long,
    ) {
        when (name) {
            "aid" ->
                if (pendingAudioSelectorId == value) {
                    pendingAudioSelection?.let { selection -> audioActivationConfirmation.confirm(selection.target) }
                }
            "sid" ->
                if (pendingSubtitleSelectorId == value) {
                    pendingSubtitleSelection?.let { selection -> subtitleActivationConfirmation.confirm(selection.target) }
                }
            "width" -> updateDiagnostics { current -> current.copy(videoWidth = value.toInt().takeIf { width -> width > 0 }) }
            "height" -> updateDiagnostics { current -> current.copy(videoHeight = value.toInt().takeIf { height -> height > 0 }) }
        }
    }

    private fun handleDoubleProperty(
        name: String,
        value: Double,
    ) {
        if (!value.isFinite()) return
        when (name) {
            "duration" ->
                playbackFacts =
                    playbackFacts.copy(durationMs = (value * 1_000.0).roundToLong().takeIf { duration -> duration > 0L })
            "speed" -> _playbackState.update { current -> current.copy(playbackSpeed = value.toFloat().coerceIn(0.1f, 5f)) }
        }
    }

    private fun handleObservedPosition(observedPositionMs: Long) {
        val pendingSeekOrigin = pendingSeekFromPositionMs
        val pendingSeekTarget = pendingSeekTargetPositionMs
        val seekConfirmed =
            if (pendingSeekOrigin != null && pendingSeekTarget != null) {
                seekReachedTarget(
                    originPositionMs = pendingSeekOrigin,
                    targetPositionMs = pendingSeekTarget,
                    observedPositionMs = observedPositionMs,
                )
            } else {
                true
            }
        val resumeConfirmed =
            resumeConfirmationFromPositionMs?.let { previous -> observedPositionMs != previous } ?: true
        if (seekConfirmed && pendingSeekOrigin != null && pendingSeekTarget != null) {
            logSeekDiagnostic(
                event = PlaybackDiagnosticEvent.SeekCompleted,
                originPositionMs = pendingSeekOrigin,
                targetPositionMs = pendingSeekTarget,
                observedPositionMs = observedPositionMs,
            )
            clearPendingSeekState()
        }
        if (resumeConfirmed) resumeConfirmationFromPositionMs = null
        refreshImmediateClockGeneration()
        playbackFacts =
            playbackFacts.copy(
                positionMs = observedPositionMs.takeIf { seekConfirmed } ?: playbackFacts.positionMs,
                seeking = !seekConfirmed,
            )
    }

    private fun handleStringProperty(
        name: String,
        value: String,
    ) {
        when (name) {
            "track-list" -> {
                trackListObserved = true
                trackDescriptors = parseTrackList(value)
                applyPendingAudioSelection()
                applyPendingSubtitleSelection()
                confirmExternalSubtitleIfSelected()
            }
            "video-codec" -> updateDiagnostics { current -> current.copy(videoDecoderName = value.takeIf(String::isNotBlank)) }
            "hwdec-current" ->
                updateDiagnostics { current ->
                    current.copy(
                        videoDecodingMode = mpvVideoDecodingMode(value),
                        presentationPath = value.takeIf(String::isNotBlank),
                    )
                }
        }
    }

    private fun parseTrackList(value: String): List<MpvTrackDescriptor> =
        runCatching {
            val array = org.json.JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        MpvTrackDescriptor(
                            selectorId = item.optLong("id", -1L),
                            type = item.optString("type").takeIf(String::isNotBlank),
                            external = item.optBoolean("external", false),
                            ffIndex = item.optInt("ff-index", -1).takeIf { candidate -> candidate >= 0 },
                            codec = item.optString("codec").takeIf(String::isNotBlank),
                            language = item.optString("lang").takeIf(String::isNotBlank),
                            label = item.optString("title").takeIf(String::isNotBlank),
                            selected = item.optBoolean("selected", false),
                        ),
                    )
                }
            }.filter { track -> track.selectorId >= 0L }
        }.getOrDefault(emptyList())

    private fun applyPendingAudioSelection() {
        if (!lifecycle.state.loaded) return
        val selection = pendingAudioSelection ?: return
        val candidateCount =
            trackDescriptors.count { descriptor ->
                !descriptor.external &&
                    descriptor.type.equals(MPV_TRACK_TYPE_AUDIO, true)
            }
        val resolution = resolveMpvTrackDescriptor(selection.descriptor, trackDescriptors, MPV_TRACK_TYPE_AUDIO)
        logMpvTrackResolution(
            trackKind = PlaybackDiagnosticTrackKind.Audio,
            operation = PlayerOperation.SelectEmbeddedAudio,
            target = selection.target,
            candidateCount = candidateCount,
            result = resolution.result,
            reason = resolution.reason,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val candidate = resolution.candidate ?: return
                pendingAudioSelectorId = candidate.selectorId
                if (candidate.selected) {
                    logMpvTrackResolution(
                        trackKind = PlaybackDiagnosticTrackKind.Audio,
                        operation = PlayerOperation.SelectEmbeddedAudio,
                        target = selection.target,
                        candidateCount = candidateCount,
                        result = NativeTrackMappingResult.Active,
                        reason = NativeTrackMappingReason.AlreadySelected,
                    )
                    audioActivationConfirmation.confirm(selection.target)
                    return
                }
                enqueueNative { native -> native.setPropertyLong("aid", candidate.selectorId) }
                audioActivationConfirmation.armTimeout(selection.target, candidateCount)
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> audioActivationConfirmation.fail(selection.target, resolution.result, candidateCount)
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> if (trackListObserved) audioActivationConfirmation.armTimeout(selection.target, candidateCount)
        }
    }

    private fun applyPendingSubtitleSelection() {
        if (!lifecycle.state.loaded) return
        val selection = pendingSubtitleSelection ?: return
        if (rejectDirectOutputSubtitle(selection.target)) return
        val candidateCount =
            trackDescriptors.count { descriptor ->
                !descriptor.external &&
                    descriptor.type.equals(MPV_TRACK_TYPE_SUBTITLE, true)
            }
        val resolution = resolveMpvTrackDescriptor(selection.descriptor, trackDescriptors, MPV_TRACK_TYPE_SUBTITLE)
        logMpvTrackResolution(
            trackKind = PlaybackDiagnosticTrackKind.Subtitle,
            operation = PlayerOperation.SelectEmbeddedSubtitle,
            target = selection.target,
            candidateCount = candidateCount,
            result = resolution.result,
            reason = resolution.reason,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val candidate = resolution.candidate ?: return
                pendingSubtitleSelectorId = candidate.selectorId
                if (candidate.selected) {
                    logMpvTrackResolution(
                        trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                        operation = PlayerOperation.SelectEmbeddedSubtitle,
                        target = selection.target,
                        candidateCount = candidateCount,
                        result = NativeTrackMappingResult.Active,
                        reason = NativeTrackMappingReason.AlreadySelected,
                    )
                    subtitleActivationConfirmation.confirm(selection.target)
                    return
                }
                enqueueNative { native -> native.setPropertyLong("sid", candidate.selectorId) }
                subtitleActivationConfirmation.armTimeout(selection.target, candidateCount)
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            ->
                subtitleActivationConfirmation.fail(
                    target = selection.target,
                    result = resolution.result,
                    candidateCount = candidateCount,
                )
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> if (trackListObserved) subtitleActivationConfirmation.armTimeout(selection.target, candidateCount)
        }
    }

    private fun logMpvTrackResolution(
        trackKind: PlaybackDiagnosticTrackKind,
        operation: PlayerOperation,
        target: Any,
        candidateCount: Int,
        result: NativeTrackMappingResult,
        reason: NativeTrackMappingReason,
    ) {
        if (
            !trackResolutionDiagnostics.admit(
                kind = trackKind,
                target = generation to target,
                candidateCount = candidateCount,
                result = result,
                reason = reason,
            )
        ) {
            return
        }
        val diagnostic =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event =
                        when (result) {
                            NativeTrackMappingResult.Active -> PlaybackDiagnosticEvent.Resolved
                            NativeTrackMappingResult.Timeout -> PlaybackDiagnosticEvent.Timeout
                            else -> PlaybackDiagnosticEvent.Failed
                        },
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = PlayerBackend.Mpv,
                    prepareSequence = generation.takeIf { value -> value > 0L },
                    trackKind = trackKind,
                    candidateCount = candidateCount,
                    mappingResult = result,
                    mappingReason = reason,
                    operation = operation,
                ),
            )
        if (result == NativeTrackMappingResult.Active) {
            androidMpvControllerLogger.i { diagnostic }
        } else {
            androidMpvControllerLogger.w { diagnostic }
        }
    }

    private fun logSeekDiagnostic(
        event: PlaybackDiagnosticEvent,
        originPositionMs: Long,
        targetPositionMs: Long,
        observedPositionMs: Long? = null,
    ) {
        recordNativeSnapshot(
            if (event == PlaybackDiagnosticEvent.SeekStarted) {
                PlaybackNativeSampleCause.SeekRequested
            } else {
                PlaybackNativeSampleCause.SeekCompleted
            },
        )
        androidMpvControllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = event,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = PlayerBackend.Mpv,
                    prepareSequence = generation.takeIf { value -> value > 0L },
                    operation = PlayerOperation.SeekTo,
                    seekOriginPositionMs = originPositionMs,
                    seekTargetPositionMs = targetPositionMs,
                    seekObservedPositionMs = observedPositionMs,
                ),
            )
        }
    }

    private fun seekReachedTarget(
        originPositionMs: Long,
        targetPositionMs: Long,
        observedPositionMs: Long,
    ): Boolean =
        when {
            targetPositionMs > originPositionMs ->
                observedPositionMs >= (targetPositionMs - SEEK_CONFIRMATION_TOLERANCE_MS).coerceAtLeast(0L)
            targetPositionMs < originPositionMs ->
                observedPositionMs <= seekUpperBound(targetPositionMs)
            else -> {
                val lowerBound = (targetPositionMs - SEEK_CONFIRMATION_TOLERANCE_MS).coerceAtLeast(0L)
                observedPositionMs in lowerBound..seekUpperBound(targetPositionMs)
            }
        }

    private fun seekUpperBound(positionMs: Long): Long =
        if (positionMs > Long.MAX_VALUE - SEEK_CONFIRMATION_TOLERANCE_MS) {
            Long.MAX_VALUE
        } else {
            positionMs + SEEK_CONFIRMATION_TOLERANCE_MS
        }

    private fun clearPendingSeekState() {
        pendingSeekWatchdog?.cancel()
        pendingSeekWatchdog = null
        pendingSeekFromPositionMs = null
        pendingSeekTargetPositionMs = null
        refreshImmediateClockGeneration()
    }

    private fun refreshImmediateClockGeneration() {
        immediateClockGeneration.set(
            if (pendingSeekTargetPositionMs != null || resumeConfirmationFromPositionMs != null) {
                generation
            } else {
                NO_NATIVE_EVENT_GENERATION
            },
        )
    }

    /**
     * Confirmation is tolerance-based, so a seek mpv refuses or clamps (an
     * unseekable or cache-bounded stream) would otherwise pin `seeking` and a
     * frozen position for the rest of the session. Abandon confirmation after
     * a bounded wait and let the next `time-pos` tick republish observed truth.
     */
    private fun armPendingSeekWatchdog(
        requestGeneration: Long,
        targetPositionMs: Long,
    ) {
        pendingSeekWatchdog?.cancel()
        pendingSeekWatchdog =
            scope.launch {
                kotlinx.coroutines.delay(seekConfirmationTimeout)
                if (generation != requestGeneration || pendingSeekTargetPositionMs != targetPositionMs) return@launch
                logSeekDiagnostic(
                    event = PlaybackDiagnosticEvent.Timeout,
                    originPositionMs = pendingSeekFromPositionMs ?: playbackFacts.positionMs,
                    targetPositionMs = targetPositionMs,
                    observedPositionMs = playbackFacts.positionMs,
                )
                playbackFacts = playbackFacts.copy(seeking = false)
                publishDerivedState()
                // Last statement: cancels this running job, so nothing may follow.
                clearPendingSeekState()
            }
    }

    private fun rejectDirectOutputSubtitle(target: SubtitleActivationTarget): Boolean {
        if (!directVideoOutput) return false
        if (_playbackState.value.subtitleActivation != SubtitleActivationState.Unavailable(target)) {
            subtitleActivationConfirmation.fail(
                target = target,
                reason = SubtitleActivationFailureReason.MpvDirectOutputUnavailable,
            )
        }
        return true
    }

    private fun attachExternalSubtitleIfNeeded() {
        if (subtitleDisabledExplicitly) return
        val plan = lastPlan ?: return
        val target = plan.subtitleActivationTarget ?: return
        if (target.kind != LocalSubtitleKind.ExternalText) return
        if (rejectDirectOutputSubtitle(target)) return
        val resource =
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlineSidecarPath ?: return
            } else {
                val request = networkRequest ?: return
                request.subtitleUrl ?: request.localSubtitlePath ?: return
            }
        val title = "jellyscope-external-${target.requestId}-$generation"
        val requestGeneration = generation
        externalSubtitleTrackTitle = title
        externalSubtitleCommandAccepted = false
        enqueueNative { native ->
            try {
                native.command(arrayOf("sub-add", resource, "select", title))
                postMain {
                    if (
                        requestGeneration == generation &&
                        !releasedFlag.get() &&
                        externalSubtitleTrackTitle == title
                    ) {
                        externalSubtitleCommandAccepted = true
                        subtitleActivationConfirmation.armTimeout(target)
                    }
                }
            } catch (_: Throwable) {
                postMain {
                    if (requestGeneration == generation && !releasedFlag.get()) {
                        subtitleActivationConfirmation.fail(
                            target = target,
                            reason = SubtitleActivationFailureReason.SubtitleAttachRejected,
                        )
                    }
                }
            }
        }
    }

    private fun tryStartIfReady() {
        if (!playIntent || !playbackFocusAdmitted || !nativeReady || !lifecycle.state.loaded) return
        if (lifecycle.state.outcome != AndroidMpvLifecycleOutcome.Active) return
        if (attachedSurface?.isValid != true) return
        if (initialAudioGate) return
        if (pendingAudioSelection != null && _playbackState.value.audioActivation is AudioActivationState.Pending) return
        if (!playbackFacts.paused && resumeConfirmationFromPositionMs == null) return
        if (resumeConfirmationFromPositionMs != null) return
        resumeConfirmationFromPositionMs = playbackFacts.positionMs
        refreshImmediateClockGeneration()
        recordNativeSnapshot(PlaybackNativeSampleCause.ResumeRequested)
        enqueueNative { native -> native.setPropertyBoolean("pause", false) }
        logNativeMilestone(PlaybackNativePlayerMilestone.PlayUnpauseDispatched)
    }

    private fun onAudioFocusEvent(
        eventGeneration: Long,
        event: AndroidAudioFocusEvent,
    ) {
        if (releasedFlag.get() || eventGeneration != generation) return
        when (event) {
            AndroidAudioFocusEvent.Gained -> {
                if (isDucked.compareAndSet(true, false)) {
                    volumeBeforeDuck.getAndSet(null)?.let { volume ->
                        enqueueNative { native -> native.setPropertyDouble("volume", volume) }
                    }
                }
                if (resumeAfterTransientFocusLoss && playIntent) {
                    resumeAfterTransientFocusLoss = false
                    resumeConfirmationFromPositionMs = playbackFacts.positionMs
                    refreshImmediateClockGeneration()
                    enqueueNative { native -> native.setPropertyBoolean("pause", false) }
                }
            }
            AndroidAudioFocusEvent.Duck ->
                enqueueNative { native ->
                    if (isDucked.compareAndSet(false, true)) {
                        val volume = native.getPropertyDouble("volume") ?: 100.0
                        volumeBeforeDuck.set(volume)
                        native.setPropertyDouble("volume", volume * 0.2)
                    }
                }
            AndroidAudioFocusEvent.TransientLoss -> {
                resumeAfterTransientFocusLoss = playIntent
                resumeConfirmationFromPositionMs = null
                refreshImmediateClockGeneration()
                enqueueNative { native -> native.setPropertyBoolean("pause", true) }
            }
            AndroidAudioFocusEvent.PermanentLoss,
            AndroidAudioFocusEvent.BecomingNoisy,
            -> {
                resumeAfterTransientFocusLoss = false
                playIntent = false
                resumeConfirmationFromPositionMs = null
                refreshImmediateClockGeneration()
                volumeBeforeDuck.getAndSet(null)?.let { volume ->
                    enqueueNative { native -> native.setPropertyDouble("volume", volume) }
                }
                isDucked.set(false)
                enqueueNative { native -> native.setPropertyBoolean("pause", true) }
                playbackFocusAdmitted = false
                audioFocusCoordinator?.abandon(eventGeneration)
            }
        }
    }

    private fun confirmVideoFailure() {
        val reason = pendingVideoFailure ?: return
        if (lastPlan?.videoExpected != true ||
            !lifecycle.state.loaded ||
            lifecycle.state.outcome != AndroidMpvLifecycleOutcome.Active ||
            releasedFlag.get()
        ) {
            return
        }
        val failureGeneration = generation
        enqueueNative { native ->
            if (nativeEventGeneration.get() != failureGeneration) return@enqueueNative
            // An init error alone can precede a successful decoder fallback. Only
            // mpv explicitly disabling video in a live file makes this terminal.
            if (native.getPropertyString("vid") != "no" ||
                native.getPropertyBoolean("eof-reached") != false ||
                native.getPropertyBoolean("idle-active") != false
            ) {
                return@enqueueNative
            }
            native.setPropertyBoolean("pause", true)
            postMain {
                if (failureGeneration != generation ||
                    lifecycle.state.outcome != AndroidMpvLifecycleOutcome.Active ||
                    releasedFlag.get()
                ) {
                    return@postMain
                }
                androidMpvControllerLogger.w {
                    formatPlaybackDiagnostic(
                        PlaybackDiagnostic(
                            stage = PlaybackDiagnosticStage.NativePlayer,
                            event = PlaybackDiagnosticEvent.Failed,
                            platform = PlaybackDiagnosticPlatform.Android,
                            backend = PlayerBackend.Mpv,
                            prepareSequence = failureGeneration,
                            nativeControllerSequence = diagnosticControllerSequence,
                            nativeSampleElapsedMs = (System.nanoTime() - diagnosticStartedNanos) / 1_000_000L,
                            nativeFailureReason = reason,
                            nativeVideoTrackSelected = false,
                            errorCategory = PlaybackError.UnsupportedMedia,
                        ),
                    )
                }
                playIntent = false
                resumeConfirmationFromPositionMs = null
                resumeAfterTransientFocusLoss = false
                playbackFocusAdmitted = false
                audioFocusCoordinator?.abandon(failureGeneration)
                seekCoalescer.cancel()
                clearPendingSeekState()
                enqueueNative { currentNative ->
                    if (nativeEventGeneration.get() == failureGeneration) {
                        currentNative.command(arrayOf("stop"))
                    }
                }
                applyLifecycle(NativeFailure(failureGeneration, PlaybackError.UnsupportedMedia))
            }
        }
    }

    private fun applyLifecycle(event: com.jellyscope.core.playback.AndroidMpvLifecycleEvent) {
        val transition = lifecycle.transition(event)
        when (val decision = transition.decision) {
            Completed -> publishState(PlaybackStatus.Completed, positionMs = playbackFacts.durationMs ?: playbackFacts.positionMs)
            is Failed -> {
                androidMpvControllerLogger.w {
                    formatPlaybackDiagnostic(
                        PlaybackDiagnostic(
                            stage = PlaybackDiagnosticStage.NativePlayer,
                            event = PlaybackDiagnosticEvent.TerminalError,
                            platform = PlaybackDiagnosticPlatform.Android,
                            backend = activeBackend,
                            prepareSequence = generation,
                            sessionSequence = lastPlan?.diagnosticSessionSequence,
                            nativeControllerSequence = diagnosticControllerSequence,
                            nativeFailureReason = pendingVideoFailure,
                            errorCategory = decision.error,
                        ),
                    )
                }
                startupWatchdog?.cancel()
                startupWatchdog = null
                if (lastPlan?.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                    releaseOfflineLeaseAfterNativeStop(offlineLeaseHolder.detach())
                    offlinePath = null
                    offlineSidecarPath = null
                }
                publishState(PlaybackStatus.Failed, error = decision.error)
            }
            Ready -> publishDerivedState()
            else -> Unit
        }
    }

    private fun armStartupWatchdog(requestGeneration: Long) {
        startupWatchdog?.cancel()
        startupWatchdog =
            scope.launch {
                kotlinx.coroutines.delay(startupTimeout)
                if (generation == requestGeneration && lifecycle.state.loaded.not()) {
                    logNativeMilestone(PlaybackNativePlayerMilestone.StartupTimeout, warning = true)
                    applyLifecycle(StartupTimeout(requestGeneration, lastNativeError ?: PlaybackError.Unknown))
                }
            }
    }

    private fun failRejectedExternalSubtitle(
        rejection: AndroidMpvNetworkRejection,
        requestGeneration: Long,
    ) {
        if (requestGeneration != generation) return
        val target =
            lastPlan
                ?.subtitleActivationTarget
                ?.takeIf { candidate -> candidate.kind == LocalSubtitleKind.ExternalText }
                ?: return
        subtitleActivationConfirmation.fail(
            target = target,
            reason =
                when (rejection) {
                    AndroidMpvNetworkRejection.CrossOrigin -> SubtitleActivationFailureReason.ResourceOriginRejected
                    AndroidMpvNetworkRejection.MissingLocalSubtitle -> SubtitleActivationFailureReason.MissingExternalResource
                    else -> SubtitleActivationFailureReason.SubtitleAttachRejected
                },
        )
    }

    private fun confirmExternalSubtitleIfSelected() {
        val target =
            lastPlan
                ?.subtitleActivationTarget
                ?.takeIf { candidate -> candidate.kind == LocalSubtitleKind.ExternalText }
                ?: return
        if (!externalSubtitleCommandAccepted || _playbackState.value.subtitleActivation != SubtitleActivationState.Pending(target)) return
        val expectedTitle = externalSubtitleTrackTitle ?: return
        if (
            trackDescriptors.any { descriptor ->
                descriptor.external && descriptor.selected && descriptor.label == expectedTitle
            }
        ) {
            subtitleActivationConfirmation.confirm(target)
        }
    }

    private fun postLifecycleFailure(
        eventGeneration: Long,
        error: PlaybackError,
    ) = postMain { if (eventGeneration == generation) applyLifecycle(NativeFailure(eventGeneration, error)) }

    private fun logNativeMilestone(
        milestone: PlaybackNativePlayerMilestone,
        commandShape: PlaybackNativeCommandShape? = null,
        warning: Boolean = false,
    ) {
        val diagnostic =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.NativeLifecycle,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = PlayerBackend.Mpv,
                    prepareSequence = generation.takeIf { value -> value > 0L },
                    nativePlayerMilestone = milestone,
                    nativeCommandShape = commandShape,
                    nativeReady = nativeReady,
                    nativeLoadOutstanding = nativeLoadOutstanding.get(),
                    nativeStartObserved = nativeStartObserved,
                    nativeFileLoaded = lifecycle.state.loaded,
                    nativeSurfaceAttached = attachedSurface?.isValid == true,
                    nativeSurfaceWidthPx = surfaceWidth.takeIf { value -> value > 0 },
                    nativeSurfaceHeightPx = surfaceHeight.takeIf { value -> value > 0 },
                    nativePlayIntent = playIntent,
                    nativeAudioGatePending = initialAudioGate,
                ),
            )
        if (warning) {
            androidMpvControllerLogger.w { diagnostic }
        } else {
            androidMpvControllerLogger.i { diagnostic }
        }
    }

    private fun recordNativeSnapshot(cause: PlaybackNativeSampleCause) {
        if (releasedFlag.get() || lifecycle.state.stopped || logCollectionPreferences?.enabled?.value != true) return
        val sampleGeneration = generation
        if (sampleGeneration == NO_NATIVE_EVENT_GENERATION) return
        val context =
            PlaybackDiagnostic(
                stage = PlaybackDiagnosticStage.NativePlayer,
                event = PlaybackDiagnosticEvent.NativeSnapshot,
                platform = PlaybackDiagnosticPlatform.Android,
                backend = PlayerBackend.Mpv,
                prepareSequence = sampleGeneration,
                nativeControllerSequence = diagnosticControllerSequence,
                nativeSampleCause = cause,
                nativeRequestedVideoOutput = diagnosticVideoOutput(enginePolicy.videoOutput),
                nativePlayIntent = playIntent,
                nativeFileLoaded = lifecycle.state.loaded,
                streamMode = lastPlan?.streamMode,
                firstVideoOutputAvailable = false,
                firstVideoOutputEvidence = VideoOutputEvidence.Unsupported,
            )
        enqueueNative { native ->
            if (nativeEventGeneration.get() != sampleGeneration) return@enqueueNative
            val sampled =
                runCatching {
                    val width = native.getPropertyLong("width")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                    val height = native.getPropertyLong("height")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                    val decoder = native.getPropertyString("video-codec")?.takeIf(String::isNotBlank)
                    val position = native.getPropertyDouble("time-pos")?.takeIf { it.isFinite() && it >= 0.0 }
                    val cachedUntil = native.getPropertyDouble("demuxer-cache-time")?.takeIf { it.isFinite() && it >= 0.0 }
                    context.copy(
                        nativeSampleElapsedMs = (System.nanoTime() - diagnosticStartedNanos) / 1_000_000L,
                        nativeActiveVideoOutput = diagnosticVideoOutput(native.getPropertyString("current-vo")),
                        nativeActiveAudioOutput =
                            when (native.getPropertyString("current-ao")) {
                                "audiotrack" -> PlaybackNativeAudioOutput.AudioTrack
                                "aaudio" -> PlaybackNativeAudioOutput.AAudio
                                "opensles" -> PlaybackNativeAudioOutput.OpenSles
                                null, "" -> PlaybackNativeAudioOutput.Unavailable
                                else -> PlaybackNativeAudioOutput.Other
                            },
                        nativeAvSyncMs =
                            native.getPropertyDouble("avsync")?.takeIf(Double::isFinite)?.let { (it * 1_000.0).roundToLong() },
                        nativeTotalAvSyncChangeMs =
                            native
                                .getPropertyDouble("total-avsync-change")
                                ?.takeIf(Double::isFinite)
                                ?.let { (it * 1_000.0).roundToLong() },
                        nativePaused = native.getPropertyBoolean("pause"),
                        nativePositionMs = position?.let { (it * 1_000.0).roundToLong() },
                        nativeVideoTrackSelected =
                            when (val track = native.getPropertyString("vid")) {
                                "no" -> false
                                else -> track?.toLongOrNull()?.let { it > 0L }
                            },
                        nativeDecoderAvailable = decoder != null,
                        nativeVideoFormatAvailable = width != null && height != null,
                        videoDecoderName = decoder,
                        videoDecodingMode = mpvVideoDecodingMode(native.getPropertyString("hwdec-current")),
                        codec = native.getPropertyString("video-format"),
                        runtimeVideoWidth = width,
                        runtimeVideoHeight = height,
                        outputDroppedVideoFrames = native.getPropertyLong("frame-drop-count"),
                        decoderDroppedVideoFrames = native.getPropertyLong("decoder-frame-drop-count"),
                        bufferedAheadMs =
                            if (position != null && cachedUntil != null) {
                                ((cachedUntil - position).coerceAtLeast(0.0) * 1_000.0).roundToLong()
                            } else {
                                null
                            },
                    )
                }.getOrElse { context.copy(exceptionType = it.playbackExceptionType()) }
            postMain {
                if (!releasedFlag.get() &&
                    sampleGeneration == generation &&
                    !lifecycle.state.stopped &&
                    logCollectionPreferences?.enabled?.value == true
                ) {
                    androidMpvControllerLogger.i { formatPlaybackDiagnostic(sampled) }
                }
            }
        }
    }

    /** Keeps the long-standing trailing-lambda call shape source-compatible. */
    private fun enqueueNative(action: (AndroidMpvEngine) -> Unit): Job = enqueueNative(onComplete = null, action = action)

    private fun enqueueNative(
        onComplete: (() -> Unit)?,
        action: (AndroidMpvEngine) -> Unit,
    ): Job =
        nativeScope.launch {
            try {
                nativeMutex.withLock {
                    if (!releasedFlag.get()) engine?.let(action)
                }
            } finally {
                onComplete?.invoke()
            }
        }

    private fun releaseOfflineLeaseAfterNativeStop(lease: OfflineArtifactLease?) {
        if (lease == null) return
        queueNativeStopLease(lease)
        enqueueNative(
            action = { native -> native.command(arrayOf("stop")) },
            onComplete = { completeNativeStopLease(lease) },
        )
    }

    private fun queueNativeStopLease(lease: OfflineArtifactLease) {
        synchronized(offlineLeaseLock) {
            pendingNativeStopLeases += lease
        }
    }

    private fun completeNativeStopLease(lease: OfflineArtifactLease) {
        synchronized(offlineLeaseLock) {
            if (releasedFlag.get()) return
            pendingNativeStopLeases.remove(lease)
            lease.release()
        }
    }

    private fun enqueueNativeSurfaceAttach(surface: Surface): Job =
        trackSurfaceOp(
            enqueueNative { native ->
                ensureNativeSurfaceAttached(native, surface)
            },
        )

    /**
     * Every native surface attach/detach enqueue must pass through here so the
     * destroy barrier's [outstandingSurfaceOps] predicate stays complete
     * (enqueueNativeSurfaceReload's re-attach is exempt only because a tracked
     * attach always precedes it).
     */
    private fun trackSurfaceOp(job: Job): Job {
        outstandingSurfaceOps.incrementAndGet()
        job.invokeOnCompletion { outstandingSurfaceOps.decrementAndGet() }
        return job
    }

    private fun ensureNativeSurfaceAttached(
        native: AndroidMpvEngine,
        surface: Surface,
        logRetained: Boolean = false,
    ) {
        if (engineAttachedSurface === surface) {
            if (logRetained) {
                logNativeMilestone(PlaybackNativePlayerMilestone.SurfaceAlreadyAttached)
            }
            return
        }
        attachNativeSurface(native, surface)
    }

    private fun attachNativeSurface(
        native: AndroidMpvEngine,
        surface: Surface,
    ) {
        native.attachSurface(surface)
        logRuntimeSurfaceOptionResult(
            nativeCode = native.setOptionString("force-window", "yes"),
        )
        engineAttachedSurface = surface
        applyPresentation(native)
    }

    private fun markExpectedReplacementEndFile(): Boolean =
        nativeLoadOutstanding.getAndSet(true).also { expected ->
            if (expected) expectedReplacementEndFiles.incrementAndGet()
        }

    private fun unwindFailedLoadCommand(replacementEndExpected: Boolean) {
        if (replacementEndExpected) {
            consumeExpectedReplacementEndFile()
        } else {
            nativeLoadOutstanding.set(false)
        }
    }

    private fun consumeExpectedReplacementEndFile(): Boolean {
        while (true) {
            val current = expectedReplacementEndFiles.get()
            if (current <= 0) return false
            if (expectedReplacementEndFiles.compareAndSet(current, current - 1)) return true
        }
    }

    private fun enqueueNativeSurfaceDetach(): Job =
        trackSurfaceOp(
            enqueueNative { native ->
                if (engineAttachedSurface != null) {
                    logRuntimeSurfaceOptionResult(
                        nativeCode = native.setOptionString("force-window", "no"),
                    )
                    native.detachSurface()
                    engineAttachedSurface = null
                }
            },
        )

    private fun logRuntimeSurfaceOptionResult(nativeCode: Int) {
        if (nativeCode < 0) {
            androidMpvControllerLogger.w {
                androidMpvSurfaceOptionFailureDiagnostic(
                    prepareSequence = generation.takeIf { value -> value > 0L },
                    nativeCode = nativeCode,
                )
            }
        }
    }

    private fun enqueuePresentationUpdate() = enqueueNative { native -> applyPresentation(native) }

    private fun enqueueSubtitleStyle() = enqueueNative { native -> applySubtitleStyle(native) }

    private fun enqueueTimingOffset(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ) = enqueueNative { native ->
        native.setPropertyDouble(
            when (kind) {
                PlaybackTimingKind.Audio -> "audio-delay"
                PlaybackTimingKind.Subtitle -> "sub-delay"
            },
            offsetMs / 1_000.0,
        )
    }

    private fun applyTimingOffsets(native: AndroidMpvEngine) {
        timing.timingState.value.let { state ->
            native.setPropertyDouble("audio-delay", state.audio.offsetMs / 1_000.0)
            native.setPropertyDouble("sub-delay", state.subtitle.offsetMs / 1_000.0)
        }
    }

    private fun applySubtitleStyle(native: AndroidMpvEngine) {
        if (directVideoOutput) return
        val scaledMargin =
            when {
                surfaceHeight <= 0 -> 180
                presentation.subtitleBottomInsetPx <= 0 -> MPV_DEFAULT_SUBTITLE_BOTTOM_MARGIN_PX
                else -> maxOf(180, (presentation.subtitleBottomInsetPx * 720f / surfaceHeight).roundToInt())
            }
        native.setPropertyString("sub-font-size", ANDROID_MPV_SUBTITLE_BASE_FONT_SIZE.toString())
        _playbackState.value.subtitleStyle
            .toMpvSubtitleProperties(scaledPixelMargin = scaledMargin)
            .forEach { (name, value) -> native.setPropertyString(name, value) }
    }

    private fun applyPresentation(native: AndroidMpvEngine) {
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            native.setPropertyString("android-surface-size", "${surfaceWidth}x$surfaceHeight")
        }
        if (directVideoOutput) return
        when (presentation.resizeMode) {
            AndroidSurfaceResizeMode.Fit -> {
                native.setPropertyDouble("video-aspect-override", -1.0)
                native.setPropertyDouble("video-zoom", 0.0)
                native.setPropertyDouble("panscan", 0.0)
            }
            AndroidSurfaceResizeMode.Fill -> {
                val aspect =
                    if (surfaceWidth > 0 && surfaceHeight > 0) surfaceWidth.toDouble() / surfaceHeight else -1.0
                native.setPropertyDouble("video-aspect-override", aspect)
                native.setPropertyDouble("video-zoom", 0.0)
                native.setPropertyDouble("panscan", 0.0)
            }
            AndroidSurfaceResizeMode.Zoom -> {
                native.setPropertyDouble("video-aspect-override", -1.0)
                native.setPropertyDouble("video-zoom", 0.0)
                native.setPropertyDouble("panscan", 1.0)
            }
        }
        applySubtitleStyle(native)
    }

    private fun publishDerivedState() {
        val status =
            when {
                lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Completed -> PlaybackStatus.Completed
                lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Failed -> PlaybackStatus.Failed
                lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Stopped ||
                    lifecycle.state.outcome == AndroidMpvLifecycleOutcome.Released -> PlaybackStatus.Idle
                !lifecycle.state.loaded -> PlaybackStatus.Loading
                playbackFacts.pausedForCache ||
                    playbackFacts.seeking ||
                    resumeConfirmationFromPositionMs != null ||
                    (playbackFacts.coreIdle && !playbackFacts.paused && !playbackFacts.idleActive) -> PlaybackStatus.Buffering
                playbackFacts.paused -> PlaybackStatus.Paused
                playIntent -> PlaybackStatus.Playing
                else -> PlaybackStatus.Paused
            }
        publishState(status)
    }

    private fun publishState(
        status: PlaybackStatus,
        positionMs: Long = playbackFacts.positionMs,
        durationMs: Long? = playbackFacts.durationMs,
        error: PlaybackError? = if (status == PlaybackStatus.Failed) _playbackState.value.error else null,
    ) {
        val current = _playbackState.value
        val next =
            current.copy(
                status = status,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs?.takeIf { value -> value > 0L },
                bufferedPositionMs = playbackFacts.bufferedPositionMs.coerceAtLeast(positionMs),
                error = error.takeIf { status == PlaybackStatus.Failed },
            )
        if (next != current) _playbackState.value = next
    }

    private fun updateDiagnostics(transform: (PlaybackRuntimeDiagnostics) -> PlaybackRuntimeDiagnostics) {
        _runtimeDiagnostics.update(transform)
    }

    private fun observeDroppedFrames(
        outputCount: Long?,
        decoderCount: Long?,
    ): AndroidMpvDroppedFrameDiagnostics? {
        if (outputCount != null) latestOutputDropCount = outputCount
        if (decoderCount != null) latestDecoderDropCount = decoderCount
        val currentOutputCount = latestOutputDropCount ?: return null
        val currentDecoderCount = latestDecoderDropCount ?: return null
        val previousOutputCount = outputDropBaseline
        val previousDecoderCount = decoderDropBaseline
        val now = System.nanoTime()
        val result =
            mpvDroppedFramePoll(
                previousOutputCount = previousOutputCount,
                previousDecoderCount = previousDecoderCount,
                previousTimeNanos = dropBaselineNanos,
                currentOutputCount = currentOutputCount,
                currentDecoderCount = currentDecoderCount,
                nowNanos = now,
            )
        dropBaselineNanos = result.baselineTimeNanos
        outputDropBaseline = result.outputBaselineCount
        decoderDropBaseline = result.decoderBaselineCount
        result.measurement?.let(droppedFrameChannel::trySend)
        val diagnosticCounts = currentOutputCount to currentDecoderCount
        if (diagnosticCounts != lastDiagnosticDropCounts && logCollectionPreferences?.enabled?.value == true) {
            lastDiagnosticDropCounts = diagnosticCounts
            if (dropDiagnosticJob?.isActive != true) {
                val sampleGeneration = generation
                dropDiagnosticJob =
                    scope.launch {
                        delay(HOT_DIAGNOSTIC_PUBLICATION_INTERVAL_MS)
                        if (sampleGeneration == generation) recordNativeSnapshot(PlaybackNativeSampleCause.CountersChanged)
                    }
            }
        }
        return AndroidMpvDroppedFrameDiagnostics(
            total = listOfNotNull(outputDropBaseline, decoderDropBaseline).maxOrNull(),
            output = outputDropBaseline,
            decoder = decoderDropBaseline,
            ratePerSecond = result.measurement?.ratePerSecond,
        )
    }

    private fun postMain(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun warnReleased(operation: PlayerOperation) = Unit

    private data class AndroidMpvHotClockSample(
        val generation: Long,
        val positionMs: Long? = null,
        val bufferedPositionMs: Long? = null,
    )

    private data class AndroidMpvHotDiagnosticSample(
        val generation: Long,
        val bufferedAheadDirty: Boolean = false,
        val cacheSpeedBytesPerSecond: Double? = null,
        val containerFps: Double? = null,
        val outputDropCount: Long? = null,
        val decoderDropCount: Long? = null,
    )

    private sealed interface AndroidMpvHotSampleEpoch {
        data object Inactive : AndroidMpvHotSampleEpoch

        data class AwaitingStart(
            val generation: Long,
        ) : AndroidMpvHotSampleEpoch

        data class Active(
            val generation: Long,
        ) : AndroidMpvHotSampleEpoch
    }

    private data class AndroidMpvDroppedFrameDiagnostics(
        val total: Long?,
        val output: Long?,
        val decoder: Long?,
        val ratePerSecond: Double?,
    )

    private data class PlaybackFacts(
        val positionMs: Long = 0L,
        val durationMs: Long? = null,
        val bufferedPositionMs: Long = 0L,
        val paused: Boolean = true,
        val pausedForCache: Boolean = false,
        val seeking: Boolean = false,
        val coreIdle: Boolean = false,
        val idleActive: Boolean = false,
    )

    private companion object {
        val nextDiagnosticControllerSequence = AtomicLong()
        const val NO_NATIVE_EVENT_GENERATION = 0L
        const val SEEK_CONFIRMATION_TOLERANCE_MS = 250L
        const val HOT_CLOCK_PUBLICATION_INTERVAL_MS = 250L
        const val HOT_DIAGNOSTIC_PUBLICATION_INTERVAL_MS = 1_000L

        /** Well below the 5s input-dispatch ANR threshold; a normal detach takes milliseconds. */
        const val NATIVE_SURFACE_RELEASE_TIMEOUT_MS = 3_000L

        const val DIAGNOSTIC_LOG_MAX_BYTES = 32L * 1024L * 1024L
        const val DIAGNOSTIC_LOG_SIZE_POLL_MS = 30_000L
    }
}

private const val ANDROID_MPV_SUBTITLE_BASE_FONT_SIZE = 41.25f

private val androidMpvControllerLogger = diagnosticLogger(DiagnosticTag.AndroidMpvPlayerController)

private fun AndroidMpvLogCategory.toPlaybackError(): PlaybackError =
    when (this) {
        AndroidMpvLogCategory.Decoder -> PlaybackError.Decoder
        AndroidMpvLogCategory.Network -> PlaybackError.Network
        AndroidMpvLogCategory.Unsupported -> PlaybackError.UnsupportedMedia
        AndroidMpvLogCategory.Unknown -> PlaybackError.Unknown
    }

private fun diagnosticVideoOutput(value: String?): PlaybackNativeVideoOutput =
    when (value) {
        "gpu" -> PlaybackNativeVideoOutput.Gpu
        "gpu-next" -> PlaybackNativeVideoOutput.GpuNext
        "mediacodec_embed" -> PlaybackNativeVideoOutput.MediaCodecEmbed
        null, "" -> PlaybackNativeVideoOutput.Unavailable
        else -> PlaybackNativeVideoOutput.Other
    }
