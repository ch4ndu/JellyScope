// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.Media3AudioDecoderReason
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.PlayerTimingController
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.knownSubtitleFormat
import com.jellyscope.core.domain.playback.normalizeSubtitleFormat
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class Media3PlayerController(
    context: Context,
    private val session: Session,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val clientInfo: ClientInfo,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val audioFocusCoordinator: AndroidAudioFocusCoordinator? = null,
) : PlayerController,
    AndroidMedia3SubtitleTimingBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var released = false
    private var positionJob: Job? = null
    private var lastPlan: PlaybackPlan? = null
    private var audioTrackInitRetries = 0
    private var audioDisabledForOutputFailure = false
    private var recoveringFromAudioOutputFailure = false
    private var pendingEmbeddedAudioSelection: EmbeddedAudioSelection? = null
    private var pendingEmbeddedSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var expectedSubtitleTarget: SubtitleActivationTarget? = null
    private var playIntent = false
    private var playbackFocusAdmitted = audioFocusCoordinator == null
    private var resumeAfterTransientFocusLoss = false

    // Bumped on every prepare(); a coalesced timing reconfigure captures this at
    // schedule time and skips if a prepare has since applied the offset.
    private var prepareGeneration = 0L
    private var performanceEpochActive = false
    private var reconfigureJob: Job? = null
    private var playbackGeneration = 0L
    private var audioRecoveryJob: Job? = null
    private var initialAudioGate = false
    private val trackResolutionDiagnostics = TrackResolutionDiagnosticGate()
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()
    private var offlinePath: String? = null
    private var offlineSidecarPath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null

    /** Platform DI seam; remote construction remains compatible until the graph supplies it. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)
    private val authorizationHeader =
        AuthHeaderBuilder.build(
            deviceName = deviceInfoProvider.deviceName,
            deviceId = session.deviceId,
            clientInfo = clientInfo,
            token = session.accessToken,
        )
    private val credentialRequestPolicy =
        Media3CredentialRequestPolicy(
            credentialOriginGuard = credentialOriginGuard,
            authorizationHeader = authorizationHeader,
        )
    private val media3HttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val resolved =
                    credentialRequestPolicy.resolve(
                        resourceUrl = request.url.toString(),
                        requestHeaders = request.headers,
                    )
                val sanitizedRequest =
                    request
                        .newBuilder()
                        .url(resolved.sanitizedUrl)
                        .headers(
                            Headers
                                .Builder()
                                .apply {
                                    resolved.headers.forEach { (name, value) -> add(name, value) }
                                }.build(),
                        ).build()
                chain.proceed(sanitizedRequest)
            }.build()
    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(media3HttpClient),
            ResolvingDataSource.Resolver { dataSpec ->
                val resolved =
                    credentialRequestPolicy.resolve(
                        resourceUrl = dataSpec.uri.toString(),
                        requestHeaders =
                            dataSpec.httpRequestHeaders
                                .map { (name, value) -> name to value },
                    )
                dataSpec
                    .buildUpon()
                    .setUri(resolved.sanitizedUrl)
                    .setHttpRequestHeaders(resolved.headers.toMap())
                    .build()
            },
        )

    // Keep the credential-aware OkHttp resolver for remote HTTP(S), but let
    // Media3 select its private FileDataSource for a trusted Offline lease.
    // Passing the OkHttp-only factory directly would make a local HLS master
    // playlist look like a network request and would weaken the offline
    // no-credentials boundary for its relative media/segment resources.
    private val localAwareDataSourceFactory =
        DefaultDataSource.Factory(context.applicationContext, dataSourceFactory)

    private val audioTimingProcessor = Media3AudioDelayProcessor()
    private val subtitleTimingRenderer = AndroidMedia3SubtitleTimingRenderer()
    private val media3TimingController =
        Media3TimingController(
            audioProcessor = audioTimingProcessor,
            subtitleRenderer = subtitleTimingRenderer,
            onAudioOutputModeChanged = ::scheduleTimingReconfigure,
        )
    private val media3LoadControlPolicy =
        resolveMedia3LoadControlPolicy(
            context = context,
            // The larger regular-memory target remains an experiment until the
            // documented same-fixture device A/B passes.
            useRegularCandidate = false,
        )
    private val media3Allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val performanceTracker = Media3PlaybackPerformanceTracker(SystemClock::elapsedRealtime)
    private val media3LoadControl =
        DefaultLoadControl
            .Builder()
            .setAllocator(media3Allocator)
            .setBufferDurationsMs(
                media3LoadControlPolicy.minBufferMs,
                media3LoadControlPolicy.maxBufferMs,
                media3LoadControlPolicy.bufferForPlaybackMs,
                media3LoadControlPolicy.bufferAfterRebufferMs,
            ).setTargetBufferBytes(media3LoadControlPolicy.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(
                media3LoadControlPolicy.prioritizeTimeOverSizeThresholds,
            ).build()
    private val exoPlayer =
        ExoPlayer
            .Builder(context.applicationContext)
            // This is a load-decision target, not a total-memory ceiling.
            // Streaming time priority may allocate past it until the configured
            // duration floor is satisfied.
            .setLoadControl(media3LoadControl)
            .setRenderersFactory(
                JellyfinMedia3RenderersFactory(
                    context = context.applicationContext,
                    audioProcessor = audioTimingProcessor,
                ),
            ).setAudioAttributes(
                androidx.media3.common.AudioAttributes
                    .Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            ).setHandleAudioBecomingNoisy(false)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source
                    .DefaultMediaSourceFactory(localAwareDataSourceFactory),
            ).build()

    private val listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    audioTrackInitRetries = 0
                    recoveringFromAudioOutputFailure = false
                }
                updateAudioActivation()
                updateSubtitleActivation()
                updateState()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyPendingEmbeddedAudioSelection(tracks)
                applyPendingEmbeddedSubtitleSelection(tracks)
                updateAudioActivation(tracks)
                updateSubtitleActivation(tracks)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                // AudioFlinger occasionally returns ENOMEM (-12) creating the
                // AudioTrack — transient resource pressure (seen on the Shield).
                // Re-prepare at the current position a few times before giving
                // up; the track slot frees and playback recovers.
                if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
                    !audioDisabledForOutputFailure &&
                    audioTrackInitRetries < MAX_AUDIO_TRACK_INIT_RETRIES
                ) {
                    audioTrackInitRetries++
                    logTerminalError(
                        error = error,
                        category = PlaybackError.AudioOutput,
                        outcome = PlaybackTerminalOutcome.RetryScheduled,
                        retryAttempted = true,
                        degradationAttempted = false,
                    )
                    retryAfterAudioTrackInitFailure()
                    return
                }
                if (error.isAudioOutputFailure()) {
                    if (audioDisabledForOutputFailure) {
                        logTerminalError(
                            error = error,
                            category = PlaybackError.AudioOutput,
                            outcome = PlaybackTerminalOutcome.VideoOnlyPlayback,
                            retryAttempted = audioTrackInitRetries > 0,
                            degradationAttempted = true,
                        )
                        _playbackState.update { current ->
                            current.copy(
                                error = null,
                                audioUnavailable = current.status.allowsAudioUnavailable(),
                            )
                        }
                    } else {
                        val degradationAttempted = degradeToVideoOnlyAfterAudioFailure()
                        logTerminalError(
                            error = error,
                            category = PlaybackError.AudioOutput,
                            outcome =
                                if (degradationAttempted) {
                                    PlaybackTerminalOutcome.VideoOnlyDegradationScheduled
                                } else {
                                    PlaybackTerminalOutcome.Failed
                                },
                            retryAttempted = audioTrackInitRetries > 0,
                            degradationAttempted = degradationAttempted,
                        )
                        if (!degradationAttempted) {
                            failPlayback(PlaybackError.AudioOutput)
                        }
                    }
                    return
                }
                val category =
                    if (recoveringFromAudioOutputFailure) {
                        PlaybackError.AudioOutput
                    } else {
                        classifyPlaybackError(error)
                    }
                logTerminalError(
                    error = error,
                    category = category,
                    outcome = PlaybackTerminalOutcome.Failed,
                    retryAttempted = audioTrackInitRetries > 0,
                    degradationAttempted = audioDisabledForOutputFailure,
                )
                failPlayback(category)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _runtimeDiagnostics.update { current ->
                    media3RuntimeDiagnosticsAfterVideoInfo(
                        current = current,
                        width = videoSize.width,
                        height = videoSize.height,
                    )
                }
            }
        }

    private val analyticsListener =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                _runtimeDiagnostics.update { current ->
                    media3RuntimeDiagnosticsAfterVideoInfo(
                        current = current,
                        decoderName = decoderName,
                    )
                }
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val eventPrepareEpoch = eventTime.prepareEpoch() ?: return
                if (released || eventPrepareEpoch != prepareGeneration) return
                controllerLogger.i {
                    formatPlaybackDiagnostic(
                        media3AudioDecoderInitializedDiagnostic(
                            decoderName = decoderName,
                            prepareSequence = eventPrepareEpoch,
                            sessionSequence = lastPlan?.diagnosticSessionSequence ?: playbackGeneration,
                        ),
                    )
                }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                _runtimeDiagnostics.update { current ->
                    media3RuntimeDiagnosticsAfterVideoInfo(
                        current = current,
                        width = format.width,
                        height = format.height,
                        frameRate = format.frameRate.toDouble(),
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                if (released) return
                DroppedFrameMeasurement
                    .create(
                        droppedFrames = droppedFrames.toLong(),
                        intervalMs = elapsedMs,
                    )?.let { measurement -> droppedFrameMeasurementsChannel.trySend(measurement) }
                _runtimeDiagnostics.update { current ->
                    media3RuntimeDiagnosticsAfterDroppedFrames(
                        current = current,
                        droppedFrames = droppedFrames,
                        elapsedMs = elapsedMs,
                    )
                }
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                _runtimeDiagnostics.update { current ->
                    current.copy(
                        bandwidthEstimateBps = bitrateEstimate.takeIf { estimate -> estimate > 0L },
                    )
                }
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                val eventPrepareEpoch = eventTime.prepareEpoch() ?: return
                if (!released && eventPrepareEpoch == prepareGeneration) {
                    videoOutputObservationsChannel.trySend(
                        VideoOutputObservation(
                            generation = eventPrepareEpoch,
                            presented = true,
                            observedAtMs = SystemClock.elapsedRealtime(),
                        ),
                    )
                }
                performanceTracker.onRenderedFirstFrame(eventPrepareEpoch)
                publishPerformanceDiagnostics()
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                val eventPrepareEpoch = eventTime.prepareEpoch() ?: return
                performanceTracker.onAudioUnderrun(
                    prepareEpoch = eventPrepareEpoch,
                    elapsedSinceLastFeedMs = elapsedSinceLastFeedMs,
                )
                publishPerformanceDiagnostics()
            }
        }

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
    private val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    private val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)

    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> =
        droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> =
        videoOutputObservationsChannel.receiveAsFlow()
    override val videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.NativeFirstOutput
    override val platformPlayer: Any? = exoPlayer
    override val activeBackend = com.jellyscope.core.domain.playback.PlayerBackend.ExoPlayer
    override val timingController: PlayerTimingController = media3TimingController
    override val playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = true,
            hasDroppedFrameMeasurements = true,
            hasReliableFirstVideoOutput = true,
        )

    /** Coalesces rapid audio-offset nudges and drops work superseded by prepare(). */
    private fun scheduleTimingReconfigure() {
        if (released) return
        reconfigureJob?.cancel()
        val scheduledAtGeneration = prepareGeneration
        reconfigureJob =
            scope.launch {
                delay(TIMING_RECONFIGURE_DEBOUNCE_MS)
                reconfigureJob = null
                if (released || prepareGeneration != scheduledAtGeneration) return@launch
                applyTimingReconfigure()
            }
    }

    /** Rebuild the current item so the audio sink picks up the new PCM offset. */
    private fun applyTimingReconfigure() {
        if (released) return
        val plan = lastPlan ?: return
        val positionMs = exoPlayer.safePosition()
        val shouldPlay = playIntent
        val audioSelection = pendingEmbeddedAudioSelection
        val subtitleSelection = pendingEmbeddedSubtitleSelection
        // Reuse the normal prepare path so a timing transition does not discard
        // the active audio/subtitle selection or activation gates. Carry live
        // position + speed + subtitle style into the replacement plan so a nudge
        // never reverts a user speed/style change (setPlaybackSpeed does not
        // update lastPlan).
        prepare(
            plan.copy(
                startPositionMs = positionMs,
                playbackSpeed = _playbackState.value.playbackSpeed,
                subtitleStyle = _playbackState.value.subtitleStyle,
            ),
            plan.subtitleAsset,
        )
        audioSelection?.let(::selectEmbeddedAudio)
        subtitleSelection?.let(::selectEmbeddedSubtitle)
        if (shouldPlay) play()
    }

    override fun attachSubtitleView(
        player: Player,
        applyCues: (List<Cue>) -> Unit,
    ) {
        if (player === exoPlayer) {
            subtitleTimingRenderer.attach(player, applyCues)
        }
    }

    override fun detachSubtitleView() {
        subtitleTimingRenderer.detach()
    }

    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = scope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(subtitleActivation = activation) }
            },
            platform = PlaybackDiagnosticPlatform.Android,
        )
    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = scope,
            platform = PlaybackDiagnosticPlatform.Android,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(audioActivation = activation) }
            },
            onActivated = ::releasePlayIntentAfterAudioActivation,
        )

    init {
        exoPlayer.addListener(listener)
        exoPlayer.addAnalyticsListener(analyticsListener)
    }

    private fun invalidateDelayedRecovery() {
        playbackGeneration += 1L
        audioRecoveryJob?.cancel()
        audioRecoveryJob = null
    }

    private fun onAudioFocusEvent(
        generation: Long,
        event: AndroidAudioFocusEvent,
    ) {
        if (released || generation != playbackGeneration) return
        when (event) {
            AndroidAudioFocusEvent.Gained -> {
                exoPlayer.volume = 1f
                if (resumeAfterTransientFocusLoss && playIntent) {
                    resumeAfterTransientFocusLoss = false
                    exoPlayer.play()
                    updateState()
                }
            }
            AndroidAudioFocusEvent.Duck -> exoPlayer.volume = 0.2f
            AndroidAudioFocusEvent.TransientLoss -> {
                resumeAfterTransientFocusLoss = playIntent
                exoPlayer.pause()
                updateState()
            }
            AndroidAudioFocusEvent.PermanentLoss,
            AndroidAudioFocusEvent.BecomingNoisy,
            -> {
                resumeAfterTransientFocusLoss = false
                playIntent = false
                playbackFocusAdmitted = false
                exoPlayer.volume = 1f
                exoPlayer.pause()
                audioFocusCoordinator?.abandon(generation)
                updateState()
            }
        }
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (released) return warnReleased(PlayerOperation.Prepare)
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            val replacedOfflineLease = offlineLeaseHolder.detach()
            // ExoPlayer may still own/read the previous file-backed item until this synchronous
            // replacement. Release the lease only after native teardown has completed.
            exoPlayer.stop()
            offlinePath = null
            offlineSidecarPath = null
            replacedOfflineLease?.release()
        } else if (offlinePath == null) {
            _playbackState.update { current ->
                current.copy(status = PlaybackStatus.Failed, error = PlaybackError.OfflineArtifactUnavailable)
            }
            return
        }
        finishPerformanceEpoch()
        invalidateDelayedRecovery()
        prepareGeneration += 1L
        performanceTracker.begin(prepareGeneration)
        performanceEpochActive = true
        reconfigureJob?.cancel()
        reconfigureJob = null
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = audioFocusCoordinator == null
        resumeAfterTransientFocusLoss = false
        trackResolutionDiagnostics.reset()
        media3TimingController.clearForDiscontinuity()
        _runtimeDiagnostics.value =
            PlaybackRuntimeDiagnostics.EMPTY.copy(
                prepareEpoch = prepareGeneration,
                bufferPolicy = media3LoadControlPolicy.diagnosticPolicy,
                lowRamDevice = media3LoadControlPolicy.memoryClass == Media3MemoryClass.LowRam,
                targetBufferBytes = media3LoadControlPolicy.targetBufferBytes.toLong(),
                rebufferCount = 0,
                totalRebufferMs = 0L,
                maxRebufferMs = 0L,
                audioUnderrunCount = 0,
                maxAudioFeedGapMs = 0L,
            )
        val freshMediaItem = lastPlan?.itemId != plan.itemId
        if (freshMediaItem) {
            resetAudioOutputFailureForFreshMediaItem()
        }
        lastPlan = plan
        playIntent = false
        exoPlayer.pause()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = plan.subtitleActivationTarget
        val initialAudioActivation = initialAudioActivationFor(plan)
        initialAudioGate = initialAudioActivation is InitialAudioActivation.AwaitNativeMapping
        audioActivationConfirmation.applyInitial(initialAudioActivation)
        disableTextTracks()
        subtitleActivationConfirmation.beginForPlan(plan, subtitleAsset)
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Loading,
                positionMs = plan.startPositionMs,
                durationMs = null,
                bufferedPositionMs = 0L,
                playbackSpeed = plan.playbackSpeed,
                subtitleStyle = plan.subtitleStyle,
                error = null,
                audioUnavailable = false,
            )
        }
        exoPlayer.setPlaybackSpeed(plan.playbackSpeed)
        exoPlayer.setMediaItem(plan.toMediaItem(subtitleAsset))
        exoPlayer.seekTo(plan.startPositionMs)
        exoPlayer.prepare()
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (released) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.ExoPlayer))
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
            // prepare() normally stops synchronously, but keep the exact lease
            // detached until the same native stop boundary even when a future
            // ExoPlayer failure is surfaced from this call.
            val failedLease = offlineLeaseHolder.detach()
            exoPlayer.stop()
            failedLease?.release()
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.ExoPlayer))
        }
    }

    // Recovery for a transient AudioTrack init failure (ENOMEM / -12), typically
    // when a playlist item's new audio format needs a fresh AudioTrack before the
    // previous one has been released. Fully stop (release the sink), then
    // re-prepare at the current position after a short delay.
    private fun retryAfterAudioTrackInitFailure() {
        val plan = lastPlan ?: return
        val generation = playbackGeneration
        val resumeMs = exoPlayer.safePosition().coerceAtLeast(0L)
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Buffering,
                error = null,
                audioUnavailable = false,
            )
        }
        audioRecoveryJob?.cancel()
        audioRecoveryJob =
            scope.launch {
                delay(AUDIO_TRACK_INIT_RETRY_DELAY_MS)
                // An in-player subtitle switch replaces `lastPlan` with an updated
                // copy of this same session (see `syncPlanSubtitleTarget`), so plan
                // identity would falsely invalidate a recovery scheduled just
                // before it. Match the session by item — a genuinely different
                // session went through prepare()/stop() and bumped the generation —
                // and rebuild from the live plan so the retry carries live intent.
                val livePlan = lastPlan ?: return@launch
                if (!media3RecoveryIsCurrent(released, generation, playbackGeneration, livePlan.itemId == plan.itemId)) {
                    return@launch
                }
                val liveSubtitleAsset =
                    livePlan.subtitleAsset.takeUnless {
                        livePlan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
                    }
                subtitleActivationConfirmation.beginForPlan(livePlan, liveSubtitleAsset)
                exoPlayer.stop()
                exoPlayer.setMediaItem(livePlan.toMediaItem(liveSubtitleAsset))
                exoPlayer.seekTo(resumeMs)
                exoPlayer.prepare()
                if (playIntent && playbackFocusAdmitted) exoPlayer.play()
            }
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedAudio)
        enableAudioAfterManualSelection()
        pendingEmbeddedAudioSelection = selection
        if ((_playbackState.value.audioActivation as? AudioActivationState.Pending)?.target != selection.target) {
            audioActivationConfirmation.begin(selection.target)
        }
        applyPendingEmbeddedAudioSelection(exoPlayer.currentTracks)
        updateAudioActivation()
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedSubtitle)
        pendingEmbeddedSubtitleSelection = selection
        if (selection == null) {
            expectedSubtitleTarget = null
            syncPlanSubtitleTarget(null)
            disableTextTracks()
            subtitleActivationConfirmation.clear()
            return
        }
        expectedSubtitleTarget = selection.target
        syncPlanSubtitleTarget(selection.target)
        subtitleActivationConfirmation.begin(selection.target)
        applyPendingEmbeddedSubtitleSelection(exoPlayer.currentTracks)
        updateSubtitleActivation()
    }

    /**
     * Keeps the controller's authoritative plan aligned with an in-player
     * subtitle switch.
     *
     * `PlayerViewModel` updates only its own plan copy, so without this
     * `lastPlan` keeps the target the session was prepared with and every
     * controller-internal re-prepare — timing reconfigure, audio-track-init
     * retry, video-only degrade — rebuilds the `MediaItem` from that stale
     * intent, silently reverting the user's choice. The sidecar asset belongs to
     * an external target only: any other switch, including Off, drops it so a
     * re-prepare cannot re-arm a dead target.
     */
    private fun syncPlanSubtitleTarget(target: SubtitleActivationTarget?) {
        val plan = lastPlan ?: return
        if (plan.subtitleActivationTarget == target) return
        lastPlan =
            plan.copy(
                subtitleActivationTarget = target,
                subtitleAsset = plan.subtitleAsset.takeIf { target?.kind == LocalSubtitleKind.ExternalText },
            )
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return warnReleased(PlayerOperation.SetPlaybackSpeed)
        exoPlayer.setPlaybackSpeed(speed)
        _playbackState.update { current -> current.copy(playbackSpeed = speed) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (released) return warnReleased(PlayerOperation.SetSubtitleStyle)
        _playbackState.update { current -> current.copy(subtitleStyle = style) }
    }

    override fun play() {
        if (released) return warnReleased(PlayerOperation.Play)
        playIntent = true
        if (initialAudioGate && _playbackState.value.audioActivation is AudioActivationState.Pending) {
            updateState()
            return
        }
        val admitted =
            PlaybackFocusAdmission.admit(
                requestFocus = { audioFocusCoordinator?.request(playbackGeneration, ::onAudioFocusEvent) ?: true },
                nativeStart = {
                    playbackFocusAdmitted = true
                    exoPlayer.play()
                },
            )
        if (!admitted) {
            playIntent = false
            playbackFocusAdmitted = false
            performanceTracker.excludeBufferingUntilPlaying()
            stopPositionTicker()
            _playbackState.update { current ->
                if (current.status == PlaybackStatus.Failed || current.status == PlaybackStatus.Completed) {
                    current
                } else {
                    current.copy(status = PlaybackStatus.Paused)
                }
            }
            return
        }
        updateState()
    }

    override fun pause() {
        if (released) return warnReleased(PlayerOperation.Pause)
        playIntent = false
        playbackFocusAdmitted = false
        performanceTracker.excludeBufferingUntilPlaying()
        exoPlayer.pause()
        updateState()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return warnReleased(PlayerOperation.SeekTo)
        performanceTracker.excludeBufferingUntilPlaying()
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        updateState()
    }

    override fun stop() {
        if (released) return warnReleased(PlayerOperation.Stop)
        finishPerformanceEpoch()
        invalidateDelayedRecovery()
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = false
        resumeAfterTransientFocusLoss = false
        recoveringFromAudioOutputFailure = false
        playIntent = false
        initialAudioGate = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        exoPlayer.stop()
        stopPositionTicker()
        updateState()
        val detachedOfflineLease = offlineLeaseHolder.detach()
        offlinePath = null
        offlineSidecarPath = null
        detachedOfflineLease?.release()
    }

    override fun retry() {
        if (released) return warnReleased(PlayerOperation.Retry)
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
        }
    }

    override fun release() {
        if (released) return
        finishPerformanceEpoch()
        released = true
        invalidateDelayedRecovery()
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        stopPositionTicker()
        exoPlayer.removeListener(listener)
        exoPlayer.removeAnalyticsListener(analyticsListener)
        subtitleTimingRenderer.detach()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        droppedFrameMeasurementsChannel.close()
        videoOutputObservationsChannel.close()
        val detachedOfflineLease = offlineLeaseHolder.detach()
        exoPlayer.release()
        detachedOfflineLease?.release()
        offlinePath = null
        offlineSidecarPath = null
        media3HttpClient.dispatcher.executorService.shutdown()
        media3HttpClient.connectionPool.evictAll()
        scope.cancel()
    }

    override fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) {
        if (released) return
        performanceTracker.recordLaunchToFirstFrame(prepareEpoch, durationMs)
    }

    private fun warnReleased(operation: PlayerOperation) {
        controllerLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Release,
                    event = PlaybackDiagnosticEvent.Rejected,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    operation = operation,
                ),
            )
        }
    }

    private fun updateState() {
        val status =
            when (exoPlayer.playbackState) {
                Player.STATE_BUFFERING -> PlaybackStatus.Buffering
                Player.STATE_READY ->
                    media3ReadyPlaybackStatus(
                        isPlaying = exoPlayer.isPlaying,
                        playIntent = playIntent,
                        initialAudioGate = initialAudioGate,
                    )
                Player.STATE_ENDED -> PlaybackStatus.Completed
                Player.STATE_IDLE ->
                    if (_playbackState.value.status == PlaybackStatus.Failed) {
                        PlaybackStatus.Failed
                    } else {
                        PlaybackStatus.Idle
                    }
                else -> _playbackState.value.status
            }

        _playbackState.update { current ->
            PlaybackState(
                status = status,
                positionMs = exoPlayer.safePosition(),
                durationMs = exoPlayer.safeDuration(),
                bufferedPositionMs = exoPlayer.safeBufferedPosition(),
                playbackSpeed = exoPlayer.playbackParameters.speed,
                subtitleStyle = current.subtitleStyle,
                audioActivation = current.audioActivation,
                subtitleActivation = current.subtitleActivation,
                error = current.error.takeIf { status == PlaybackStatus.Failed },
                audioUnavailable =
                    audioDisabledForOutputFailure &&
                        status.allowsAudioUnavailable(),
            )
        }
        performanceTracker.onPlaybackStatus(prepareGeneration, status)
        publishPerformanceDiagnostics()

        if (status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering) {
            startPositionTicker()
        } else {
            stopPositionTicker()
        }
    }

    private fun startPositionTicker() {
        if (positionJob != null) {
            return
        }

        positionJob =
            scope.launch {
                while (isActive) {
                    delay(POSITION_POLL_INTERVAL_MS)
                    val positionMs = exoPlayer.safePosition()
                    val bufferedPositionMs = exoPlayer.safeBufferedPosition()
                    performanceTracker.onSample(
                        prepareEpoch = prepareGeneration,
                        allocatedBufferBytes = media3Allocator.totalBytesAllocated.toLong(),
                        bufferedAheadMs = (bufferedPositionMs - positionMs).coerceAtLeast(0L),
                    )
                    publishPerformanceDiagnostics()
                    _playbackState.update { current ->
                        current.copy(
                            positionMs = positionMs,
                            durationMs = exoPlayer.safeDuration(),
                            bufferedPositionMs = bufferedPositionMs,
                        )
                    }
                }
            }
    }

    private fun stopPositionTicker() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun publishPerformanceDiagnostics() {
        if (!performanceEpochActive) return
        val snapshot = performanceTracker.snapshot()
        _runtimeDiagnostics.update { current ->
            current.copy(
                prepareEpoch = snapshot.prepareEpoch,
                bufferPolicy = media3LoadControlPolicy.diagnosticPolicy,
                lowRamDevice = media3LoadControlPolicy.memoryClass == Media3MemoryClass.LowRam,
                targetBufferBytes = media3LoadControlPolicy.targetBufferBytes.toLong(),
                allocatedBufferBytes = snapshot.currentAllocatedBufferBytes,
                peakAllocatedBufferBytes = snapshot.peakAllocatedBufferBytes,
                bufferedAheadMs = snapshot.currentBufferedAheadMs,
                nativePrepareToFirstFrameMs = snapshot.nativePrepareToFirstFrameMs,
                rebufferCount = snapshot.rebufferCount,
                totalRebufferMs = snapshot.totalRebufferMs,
                maxRebufferMs = snapshot.maxRebufferMs,
                audioUnderrunCount = snapshot.audioUnderrunCount,
                maxAudioFeedGapMs = snapshot.maxAudioFeedGapMs,
            )
        }
    }

    private fun finishPerformanceEpoch() {
        if (!performanceEpochActive) return
        val snapshot = performanceTracker.finish()
        performanceEpochActive = false
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.PerformanceSummary,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    streamMode = lastPlan?.streamMode,
                    container = lastPlan?.container,
                    maxStreamingBitrate = lastPlan?.maxStreamingBitrate,
                    bufferPolicy = media3LoadControlPolicy.diagnosticPolicy,
                    lowRamDevice = media3LoadControlPolicy.memoryClass == Media3MemoryClass.LowRam,
                    targetBufferBytes = media3LoadControlPolicy.targetBufferBytes.toLong(),
                    peakAllocatedBufferBytes = snapshot.peakAllocatedBufferBytes,
                    minBufferedAheadMs = snapshot.minBufferedAheadMs,
                    maxBufferedAheadMs = snapshot.maxBufferedAheadMs,
                    nativePrepareToFirstFrameMs = snapshot.nativePrepareToFirstFrameMs,
                    launchToFirstFrameMs = snapshot.launchToFirstFrameMs,
                    rebufferCount = snapshot.rebufferCount,
                    totalRebufferMs = snapshot.totalRebufferMs,
                    maxRebufferMs = snapshot.maxRebufferMs,
                    audioUnderrunCount = snapshot.audioUnderrunCount,
                    maxAudioFeedGapMs = snapshot.maxAudioFeedGapMs,
                ),
            )
        }
    }

    private fun ExoPlayer.safePosition(): Long = currentPosition.coerceAtLeast(0L)

    private fun ExoPlayer.safeDuration(): Long? =
        duration
            .takeIf { value -> value != C.TIME_UNSET && value > 0L }

    private fun ExoPlayer.safeBufferedPosition(): Long = bufferedPosition.coerceAtLeast(0L)

    private fun disableTextTracks() {
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
    }

    private fun applyPendingEmbeddedAudioSelection(tracks: Tracks) {
        val selection = pendingEmbeddedAudioSelection ?: return
        val resolution = resolveMedia3Track(selection.descriptor, C.TRACK_TYPE_AUDIO, tracks)
        logTrackResolution(
            kind = PlaybackDiagnosticTrackKind.Audio,
            target = selection.target,
            candidateCount = tracks.audioCandidateCount(),
            resolution = resolution,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> resolution.candidate?.let(::selectAudioGroup)
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            ->
                audioActivationConfirmation.fail(
                    selection.target,
                    resolution.result,
                    tracks.audioCandidateCount(),
                )
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> Unit
        }
    }

    private fun applyPendingEmbeddedSubtitleSelection(tracks: Tracks) {
        val selection = pendingEmbeddedSubtitleSelection ?: return
        if (lastPlan?.subtitleActivationTarget != selection.target) {
            return
        }
        val resolution = resolveMedia3Track(selection.descriptor, C.TRACK_TYPE_TEXT, tracks)
        val candidateCount = tracks.embeddedTextCandidateCount()
        logTrackResolution(
            kind = PlaybackDiagnosticTrackKind.Subtitle,
            target = selection.target,
            candidateCount = candidateCount,
            resolution = resolution,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> {
                subtitleActivationConfirmation.fail(
                    selection.target,
                    result = resolution.result,
                    candidateCount = candidateCount,
                )
                return
            }
            NativeTrackMappingResult.Active,
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> Unit
        }
        val group = resolution.candidate ?: return
        if (!group.isTrackSelected(0)) {
            selectTextGroup(group)
        }
    }

    private fun applyPendingExternalSubtitleSelection(tracks: Tracks) {
        val target =
            lastPlan
                ?.subtitleActivationTarget
                ?.takeIf { candidate -> candidate.kind == LocalSubtitleKind.ExternalText }
                ?: return
        val group = tracks.externalSubtitleGroup(target) ?: return
        if (!group.isTrackSelected(0)) {
            selectTextGroup(group)
        }
    }

    private fun selectTextGroup(group: Tracks.Group) {
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
    }

    private fun selectAudioGroup(group: Tracks.Group) {
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
    }

    private fun updateAudioActivation(tracks: Tracks = exoPlayer.currentTracks) {
        val selection = pendingEmbeddedAudioSelection ?: return
        val resolution = resolveMedia3Track(selection.descriptor, C.TRACK_TYPE_AUDIO, tracks)
        if (resolution.candidate?.isTrackSelected(0) == true) {
            initialAudioGate = false
            audioActivationConfirmation.confirm(selection.target)
        } else if (exoPlayer.playbackState == Player.STATE_READY || tracks.audioCandidateCount() > 0) {
            audioActivationConfirmation.armTimeout(selection.target, tracks.audioCandidateCount())
        }
    }

    private fun releasePlayIntentAfterAudioActivation() {
        initialAudioGate = false
        if (playIntent && !released) {
            play()
        }
    }

    private fun updateSubtitleActivation(tracks: Tracks = exoPlayer.currentTracks) {
        val target = expectedSubtitleTarget
        if (target == null) {
            subtitleActivationConfirmation.clear()
            return
        }

        if (target.kind == LocalSubtitleKind.ExternalText) {
            applyPendingExternalSubtitleSelection(tracks)
        }
        val matchingGroup =
            when (target.kind) {
                LocalSubtitleKind.EmbeddedText,
                LocalSubtitleKind.EmbeddedBitmap,
                LocalSubtitleKind.HlsText,
                -> {
                    val selection = pendingEmbeddedSubtitleSelection?.takeIf { pending -> pending.target == target }
                    selection?.let { pending ->
                        resolveMedia3Track(pending.descriptor, C.TRACK_TYPE_TEXT, tracks).candidate
                    }
                }
                LocalSubtitleKind.ExternalText -> tracks.externalSubtitleGroup(target)
            }
        if (matchingGroup?.isTrackSelected(0) == true) {
            subtitleActivationConfirmation.confirm(target)
        } else if (exoPlayer.playbackState == Player.STATE_READY) {
            subtitleActivationConfirmation.armTimeout(target, tracks.embeddedTextCandidateCount())
        }
    }

    private fun resolveMedia3Track(
        descriptor: com.jellyscope.core.domain.playback.PlannedEmbeddedTrack,
        type: Int,
        tracks: Tracks,
    ): NativeTrackResolution<Tracks.Group> {
        val typedGroups =
            tracks.groups
                .withIndex()
                .filter { (_, group) -> group.type == type }
                .filterNot { (_, group) -> type == C.TRACK_TYPE_TEXT && group.isKnownExternalSubtitleGroup() }

        val ordered =
            typedGroups.sortedWith { left, right ->
                compareMedia3TrackIds(left.value.firstFormatId(), right.value.firstFormatId(), left.index, right.index)
            }
        val trackKind =
            if (type == C.TRACK_TYPE_TEXT) {
                EmbeddedTrackKind.Subtitle
            } else {
                EmbeddedTrackKind.Audio
            }
        return resolveMedia3TrackCandidates(
            descriptor = descriptor,
            trackKind = trackKind,
            candidates =
                ordered.map { (_, group) ->
                    val format = group.firstFormatOrNull()
                    Media3TrackResolutionCandidate(
                        value = group,
                        stableSourceIndex = format?.id.media3StableSourceIndex(),
                        codec =
                            if (trackKind == EmbeddedTrackKind.Subtitle) {
                                format?.subtitleCodecIdentity()
                            } else {
                                format?.codecs ?: format?.sampleMimeType
                            },
                        language = format?.language,
                        label = format?.label,
                        supported = group.length > 0 && group.isTrackSupported(0),
                    )
                },
        )
    }

    private fun Tracks.Group.firstFormatOrNull(): Format? = if (length > 0) getTrackFormat(0) else null

    private fun Tracks.Group.firstFormatId(): String? = firstFormatOrNull()?.id

    private fun Format.subtitleCodecIdentity(): String? =
        listOf(codecs, sampleMimeType)
            .firstNotNullOfOrNull(::knownSubtitleFormat)
            ?: normalizeSubtitleFormat(codecs ?: sampleMimeType)

    private fun Tracks.Group.isKnownExternalSubtitleGroup(): Boolean {
        val expectedId = lastPlan?.subtitleActivationTarget?.media3SubtitleId()
        return expectedId != null &&
            (0 until length).any { index -> media3FormatIdMatchesTarget(getTrackFormat(index).id, expectedId) }
    }

    private fun Tracks.audioCandidateCount(): Int = groups.count { group -> group.type == C.TRACK_TYPE_AUDIO }

    private fun Tracks.embeddedTextCandidateCount(): Int =
        groups.count { group -> group.type == C.TRACK_TYPE_TEXT && !group.isKnownExternalSubtitleGroup() }

    private fun logTrackResolution(
        kind: PlaybackDiagnosticTrackKind,
        target: Any?,
        candidateCount: Int,
        resolution: NativeTrackResolution<Tracks.Group>,
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
                    platform = PlaybackDiagnosticPlatform.Android,
                    trackKind = kind,
                    candidateCount = candidateCount,
                    mappingResult = resolution.result,
                    mappingReason = resolution.reason,
                ),
            )
        }
    }

    private fun Tracks.externalSubtitleGroup(target: SubtitleActivationTarget): Tracks.Group? =
        groups
            .asSequence()
            .filter { group -> group.type == C.TRACK_TYPE_TEXT }
            .firstOrNull { group ->
                (0 until group.length).any { trackIndex ->
                    media3FormatIdMatchesTarget(
                        formatId = group.getTrackFormat(trackIndex).id,
                        targetId = target.media3SubtitleId(),
                    )
                }
            }

    private fun degradeToVideoOnlyAfterAudioFailure(): Boolean {
        val plan = lastPlan ?: return false
        val generation = playbackGeneration
        val resumeMs = exoPlayer.safePosition().coerceAtLeast(0L)
        audioDisabledForOutputFailure = true
        recoveringFromAudioOutputFailure = true
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        _playbackState.update { current ->
            current.copy(
                error = null,
                audioUnavailable = true,
            )
        }
        audioRecoveryJob?.cancel()
        audioRecoveryJob =
            scope.launch {
                if (!media3RecoveryIsCurrent(released, generation, playbackGeneration, lastPlan === plan)) return@launch
                subtitleActivationConfirmation.beginForPlan(plan, plan.subtitleAsset)
                exoPlayer.stop()
                exoPlayer.setMediaItem(plan.toMediaItem(plan.subtitleAsset))
                exoPlayer.seekTo(resumeMs)
                exoPlayer.prepare()
                if (playIntent && playbackFocusAdmitted) exoPlayer.play()
            }
        return true
    }

    private fun resetAudioOutputFailureForFreshMediaItem() {
        audioDisabledForOutputFailure = false
        recoveringFromAudioOutputFailure = false
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
    }

    private fun enableAudioAfterManualSelection() {
        if (!audioDisabledForOutputFailure && !_playbackState.value.audioUnavailable) {
            return
        }
        audioDisabledForOutputFailure = false
        recoveringFromAudioOutputFailure = false
        exoPlayer.trackSelectionParameters =
            exoPlayer
                .trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        _playbackState.update { current ->
            current.copy(audioUnavailable = false)
        }
    }

    private fun failPlayback(error: PlaybackError) {
        recoveringFromAudioOutputFailure = false
        performanceTracker.onPlaybackStatus(prepareGeneration, PlaybackStatus.Failed)
        publishPerformanceDiagnostics()
        _playbackState.update { current ->
            current.copy(
                status = PlaybackStatus.Failed,
                positionMs = exoPlayer.safePosition(),
                durationMs = exoPlayer.safeDuration(),
                bufferedPositionMs = exoPlayer.safeBufferedPosition(),
                error = error,
                audioUnavailable = false,
            )
        }
        stopPositionTicker()
        val detachedOfflineLease = offlineLeaseHolder.detach()
        if (lastPlan?.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
            exoPlayer.stop()
            offlinePath = null
            offlineSidecarPath = null
        }
        detachedOfflineLease?.release()
    }

    private fun logTerminalError(
        error: PlaybackException,
        category: PlaybackError,
        outcome: PlaybackTerminalOutcome,
        retryAttempted: Boolean,
        degradationAttempted: Boolean,
    ) {
        val plan = lastPlan
        val diagnostics = _runtimeDiagnostics.value
        controllerLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.TerminalError,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    exceptionType = error.playbackExceptionType(),
                    errorCategory = category,
                    terminalOutcome = outcome,
                    prepareSequence = prepareGeneration,
                    sessionSequence = plan?.diagnosticSessionSequence ?: playbackGeneration,
                    retryAttempted = retryAttempted,
                    degradationAttempted = degradationAttempted,
                    nativeCode = error.errorCode.toLong(),
                    streamMode = plan?.streamMode,
                    sourceBitrateBps = plan?.sourceBitrateBps,
                    videoDecoderName = diagnostics.videoDecoderName,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    runtimeVideoFrameRate = diagnostics.videoFrameRate,
                    bandwidthEstimateBps = diagnostics.bandwidthEstimateBps,
                    decoderDroppedVideoFrames = diagnostics.decoderDroppedVideoFrames,
                    outputDroppedVideoFrames = diagnostics.outputDroppedVideoFrames,
                    requestCapBitrateBps = plan?.maxStreamingBitrate,
                    effectiveTranscodeCapBitrateBps = plan?.effectiveTranscodeMaxStreamingBitrate,
                ),
            )
        }
    }

    private fun PlaybackException.isAudioOutputFailure(): Boolean =
        when (errorCode) {
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            -> true
            else -> false
        }

    private fun classifyPlaybackError(error: PlaybackException): PlaybackError =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> PlaybackError.Decoder

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            -> PlaybackError.Network

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> PlaybackError.UnsupportedMedia

            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            -> PlaybackError.Drm

            else -> PlaybackError.Unknown
        }

    private fun PlaybackStatus.allowsAudioUnavailable(): Boolean =
        this == PlaybackStatus.Playing ||
            this == PlaybackStatus.Paused ||
            this == PlaybackStatus.Buffering

    private fun PlaybackPlan.toMediaItem(subtitleAsset: SubtitleAsset?): MediaItem {
        val planned = plannedSubtitle
        val resolvedUri =
            if (streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                Uri.fromFile(File(checkNotNull(offlinePath)))
            } else {
                Uri.parse(streamUrl)
            }
        val builder =
            MediaItem
                .Builder()
                .setMediaId(prepareGeneration.toString())
                .setUri(resolvedUri)
        streamMimeType?.let(builder::setMimeType)
        val offlineSubtitlePath = offlineSidecarPath
        if (subtitleAsset != null ||
            (streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline && offlineSubtitlePath != null)
        ) {
            val subtitle = subtitleAsset
            val target = subtitleActivationTarget?.takeIf { it.kind == LocalSubtitleKind.ExternalText }
            val uri =
                when {
                    offlineSubtitlePath != null && streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline ->
                        Uri.fromFile(File(offlineSubtitlePath))
                    subtitle is SubtitleAsset.JellyfinRemote -> Uri.parse(subtitle.url)
                    subtitle is SubtitleAsset.LocalFile ->
                        localSubtitleFileStore?.resolvePath(subtitle.fileId)?.let { path ->
                            Uri.fromFile(File(path))
                        }
                    else -> null
                }
            if (uri != null) {
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration
                            .Builder(uri)
                            .setId(target?.media3SubtitleId())
                            .setMimeType(subtitle?.mimeType ?: "text/vtt")
                            .setLanguage(subtitle?.language ?: (planned as? PlannedSubtitle.Track)?.embeddedTrack?.normalizedLanguage)
                            .setLabel(subtitle?.label ?: (planned as? PlannedSubtitle.Track)?.embeddedTrack?.label)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ),
                )
            }
        }
        return builder.build()
    }
}

