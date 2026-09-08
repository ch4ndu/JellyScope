// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.UpdateDownloadedPlaybackAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.buildOfflinePlaybackPlan
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class TvOfflinePlaybackPresenter(
    private val request: TvOfflinePlaybackRequest,
    val startWithPlaybackInfoOverlay: Boolean,
    private val getOfflinePlaybackPlan: GetOfflinePlaybackPlanUseCase,
    private val updateDownloadedPlayback: UpdateDownloadedPlaybackAction,
    private val deviceProfileProvider: com.jellyscope.core.domain.playback.DeviceProfileProvider,
    private val playerControllerFactory: () -> PlayerController,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val accountIdentity = request.session.accountIdentity()
    private val _state = MutableStateFlow(TvOfflinePlaybackUiState())
    val state: StateFlow<TvOfflinePlaybackUiState> = _state.asStateFlow()

    internal val surfaceController: PlayerController?
        get() = installedController

    private val persistenceScope = CoroutineScope(SupervisorJob() + dispatchers.work)
    private val persistenceCommands = Channel<TvOfflinePersistenceCommand>(Channel.UNLIMITED)
    private var installedController: PlayerController? = null
    private var controllerOwned = false
    private var preparedSource: TvOfflinePreparedSource? = null
    private var plan: PlaybackPlan? = null
    private var startJob: Job? = null
    private var observeJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var reprepareJob: Job? = null
    private var started = false
    private var closed = false
    private var launchGeneration = 0L
    private var playerIdentity = 0L
    private var audioRequestId = 0L
    private var subtitleRequestId = 0L
    private var lastPersistedPositionMs = 0L
    private var completedPersisted = false
    private var initialExternalSubtitle: PlannedSubtitle.Track? = null
    private var subtitleReprepareGeneration = 0L
    private var reprepareIntent: TvOfflineReprepareIntent? = null

    init {
        persistenceScope.launch {
            for (command in persistenceCommands) {
                when (command) {
                    is TvOfflinePersistenceCommand.Update ->
                        runCatching {
                            updateDownloadedPlayback(
                                accountIdentity = command.accountIdentity,
                                downloadId = command.downloadId,
                                expectedAttemptGeneration = command.attemptGeneration,
                                resumePositionMs = command.positionMs,
                                watched = command.watched,
                            )
                        }
                    TvOfflinePersistenceCommand.Close -> break
                }
            }
            persistenceCommands.close()
            persistenceScope.cancel()
        }
    }

    fun watchState(onChange: (TvOfflinePlaybackUiState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun start() {
        if (started || closed) return
        started = true
        val expectedGeneration = ++launchGeneration
        startJob =
            scope.launch {
                val candidateOwner = TvOfflineControllerCandidateOwner()
                try {
                    val downloadId = runCatching { DownloadId(request.downloadId) }.getOrNull()
                    val offlineRecord =
                        downloadId?.let { id ->
                            withContext(dispatchers.work) {
                                getOfflinePlaybackPlan(accountIdentity, id)
                            }
                        }
                    if (offlineRecord == null || offlineRecord.businessKey.accountIdentity != accountIdentity) {
                        publishFailure(PlaybackError.OfflineArtifactUnavailable)
                        return@launch
                    }
                    val requiredBackend =
                        withContext(dispatchers.work) {
                            deviceProfileProvider.requiredOfflineBackend to deviceProfileProvider.availableBackends
                        }
                    if (
                        requiredBackend.first != PlayerBackend.VlcKit ||
                        PlayerBackend.VlcKit !in requiredBackend.second
                    ) {
                        publishFailure(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
                        return@launch
                    }
                    val source =
                        withContext(dispatchers.work) {
                            prepareOfflineSource(
                                record = offlineRecord,
                                accountIdentity = accountIdentity,
                                restart = request.restart,
                            )
                        }
                    val offlinePlan = decorateOfflinePlan(source.initialPlan, offlineRecord)
                    val candidate =
                        withContext(dispatchers.work) {
                            playerControllerFactory().also(candidateOwner::acquire)
                        }
                    if (candidate.activeBackend != PlayerBackend.VlcKit) {
                        publishFailure(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
                        return@launch
                    }
                    when (val result = candidate.prepareOffline(offlinePlan)) {
                        OfflinePrepareResult.Started -> Unit
                        is OfflinePrepareResult.Unavailable -> {
                            publishFailure(result.error)
                            return@launch
                        }
                    }
                    applyOfflineSelections(candidate, offlinePlan)
                    currentCoroutineContext().ensureActive()
                    if (closed || expectedGeneration != launchGeneration) return@launch
                    candidateOwner.transferTo { controller ->
                        installedController = controller
                        controllerOwned = true
                    }
                    preparedSource = source
                    plan = offlinePlan
                    initialExternalSubtitle =
                        (offlinePlan.plannedSubtitle as? PlannedSubtitle.Track)
                            ?.takeIf { subtitle -> subtitle.deliveryMethod == SubtitleDeliveryMethod.External }
                    lastPersistedPositionMs = offlinePlan.startPositionMs
                    playerIdentity += 1L
                    observeController()
                    installedController?.play()
                    publish(installedController?.playbackState?.value ?: return@launch)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    if (!closed && expectedGeneration == launchGeneration) {
                        publishFailure(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
                    }
                } finally {
                    candidateOwner.releaseUntransferred()
                }
            }
    }

    fun retry() {
        if (closed) return
        startJob?.cancel()
        observeJob?.cancel()
        diagnosticsJob?.cancel()
        reprepareJob?.cancel()
        subtitleReprepareGeneration += 1L
        releaseController()
        preparedSource = null
        plan = null
        initialExternalSubtitle = null
        completedPersisted = false
        reprepareIntent = null
        started = false
        _state.value = TvOfflinePlaybackUiState()
        start()
    }

    fun play() {
        reprepareIntent = reprepareIntent?.copy(playWhenReady = true)
        installedController?.play()
    }

    fun pause() {
        reprepareIntent = reprepareIntent?.copy(playWhenReady = false)
        val controller = installedController ?: return
        controller.pause()
        val positionMs = reprepareIntent?.positionMs ?: controller.playbackState.value.positionMs
        persistProgress(positionMs)
    }

    fun togglePlayPause() {
        reprepareIntent?.let { intent ->
            if (intent.playWhenReady) pause() else play()
            return
        }
        when (installedController?.playbackState?.value?.status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> pause()
            PlaybackStatus.Paused, PlaybackStatus.Loading -> play()
            else -> Unit
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = installedController ?: return
        val duration = controller.playbackState.value.durationMs ?: state.value.durationMs
        val target = positionMs.coerceAtLeast(0L).let { value -> duration?.let(value::coerceAtMost) ?: value }
        reprepareIntent = reprepareIntent?.copy(positionMs = target)
        controller.seekTo(target)
        persistProgress(target)
    }

    fun selectAudio(streamIndex: Int) {
        val controller = installedController ?: return
        val currentPlan = plan ?: return
        val descriptor =
            currentPlan.embeddedAudioTracks.firstOrNull { track -> track.jellyfinStreamIndex == streamIndex }
                ?: return
        val target = AudioActivationTarget(++audioRequestId, currentPlan.itemId, streamIndex)
        plan = currentPlan.copy(selectedAudioStreamIndex = streamIndex, audioActivationTarget = target)
        controller.selectEmbeddedAudio(EmbeddedAudioSelection(target, descriptor))
        publish(controller.playbackState.value)
    }

    fun selectSubtitle(streamIndex: Int?) {
        val controller = installedController ?: return
        val currentPlan = plan ?: return
        val replacementRequired = reprepareIntent != null
        reprepareJob?.cancel()
        reprepareJob = null
        subtitleReprepareGeneration += 1L
        if (streamIndex == null) {
            val updatedPlan =
                currentPlan.copy(
                    selectedSubtitleStreamIndex = null,
                    subtitleActivationTarget = null,
                    plannedSubtitle = PlannedSubtitle.Off,
                )
            if (replacementRequired) {
                reprepareWithSubtitle(updatedPlan)
                return
            }
            plan = updatedPlan
            controller.selectEmbeddedSubtitle(null)
            publish(controller.playbackState.value)
            return
        }
        if (streamIndex == OFFLINE_LOCAL_SUBTITLE_CHOICE_KEY) {
            val localSelection =
                (preparedSource?.record?.request?.subtitleSelection as? DownloadSubtitleSelection.ExternalTextSidecar)
                    ?: return
            reprepareWithSubtitle(
                currentPlan.copy(
                    selectedSubtitleStreamIndex = null,
                    subtitleActivationTarget = null,
                    plannedSubtitle =
                        PlannedSubtitle.LocalAsset(
                            assetId = localSelection.localAssetId,
                            kind = SubtitleKind.Text,
                        ),
                ),
            )
            return
        }
        val external = initialExternalSubtitle?.takeIf { subtitle -> subtitle.streamIndex == streamIndex }
        if (external != null) {
            reprepareWithSubtitle(
                currentPlan.copy(
                    selectedSubtitleStreamIndex = streamIndex,
                    subtitleActivationTarget = null,
                    plannedSubtitle = external.copy(activationTarget = null),
                ),
            )
            return
        }
        val descriptor =
            currentPlan.embeddedSubtitleTracks.firstOrNull { track -> track.jellyfinStreamIndex == streamIndex }
                ?: return
        val target =
            SubtitleActivationTarget(
                requestId = ++subtitleRequestId,
                itemId = currentPlan.itemId,
                identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
                kind = LocalSubtitleKind.EmbeddedText,
            )
        val plannedSubtitle =
            PlannedSubtitle.Track(
                streamIndex = streamIndex,
                embeddedTrack = descriptor,
                deliveryMethod = SubtitleDeliveryMethod.Embed,
                kind = SubtitleKind.Text,
                activationTarget = target,
                normalizedFormat = descriptor.codec,
            )
        val updatedPlan =
            currentPlan.copy(
                selectedSubtitleStreamIndex = streamIndex,
                subtitleActivationTarget = target,
                plannedSubtitle = plannedSubtitle,
            )
        if (replacementRequired) {
            reprepareWithSubtitle(updatedPlan)
            return
        }
        plan = updatedPlan
        controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(target, descriptor))
        publish(controller.playbackState.value)
    }

    fun seekToChapter(index: Int) {
        val chapter = plan?.chapters?.getOrNull(index) ?: return
        seekTo(chapter.startMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val controller = installedController ?: return
        if (!speed.isFinite() || speed !in tvPlaybackSpeedValues) return
        plan = plan?.copy(playbackSpeed = speed)
        controller.setPlaybackSpeed(speed)
        publish(controller.playbackState.value.copy(playbackSpeed = speed))
    }

    fun setDiagnosticsVisible(visible: Boolean) {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        if (!visible) {
            _state.update { current -> current.copy(diagnostics = emptyList()) }
            return
        }
        val controller = installedController ?: return
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

    override fun close() {
        if (closed) return
        closed = true
        launchGeneration += 1L
        startJob?.cancel()
        observeJob?.cancel()
        diagnosticsJob?.cancel()
        reprepareJob?.cancel()
        val currentState = installedController?.playbackState?.value
        if (!completedPersisted && currentState != null) {
            persistProgress(reprepareIntent?.positionMs ?: currentState.positionMs)
        }
        persistenceCommands.trySend(TvOfflinePersistenceCommand.Close)
        releaseController()
        super.close()
    }

    private fun observeController() {
        val controller = installedController ?: return
        val expectedPlayerIdentity = playerIdentity
        observeJob =
            scope.launch {
                controller.playbackState.collect { playbackState ->
                    val source = preparedSource ?: return@collect
                    val currentPlan = plan ?: return@collect
                    val expectedReprepareGeneration = subtitleReprepareGeneration
                    val expectedReprepareIntent = reprepareIntent
                    val resumedFromReprepare = expectedReprepareIntent?.hasResumed(playbackState) == true
                    val contentPlaybackState = expectedReprepareIntent?.project(playbackState) ?: playbackState
                    val playerInstalled = controllerOwned
                    val backend = controller.activeBackend
                    val subtitleStyleSupported = controller.appliesSubtitleStyle
                    val projectedState =
                        withContext(dispatchers.work) {
                            source.project(
                                playbackState = contentPlaybackState,
                                plan = currentPlan,
                                playerInstalled = playerInstalled,
                                playerIdentity = expectedPlayerIdentity,
                                backend = backend,
                                subtitleStyleSupported = subtitleStyleSupported,
                            )
                        }
                    if (
                        closed ||
                        playerIdentity != expectedPlayerIdentity ||
                        installedController !== controller ||
                        preparedSource !== source ||
                        plan !== currentPlan ||
                        subtitleReprepareGeneration != expectedReprepareGeneration ||
                        reprepareIntent !== expectedReprepareIntent
                    ) {
                        return@collect
                    }
                    if (resumedFromReprepare) {
                        reprepareIntent = null
                    }
                    onPlaybackState(contentPlaybackState, projectedState)
                }
            }
    }

    private fun onPlaybackState(
        playbackState: PlaybackState,
        projectedState: TvOfflinePlaybackUiState,
    ) {
        if (playbackState.status == PlaybackStatus.Completed && !completedPersisted) {
            reprepareIntent = null
            completedPersisted = true
            persistProgress(positionMs = 0L, watched = true)
        } else if (
            playbackState.status == PlaybackStatus.Playing &&
            abs(playbackState.positionMs - lastPersistedPositionMs) >= OFFLINE_PROGRESS_INTERVAL_MS
        ) {
            persistProgress(playbackState.positionMs)
        } else if (playbackState.status == PlaybackStatus.Paused || playbackState.status == PlaybackStatus.Failed) {
            persistProgress(playbackState.positionMs)
        }
        publish(projectedState)
    }

    private fun reprepareWithSubtitle(updatedPlan: PlaybackPlan) {
        val controller = installedController ?: return
        val source = preparedSource ?: return
        val currentPlaybackState = controller.playbackState.value
        val desiredIntent =
            reprepareIntent
                ?: TvOfflineReprepareIntent(
                    positionMs = currentPlaybackState.positionMs,
                    playWhenReady = currentPlaybackState.status != PlaybackStatus.Paused,
                )
        val preparedPlan =
            decorateOfflinePlan(updatedPlan, source.record)
                .copy(startPositionMs = desiredIntent.positionMs)
        reprepareJob?.cancel()
        val expectedGeneration = ++subtitleReprepareGeneration
        reprepareIntent = desiredIntent.copy(replacementMediaPrepared = false)
        reprepareJob =
            scope.launch {
                _state.update { current -> current.copy(phase = TvPlaybackPhase.Loading) }
                when (val result = controller.prepareOffline(preparedPlan)) {
                    OfflinePrepareResult.Started -> {
                        currentCoroutineContext().ensureActive()
                        val currentIntent = reprepareIntent
                        if (
                            closed ||
                            expectedGeneration != subtitleReprepareGeneration ||
                            installedController !== controller ||
                            preparedSource !== source ||
                            currentIntent == null
                        ) {
                            return@launch
                        }
                        val installedPlan = preparedPlan.copy(startPositionMs = currentIntent.positionMs)
                        plan = installedPlan
                        reprepareIntent = currentIntent.copy(replacementMediaPrepared = true)
                        applyOfflineSelections(controller, installedPlan)
                        controller.seekTo(currentIntent.positionMs)
                        if (currentIntent.playWhenReady) controller.play() else controller.pause()
                        publish(controller.playbackState.value)
                    }
                    is OfflinePrepareResult.Unavailable ->
                        if (!closed && expectedGeneration == subtitleReprepareGeneration) {
                            persistProgress(reprepareIntent?.positionMs ?: desiredIntent.positionMs)
                            publishFailure(result.error)
                        }
                }
            }
    }

    private fun decorateOfflinePlan(
        sourcePlan: PlaybackPlan,
        record: DownloadRecord,
    ): PlaybackPlan {
        val audioTarget =
            sourcePlan.selectedAudioStreamIndex
                ?.takeIf { streamIndex ->
                    sourcePlan.embeddedAudioTracks.any { track -> track.jellyfinStreamIndex == streamIndex }
                }?.let { streamIndex ->
                    AudioActivationTarget(
                        requestId = ++audioRequestId,
                        itemId = sourcePlan.itemId,
                        streamIndex = streamIndex,
                    )
                }
        val localAssetId =
            (record.request.subtitleSelection as? DownloadSubtitleSelection.ExternalTextSidecar)?.localAssetId
        val requestedSubtitle =
            when (val subtitle = sourcePlan.plannedSubtitle) {
                is PlannedSubtitle.Track ->
                    if (subtitle.deliveryMethod == SubtitleDeliveryMethod.External && localAssetId != null) {
                        PlannedSubtitle.LocalAsset(
                            assetId = localAssetId,
                            kind = SubtitleKind.Text,
                        )
                    } else {
                        subtitle
                    }
                else -> subtitle
            }
        val subtitleTarget =
            when (requestedSubtitle) {
                is PlannedSubtitle.LocalAsset ->
                    SubtitleActivationTarget(
                        requestId = ++subtitleRequestId,
                        itemId = sourcePlan.itemId,
                        identity = SubtitleActivationIdentity.LocalAsset(requestedSubtitle.assetId),
                        kind = LocalSubtitleKind.ExternalText,
                    )
                is PlannedSubtitle.Track ->
                    SubtitleActivationTarget(
                        requestId = ++subtitleRequestId,
                        itemId = sourcePlan.itemId,
                        identity = SubtitleActivationIdentity.JellyfinTrack(requestedSubtitle.streamIndex),
                        kind =
                            when {
                                requestedSubtitle.deliveryMethod == SubtitleDeliveryMethod.External ->
                                    LocalSubtitleKind.ExternalText
                                requestedSubtitle.kind == SubtitleKind.Bitmap -> LocalSubtitleKind.EmbeddedBitmap
                                else -> LocalSubtitleKind.EmbeddedText
                            },
                    )
                is PlannedSubtitle.Off, is PlannedSubtitle.Unavailable -> null
            }
        val plannedSubtitle =
            when (requestedSubtitle) {
                is PlannedSubtitle.LocalAsset -> requestedSubtitle.copy(activationTarget = subtitleTarget)
                is PlannedSubtitle.Track -> requestedSubtitle.copy(activationTarget = subtitleTarget)
                is PlannedSubtitle.Off, is PlannedSubtitle.Unavailable -> requestedSubtitle
            }
        return sourcePlan.copy(
            audioActivationTarget = audioTarget,
            selectedSubtitleStreamIndex =
                sourcePlan.selectedSubtitleStreamIndex.takeUnless { requestedSubtitle is PlannedSubtitle.LocalAsset },
            subtitleActivationTarget = subtitleTarget,
            plannedSubtitle = plannedSubtitle,
        )
    }

    private fun applyOfflineSelections(
        controller: PlayerController,
        playbackPlan: PlaybackPlan,
    ) {
        val audioTarget = playbackPlan.audioActivationTarget
        val audioDescriptor =
            playbackPlan.embeddedAudioTracks.firstOrNull { track ->
                track.jellyfinStreamIndex == playbackPlan.selectedAudioStreamIndex
            }
        if (audioTarget != null && audioDescriptor != null) {
            controller.selectEmbeddedAudio(EmbeddedAudioSelection(audioTarget, audioDescriptor))
        }
        when (val subtitle = playbackPlan.plannedSubtitle) {
            is PlannedSubtitle.Off -> controller.selectEmbeddedSubtitle(null)
            is PlannedSubtitle.Track -> {
                val target = subtitle.activationTarget
                val descriptor = subtitle.embeddedTrack
                if (
                    subtitle.deliveryMethod != SubtitleDeliveryMethod.External &&
                    target != null &&
                    descriptor != null
                ) {
                    controller.selectEmbeddedSubtitle(EmbeddedSubtitleSelection(target, descriptor))
                }
            }
            is PlannedSubtitle.LocalAsset, is PlannedSubtitle.Unavailable -> Unit
        }
    }

    private fun persistProgress(
        positionMs: Long,
        watched: Boolean? = null,
    ) {
        val currentRecord = preparedSource?.record ?: return
        val normalizedPosition = positionMs.coerceAtLeast(0L)
        lastPersistedPositionMs = normalizedPosition
        persistenceCommands.trySend(
            TvOfflinePersistenceCommand.Update(
                accountIdentity = accountIdentity,
                downloadId = currentRecord.downloadId,
                attemptGeneration = currentRecord.attemptGeneration,
                positionMs = normalizedPosition,
                watched = watched,
            ),
        )
    }

    private fun publish(playbackState: PlaybackState) {
        val source = preparedSource
        val currentPlan = plan
        val controller = installedController
        if (source == null || currentPlan == null || controller == null) return
        val contentPlaybackState = reprepareIntent?.project(playbackState) ?: playbackState
        publish(
            source.project(
                playbackState = contentPlaybackState,
                plan = currentPlan,
                playerInstalled = controllerOwned,
                playerIdentity = playerIdentity,
                backend = controller.activeBackend,
                subtitleStyleSupported = controller.appliesSubtitleStyle,
            ),
        )
    }

    private fun publish(projectedState: TvOfflinePlaybackUiState) {
        _state.update { current ->
            projectedState.copy(diagnostics = current.diagnostics)
        }
    }

    private fun publishFailure(error: PlaybackError) {
        _state.update { current ->
            current.copy(
                playerInstalled = installedController != null,
                playerIdentity = playerIdentity,
                phase = TvPlaybackPhase.Failed,
                error = error,
            )
        }
    }

    private fun releaseController() {
        val controller = installedController
        installedController = null
        if (!controllerOwned || controller == null) return
        controllerOwned = false
        controller.stop()
        controller.release()
    }
}

private data class TvOfflineReprepareIntent(
    val positionMs: Long,
    val playWhenReady: Boolean,
    val replacementMediaPrepared: Boolean = false,
) {
    fun hasResumed(playbackState: PlaybackState): Boolean =
        replacementMediaPrepared &&
            playWhenReady &&
            playbackState.status == PlaybackStatus.Playing &&
            (positionMs == 0L || playbackState.positionMs >= positionMs)

    fun project(playbackState: PlaybackState): PlaybackState =
        if (playbackState.status == PlaybackStatus.Completed || hasResumed(playbackState)) {
            playbackState
        } else {
            playbackState.copy(
                positionMs = positionMs,
                bufferedPositionMs = maxOf(playbackState.bufferedPositionMs, positionMs),
            )
        }
}

private data class TvOfflinePreparedSource(
    val record: DownloadRecord,
    val initialPlan: PlaybackPlan,
    val baseState: TvOfflinePlaybackUiState,
    val audioTracksByStreamIndex: Map<Int?, List<TvTrackChoice>>,
    val subtitleTracksByChoiceKey: Map<Int?, List<TvTrackChoice>>,
    val localSubtitleChoiceKey: Int?,
    val playbackSpeedChoicesBySpeed: Map<Float, List<TvPlaybackSpeedChoice>>,
) {
    fun project(
        playbackState: PlaybackState,
        plan: PlaybackPlan,
        playerInstalled: Boolean,
        playerIdentity: Long,
        backend: PlayerBackend,
        subtitleStyleSupported: Boolean,
    ): TvOfflinePlaybackUiState {
        val selectedAudio =
            (playbackState.audioActivation as? AudioActivationState.Active)
                ?.target
                ?.takeIf { target -> target == plan.audioActivationTarget && target.itemId == plan.itemId }
                ?.streamIndex
        val selectedSubtitleTarget =
            (playbackState.subtitleActivation as? SubtitleActivationState.Active)
                ?.target
                ?.takeIf { target -> target == plan.subtitleActivationTarget && target.itemId == plan.itemId }
        val selectedSubtitleChoiceKey =
            when (val identity = selectedSubtitleTarget?.identity) {
                is SubtitleActivationIdentity.JellyfinTrack -> identity.streamIndex
                is SubtitleActivationIdentity.LocalAsset ->
                    localSubtitleChoiceKey.takeIf {
                        identity.assetId ==
                            (record.request.subtitleSelection as? DownloadSubtitleSelection.ExternalTextSidecar)
                                ?.localAssetId
                    }
                null -> null
            }
        return baseState.copy(
            playerInstalled = playerInstalled,
            playerIdentity = playerIdentity,
            backend = backend,
            phase =
                when (playbackState.status) {
                    PlaybackStatus.Failed -> TvPlaybackPhase.Failed
                    PlaybackStatus.Completed -> TvPlaybackPhase.Completed
                    PlaybackStatus.Idle, PlaybackStatus.Loading -> TvPlaybackPhase.Loading
                    else -> TvPlaybackPhase.Active
                },
            status = playbackState.status,
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs ?: baseState.durationMs,
            bufferedPositionMs = playbackState.bufferedPositionMs,
            audioTracks = audioTracksByStreamIndex[selectedAudio] ?: audioTracksByStreamIndex[null].orEmpty(),
            subtitleTracks =
                subtitleTracksByChoiceKey[selectedSubtitleChoiceKey]
                    ?: subtitleTracksByChoiceKey[null].orEmpty(),
            playbackSpeed = playbackState.playbackSpeed,
            playbackSpeedChoices = playbackSpeedChoicesBySpeed[playbackState.playbackSpeed] ?: baseState.playbackSpeedChoices,
            subtitleStyle = playbackState.subtitleStyle,
            subtitleStyleSupported = subtitleStyleSupported,
            error = playbackState.error,
        )
    }
}

private fun prepareOfflineSource(
    record: DownloadRecord,
    accountIdentity: AccountIdentity,
    restart: Boolean,
): TvOfflinePreparedSource {
    val snapshot = record.request.snapshot
    val plan =
        buildOfflinePlaybackPlan(
            snapshot = snapshot,
            itemId = record.businessKey.itemId,
            mediaSourceId = record.businessKey.mediaSourceId,
            downloadId = record.downloadId,
            attemptGeneration = record.attemptGeneration,
            artifactKind = record.request.artifactKind,
            accountIdentity = accountIdentity,
            startPositionTicks = 0L,
            localResumePositionMs = if (restart) 0L else record.localResumePositionMs,
        )
    val localSubtitleChoiceKey =
        OFFLINE_LOCAL_SUBTITLE_CHOICE_KEY.takeIf {
            record.request.subtitleSelection is DownloadSubtitleSelection.ExternalTextSidecar
        }
    val audioStreamIndices = snapshot.offlineAudioChoices(null).map(TvTrackChoice::streamIndex)
    val subtitleChoiceKeys =
        snapshot
            .offlineSubtitleChoices(
                selectedChoiceKey = null,
                localSubtitleChoiceKey = localSubtitleChoiceKey,
            ).map(TvTrackChoice::streamIndex)
    val audioTracksByStreamIndex =
        (listOf<Int?>(null) + audioStreamIndices).associateWith { streamIndex ->
            snapshot.offlineAudioChoices(streamIndex)
        }
    val subtitleTracksByChoiceKey =
        (listOf<Int?>(null) + subtitleChoiceKeys).associateWith { choiceKey ->
            snapshot.offlineSubtitleChoices(
                selectedChoiceKey = choiceKey,
                localSubtitleChoiceKey = localSubtitleChoiceKey,
            )
        }
    val playbackSpeedChoicesBySpeed =
        tvPlaybackSpeedValues.associateWith { speed -> tvPlaybackSpeedChoices(speed) }
    val baseState =
        TvOfflinePlaybackUiState(
            itemId = plan.itemId,
            mediaSourceId = plan.mediaSourceId,
            title = snapshot.title,
            seriesName = snapshot.seriesName,
            episodeLabel = snapshot.episodeLabel,
            positionMs = plan.startPositionMs,
            durationMs = snapshot.durationMs,
            chapters = plan.chapters.map { chapter -> TvChapter(chapter.name, chapter.startMs) },
            audioTracks =
                audioTracksByStreamIndex[null].orEmpty(),
            subtitleTracks =
                subtitleTracksByChoiceKey[null].orEmpty(),
            playbackSpeed = plan.playbackSpeed,
            playbackSpeedChoices =
                playbackSpeedChoicesBySpeed[plan.playbackSpeed] ?: tvPlaybackSpeedChoices(plan.playbackSpeed),
        )
    return TvOfflinePreparedSource(
        record = record,
        initialPlan = plan,
        baseState = baseState,
        audioTracksByStreamIndex = audioTracksByStreamIndex,
        subtitleTracksByChoiceKey = subtitleTracksByChoiceKey,
        localSubtitleChoiceKey = localSubtitleChoiceKey,
        playbackSpeedChoicesBySpeed = playbackSpeedChoicesBySpeed,
    )
}

private sealed interface TvOfflinePersistenceCommand {
    data class Update(
        val accountIdentity: AccountIdentity,
        val downloadId: DownloadId,
        val attemptGeneration: Long,
        val positionMs: Long,
        val watched: Boolean?,
    ) : TvOfflinePersistenceCommand

    data object Close : TvOfflinePersistenceCommand
}

private class TvOfflineControllerCandidateOwner {
    private var controller: PlayerController? = null

    fun acquire(candidate: PlayerController) {
        check(controller == null) { "Offline controller candidate is already owned." }
        controller = candidate
    }

    fun transferTo(block: (PlayerController) -> Unit) {
        val candidate = checkNotNull(controller) { "Offline controller candidate is not owned." }
        block(candidate)
        controller = null
    }

    fun releaseUntransferred() {
        val candidate = controller
        controller = null
        candidate?.release()
    }
}

private const val OFFLINE_PROGRESS_INTERVAL_MS = 5_000L
private const val OFFLINE_LOCAL_SUBTITLE_CHOICE_KEY = -1