private fun AnalyticsListener.EventTime.prepareEpoch(): Long? {
    if (timeline.isEmpty || windowIndex !in 0 until timeline.windowCount) return null
    return timeline
        .getWindow(windowIndex, Timeline.Window())
        .mediaItem
        .mediaId
        .toLongOrNull()
}

internal data class Media3TrackResolutionCandidate<T>(
    val value: T,
    val stableSourceIndex: Int?,
    val codec: String?,
    val language: String?,
    val label: String?,
    val supported: Boolean,
)

internal data class Media3AudioDecoderClassification(
    val reason: Media3AudioDecoderReason,
    val codec: String?,
)

internal fun classifyMedia3AudioDecoder(decoderName: String): Media3AudioDecoderClassification {
    val normalizedName = decoderName.trim().lowercase()
    return when {
        normalizedName.isEmpty() -> Media3AudioDecoderClassification(Media3AudioDecoderReason.Unknown, null)
        normalizedName.startsWith("ffmpeg") ->
            Media3AudioDecoderClassification(
                reason = Media3AudioDecoderReason.BundledFfmpeg,
                codec = "eac3".takeIf { normalizedName.endsWith("-eac3") },
            )
        else -> Media3AudioDecoderClassification(Media3AudioDecoderReason.Platform, null)
    }
}

internal fun media3AudioDecoderInitializedDiagnostic(
    decoderName: String,
    prepareSequence: Long,
    sessionSequence: Long,
): PlaybackDiagnostic {
    val classification = classifyMedia3AudioDecoder(decoderName)
    return PlaybackDiagnostic(
        stage = PlaybackDiagnosticStage.NativePlayer,
        event = PlaybackDiagnosticEvent.NativeLifecycle,
        platform = PlaybackDiagnosticPlatform.Android,
        backend = PlayerBackend.ExoPlayer,
        reason = classification.reason,
        codec = classification.codec,
        prepareSequence = prepareSequence,
        sessionSequence = sessionSequence,
    )
}

internal fun <T> resolveMedia3TrackCandidates(
    descriptor: com.jellyscope.core.domain.playback.PlannedEmbeddedTrack,
    trackKind: EmbeddedTrackKind,
    candidates: List<Media3TrackResolutionCandidate<T>>,
): NativeTrackResolution<T> {
    val eligible =
        when (trackKind) {
            EmbeddedTrackKind.Audio -> candidates.filter(Media3TrackResolutionCandidate<T>::supported)
            EmbeddedTrackKind.Subtitle -> candidates
        }
    if (trackKind == EmbeddedTrackKind.Audio && candidates.isNotEmpty() && eligible.isEmpty()) {
        return NativeTrackResolution(
            candidate = null,
            result = NativeTrackMappingResult.Unsupported,
            reason = com.jellyscope.core.domain.playback.NativeTrackMappingReason.UnsupportedCandidate,
        )
    }
    val resolution =
        resolveEmbeddedTrack(
            descriptor = descriptor,
            orderedCandidates =
                eligible.map { candidate ->
                    NativeTrackCandidate(
                        value = candidate,
                        stableSourceIndex = candidate.stableSourceIndex,
                        codec = candidate.codec,
                        language = candidate.language,
                        label = candidate.label,
                    )
                },
            trackKind = trackKind,
        )
    val candidate = resolution.candidate
    if (trackKind == EmbeddedTrackKind.Subtitle && resolution.result == NativeTrackMappingResult.Active && candidate?.supported != true) {
        return NativeTrackResolution(
            candidate = null,
            result = NativeTrackMappingResult.Unsupported,
            reason = com.jellyscope.core.domain.playback.NativeTrackMappingReason.UnsupportedCandidate,
        )
    }
    return NativeTrackResolution(
        candidate = candidate?.value,
        result = resolution.result,
        reason = resolution.reason,
    )
}

internal fun media3RuntimeDiagnosticsAfterVideoInfo(
    current: PlaybackRuntimeDiagnostics,
    decoderName: String? = null,
    width: Int? = null,
    height: Int? = null,
    frameRate: Double? = null,
): PlaybackRuntimeDiagnostics =
    current.copy(
        videoDecoderName = decoderName?.takeIf(String::isNotBlank) ?: current.videoDecoderName,
        videoWidth = if (width == null) current.videoWidth else width.takeIf { value -> value > 0 },
        videoHeight = if (height == null) current.videoHeight else height.takeIf { value -> value > 0 },
        videoFrameRate =
            if (frameRate == null) {
                current.videoFrameRate
            } else {
                frameRate.takeIf { value -> value.isFinite() && value > 0.0 }
            },
        droppedVideoFrames = current.droppedVideoFrames ?: 0L,
    )

internal fun media3RuntimeDiagnosticsAfterDroppedFrames(
    current: PlaybackRuntimeDiagnostics,
    droppedFrames: Int,
    elapsedMs: Long,
): PlaybackRuntimeDiagnostics =
    droppedFrames.coerceAtLeast(0).let { normalizedDroppedFrames ->
        current.copy(
            droppedVideoFrames = (current.droppedVideoFrames ?: 0L) + normalizedDroppedFrames,
            decoderDroppedVideoFrames =
                (current.decoderDroppedVideoFrames ?: 0L) + normalizedDroppedFrames,
            droppedVideoFramesPerSecond =
                DroppedFrameMeasurement
                    .create(
                        droppedFrames = normalizedDroppedFrames.toLong(),
                        intervalMs = elapsedMs,
                    )?.ratePerSecond,
        )
    }

private fun SubtitleActivationTarget.media3SubtitleId(): String = "jellyscope-subtitle-$requestId-$itemId-$streamIndex"

internal fun media3FormatIdMatchesTarget(
    formatId: String?,
    targetId: String,
): Boolean = formatId == targetId || formatId?.endsWith(":$targetId") == true

internal fun compareMedia3TrackIds(
    leftId: String?,
    rightId: String?,
    leftNativeOrder: Int,
    rightNativeOrder: Int,
): Int {
    val left = leftId.numericColonComponents()
    val right = rightId.numericColonComponents()
    if (left != null && right == null) return -1
    if (left == null && right != null) return 1
    if (left != null && right != null) {
        val componentCount = minOf(left.size, right.size)
        for (index in 0 until componentCount) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        val sizeComparison = left.size.compareTo(right.size)
        if (sizeComparison != 0) return sizeComparison
    }
    return leftNativeOrder.compareTo(rightNativeOrder)
}

internal fun String?.media3StableSourceIndex(): Int? =
    numericColonComponents()
        ?.lastOrNull()
        ?.takeIf { value -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ?.toInt()

private fun String?.numericColonComponents(): List<Long>? {
    val value = this?.takeIf { candidate -> candidate.isNotBlank() } ?: return null
    val components = value.split(':')
    if (components.any { component -> component.isEmpty() || component.any { character -> !character.isDigit() } }) return null
    return components.map { component -> component.toLongOrNull() ?: return null }
}

private const val POSITION_POLL_INTERVAL_MS = 1_000L
private const val MAX_AUDIO_TRACK_INIT_RETRIES = 3
private const val AUDIO_TRACK_INIT_RETRY_DELAY_MS = 400L
private const val TIMING_RECONFIGURE_DEBOUNCE_MS = 400L

internal fun media3ReadyPlaybackStatus(
    isPlaying: Boolean,
    playIntent: Boolean,
    initialAudioGate: Boolean,
): PlaybackStatus =
    when {
        isPlaying -> PlaybackStatus.Playing
        playIntent && initialAudioGate -> PlaybackStatus.Buffering
        else -> PlaybackStatus.Paused
    }

internal fun media3RecoveryIsCurrent(
    released: Boolean,
    recoveryGeneration: Long,
    activeGeneration: Long,
    samePlan: Boolean,
): Boolean = !released && recoveryGeneration == activeGeneration && samePlan

private val controllerLogger =
    diagnosticLogger(DiagnosticTag.Media3PlayerController)
