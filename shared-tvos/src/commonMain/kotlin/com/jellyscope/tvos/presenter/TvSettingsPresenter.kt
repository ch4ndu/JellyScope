// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.PreparePlaybackTimelineAction
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SendPlaybackTimelineAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetPlayerClockEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.model.MAX_AUTOPLAY_NEXT_DELAY_SECONDS
import com.jellyscope.core.domain.model.MIN_AUTOPLAY_NEXT_DELAY_SECONDS
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PreparePlaybackTimelineResult
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendPlaybackTimelineResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerClockEnabledUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Playback-preferences settings. Mutations update state optimistically and
 * flow through a single serialized latest-value writer (the store itself is a
 * plain upsert, so unserialized rapid writes could land out of order). On a
 * persistence failure the writer atomically drops any pending snapshot,
 * reloads store truth, and raises [TvSettingsState.saveError].
 */
class TvSettingsPresenter(
    private val session: Session,
    private val getPlaybackPreferences: GetPlaybackPreferencesUseCase,
    private val savePlaybackPreferences: SavePlaybackPreferencesAction,
    observeRememberLastLibraryView: ObserveRememberLastLibraryViewUseCase,
    setRememberLastLibraryView: SetRememberLastLibraryViewAction,
    observePlayerClockEnabled: ObservePlayerClockEnabledUseCase,
    setPlayerClockEnabled: SetPlayerClockEnabledAction,
    private val getLogCollectionState: GetLogCollectionStateUseCase,
    private val setLogCollectionEnabled: SetLogCollectionEnabledAction,
    getPlaybackInfoAtStartState: GetPlaybackInfoAtStartStateUseCase,
    setPlaybackInfoAtStartEnabled: SetPlaybackInfoAtStartEnabledAction,
    private val sendClientLogsAction: SendClientLogsAction,
    private val preparePlaybackTimelineAction: PreparePlaybackTimelineAction,
    private val sendPlaybackTimelineAction: SendPlaybackTimelineAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val playbackInfoAtStart = getPlaybackInfoAtStartState()
    private val setPlaybackInfoAtStartEnabledAction = setPlaybackInfoAtStartEnabled
    private val _state =
        MutableStateFlow(
            TvSettingsState(playbackInfoAtStartEnabled = playbackInfoAtStart.value),
        )
    val state: StateFlow<TvSettingsState> = _state.asStateFlow()

    private val pendingWrites = Channel<PlaybackPreferences>(Channel.CONFLATED)
    private val preferencesMutex = Mutex()
    private val pendingPlaybackInfoWrites = Channel<PlaybackInfoAtStartWrite>(Channel.CONFLATED)
    private val rememberLastLibraryViewToggle =
        DeviceToggle(
            stored = observeRememberLastLibraryView(),
            write = setRememberLastLibraryView::invoke,
            operation = DiagnosticOperation.SetRememberLastLibraryView,
            failureEvent = "remember-library-view-failed",
            publish = { current, enabled -> current.copy(rememberLastLibraryView = enabled) },
        )
    private val playerClockToggle =
        DeviceToggle(
            stored = observePlayerClockEnabled(),
            write = setPlayerClockEnabled::invoke,
            operation = DiagnosticOperation.SetPlayerClockEnabled,
            failureEvent = "player-clock-failed",
            publish = { current, enabled -> current.copy(playerClockEnabled = enabled) },
        )
    private var playbackInfoAtStartGeneration = 0L
    private var playbackInfoAtStartIntent: Boolean? = null
    private var failedPlaybackInfoAtStart: Boolean? = null
    private var confirmedPreferences: PlaybackPreferences? = null
    private var retryLoadJob: Job? = null

    // The prepared timeline lives only inside this job; close() cancels it with the presenter scope.
    private var playbackTimelineJob: Job? = null

    init {
        rememberLastLibraryViewToggle.start()
        playerClockToggle.start()
        scope.launch {
            getLogCollectionState().collect { enabled ->
                reduce(TvSettingsChange.DiagnosticsObserved(enabled))
                if (!enabled) stopPlaybackTimelineForDisabledCollection()
            }
        }
        scope.launch {
            playbackInfoAtStart.collect { enabled ->
                if (playbackInfoAtStartIntent == null || playbackInfoAtStartIntent == enabled) {
                    reduce(TvSettingsChange.PlaybackInfoObserved(enabled))
                }
            }
        }
        scope.launch {
            getLogCollectionState.bufferSize().collect { size ->
                reduce(TvSettingsChange.LogSizeObserved(size.entryCount, size.byteCount))
            }
        }
        scope.launch {
            preferencesMutex.withLock { load() }
            for (snapshot in pendingWrites) {
                preferencesMutex.withLock {
                    try {
                        withContext(workDispatcher) {
                            savePlaybackPreferences(session.accountIdentity(), snapshot)
                        }
                        confirmedPreferences = snapshot
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                            formatSafeFailureDiagnostic(
                                stage = "settings",
                                event = "playback-preferences-failed",
                                operation = DiagnosticOperation.SavePlaybackPreferences,
                                throwable = exception,
                            )
                        }
                        while (pendingWrites.tryReceive().isSuccess) {
                            // Discard edits based on truth that failed to persist.
                        }
                        load()
                        reduce(TvSettingsChange.SaveFailed)
                    }
                }
            }
        }
        scope.launch {
            for (request in pendingPlaybackInfoWrites) {
                writePlaybackInfoAtStart(request)
            }
        }
    }

    private fun reduce(change: TvSettingsChange) = reduce { change }

    // Evaluate observed store values inside each atomic update attempt.
    private fun reduce(change: () -> TvSettingsChange) {
        _state.update { current -> reduceTvSettings(current, change()) }
    }

    fun accept(intent: TvSettingsIntent) {
        when (intent) {
            is TvSettingsIntent.SetDefaultMaxBitrate -> setDefaultMaxBitrate(intent.maxBitrateBps)
            is TvSettingsIntent.SetDefaultQualityPolicy -> setDefaultQualityPolicy(intent.policy)
            is TvSettingsIntent.SetDefaultQualityChoice -> setDefaultQualityChoice(intent.modeName, intent.maxBitrateBps)
            is TvSettingsIntent.SetPreferredAudioLanguage -> setPreferredAudioLanguage(intent.code)
            is TvSettingsIntent.SetPreferredSubtitleLanguage -> setPreferredSubtitleLanguage(intent.code)
            is TvSettingsIntent.SetPlaybackWarningsEnabled -> setPlaybackWarningsEnabled(intent.enabled)
            is TvSettingsIntent.SetAutoPlayNext -> setAutoPlayNext(intent.enabled)
            is TvSettingsIntent.SetStillWatchingPrompt -> setStillWatchingPrompt(intent.enabled)
            is TvSettingsIntent.SetAutoPlayNextDelaySeconds -> setAutoPlayNextDelaySeconds(intent.seconds)
            is TvSettingsIntent.SetRememberLastLibraryViewEnabled -> setRememberLastLibraryViewEnabled(intent.enabled)
            is TvSettingsIntent.SetPlayerClockEnabled -> setPlayerClockEnabled(intent.enabled)
            is TvSettingsIntent.SetSegmentPolicy -> setSegmentPolicy(intent.type, intent.policy)
            is TvSettingsIntent.SetDiagnosticCollectionEnabled -> setDiagnosticCollectionEnabled(intent.enabled)
            is TvSettingsIntent.SetPlaybackInfoAtStartEnabled -> setPlaybackInfoAtStartEnabled(intent.enabled)
            TvSettingsIntent.RetryPlaybackInfoAtStart -> retryPlaybackInfoAtStart()
            TvSettingsIntent.RetryLoad -> retryLoad()
            TvSettingsIntent.SendClientLogs -> sendClientLogs()
            TvSettingsIntent.SendPlaybackTimeline -> sendPlaybackTimeline()
        }
    }

    fun watchState(onChange: (TvSettingsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun setDefaultMaxBitrate(maxBitrateBps: Long?) {
        val policy =
            maxBitrateBps
                ?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
                ?: PlaybackQualityPolicy.Auto
        mutate { preferences ->
            preferences.copy(
                defaultQualityPolicy = policy,
                defaultMaxBitrateBps = policy.maxBitrateBps,
            )
        }
    }

    private fun setDefaultQualityPolicy(policy: PlaybackQualityPolicy) {
        val normalized = policy.normalized()
        mutate { preferences ->
            preferences.copy(
                defaultQualityPolicy = normalized,
                defaultMaxBitrateBps = normalized.maxBitrateBps,
            )
        }
    }

    private fun setDefaultQualityChoice(
        modeName: String,
        maxBitrateBps: Long?,
    ) {
        val policy =
            when (modeName) {
                PlaybackQualityMode.Original.name -> PlaybackQualityPolicy.Original
                PlaybackQualityMode.Fixed.name ->
                    maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
                else -> PlaybackQualityPolicy.Auto
            }
        setDefaultQualityPolicy(policy)
    }

    private fun setPreferredAudioLanguage(code: String?) {
        mutate { preferences -> preferences.copy(preferredAudioLanguage = code) }
    }

    private fun setPreferredSubtitleLanguage(code: String?) {
        mutate { preferences -> preferences.copy(preferredSubtitleLanguage = code) }
    }

    private fun setPlaybackWarningsEnabled(enabled: Boolean) {
        mutate { preferences -> preferences.copy(playbackWarningsEnabled = enabled) }
    }

    private fun setAutoPlayNext(enabled: Boolean) {
        mutate { preferences -> preferences.copy(autoPlayNext = enabled) }
    }

    private fun setStillWatchingPrompt(enabled: Boolean) {
        mutate { preferences -> preferences.copy(stillWatchingPrompt = enabled) }
    }

    private fun setAutoPlayNextDelaySeconds(seconds: Int) {
        mutate { preferences ->
            preferences.copy(
                autoPlayNextDelaySeconds =
                    seconds.coerceIn(
                        MIN_AUTOPLAY_NEXT_DELAY_SECONDS,
                        MAX_AUTOPLAY_NEXT_DELAY_SECONDS,
                    ),
            )
        }
    }

    private fun setRememberLastLibraryViewEnabled(enabled: Boolean) {
        rememberLastLibraryViewToggle.set(enabled, current = state.value.rememberLastLibraryView)
    }

    private fun setPlayerClockEnabled(enabled: Boolean) {
        playerClockToggle.set(enabled, current = state.value.playerClockEnabled)
    }

    private fun setSegmentPolicy(
        type: MediaSegmentType,
        policy: SegmentSkipPolicy,
    ) {
        mutate { preferences ->
            when (type) {
                MediaSegmentType.Intro -> preferences.copy(introSkip = policy)
                MediaSegmentType.Outro -> preferences.copy(outroSkip = policy)
                MediaSegmentType.Recap -> preferences.copy(recapSkip = policy)
                MediaSegmentType.Preview -> preferences.copy(previewSkip = policy)
                MediaSegmentType.Commercial -> preferences.copy(commercialSkip = policy)
                MediaSegmentType.Unknown -> preferences
            }
        }
    }

    private fun setDiagnosticCollectionEnabled(enabled: Boolean) {
        if (state.value.isLoading) {
            return
        }
        scope.launch {
            try {
                withContext(workDispatcher) { setLogCollectionEnabled(enabled) }
                reduce(TvSettingsChange.DiagnosticPreferenceChanged(false))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                    formatSafeFailureDiagnostic(
                        stage = "settings",
                        event = "diagnostic-preference-failed",
                        operation = DiagnosticOperation.SetLogCollectionEnabled,
                        throwable = exception,
                    )
                }
                reduce(TvSettingsChange.DiagnosticPreferenceChanged(true))
            }
        }
    }

    private fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        if (!state.value.isSavingPlaybackInfoAtStart && state.value.playbackInfoAtStartEnabled == enabled) return
        playbackInfoAtStartGeneration += 1
        playbackInfoAtStartIntent = enabled
        failedPlaybackInfoAtStart = null
        reduce(TvSettingsChange.PlaybackInfoSaveStarted(enabled))
        pendingPlaybackInfoWrites.trySend(
            PlaybackInfoAtStartWrite(
                enabled = enabled,
                generation = playbackInfoAtStartGeneration,
            ),
        )
    }

    private fun retryPlaybackInfoAtStart() {
        failedPlaybackInfoAtStart?.let(::setPlaybackInfoAtStartEnabled)
    }

    private fun retryLoad() {
        if (!state.value.loadError || retryLoadJob?.isActive == true) return
        retryLoadJob =
            scope.launch {
                preferencesMutex.withLock {
                    if (state.value.loadError) load()
                }
            }
    }

    private fun sendClientLogs() {
        scope.launch {
            reduce(TvSettingsChange.LogsStarted)
            val result =
                try {
                    withContext(workDispatcher) { sendClientLogsAction() }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                        formatSafeFailureDiagnostic(
                            stage = "settings",
                            event = "diagnostic-send-failed",
                            operation = DiagnosticOperation.SendClientLogs,
                            throwable = exception,
                        )
                    }
                    null
                }
            reduce(TvSettingsChange.LogsFinished(result))
        }
    }

    /** Prepares a timeline, then explicitly uploads it; tvOS has no local document picker. */
    private fun sendPlaybackTimeline() {
        if (playbackTimelineJob?.isActive == true) return
        playbackTimelineJob =
            scope.launch {
                publishPlaybackTimeline(TvPlaybackTimelineSendResult.Preparing)
                val prepared =
                    try {
                        withContext(workDispatcher) { preparePlaybackTimelineAction() }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logPlaybackTimelineFailure("prepare-failed", exception)
                        PreparePlaybackTimelineResult.Failure
                    }
                val timeline =
                    when (prepared) {
                        is PreparePlaybackTimelineResult.Ready -> prepared.timeline
                        PreparePlaybackTimelineResult.CollectionDisabled ->
                            return@launch finishPlaybackTimeline(TvPlaybackTimelineSendResult.CollectionDisabled, "collection-disabled")
                        PreparePlaybackTimelineResult.NoTimedEvents ->
                            return@launch finishPlaybackTimeline(TvPlaybackTimelineSendResult.NoTimedEvents, "no-timed-events")
                        PreparePlaybackTimelineResult.Failure ->
                            return@launch finishPlaybackTimeline(TvPlaybackTimelineSendResult.Failure, "prepare-failed")
                    }
                publishPlaybackTimeline(TvPlaybackTimelineSendResult.Sending)
                // The action makes the final collection check immediately before the repository call.
                val sent =
                    try {
                        withContext(workDispatcher) { sendPlaybackTimelineAction(timeline) }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logPlaybackTimelineFailure("send-failed", exception)
                        SendPlaybackTimelineResult.Failure
                    }
                when (sent) {
                    is SendPlaybackTimelineResult.Success ->
                        finishPlaybackTimeline(TvPlaybackTimelineSendResult.Success, "sent", filename = sent.filename)
                    SendPlaybackTimelineResult.CollectionDisabled ->
                        finishPlaybackTimeline(TvPlaybackTimelineSendResult.CollectionDisabled, "collection-disabled")
                    SendPlaybackTimelineResult.Expired -> finishPlaybackTimeline(TvPlaybackTimelineSendResult.Expired, "expired")
                    SendPlaybackTimelineResult.UploadDisallowed ->
                        finishPlaybackTimeline(TvPlaybackTimelineSendResult.UploadDisallowed, "upload-disallowed")
                    SendPlaybackTimelineResult.Failure -> finishPlaybackTimeline(TvPlaybackTimelineSendResult.Failure, "send-failed")
                }
            }
    }

    /**
     * Turning collection off drops a timeline that is still being prepared. An upload already
     * handed to the repository is irreversible and reports its real outcome.
     */
    private fun stopPlaybackTimelineForDisabledCollection() {
        if (state.value.playbackTimelineSendResult != TvPlaybackTimelineSendResult.Preparing) return
        playbackTimelineJob?.cancel()
        playbackTimelineJob = null
        finishPlaybackTimeline(TvPlaybackTimelineSendResult.CollectionDisabled, "collection-disabled")
    }

    /** One closed-literal record per terminal; never the timeline or the server filename. */
    private fun finishPlaybackTimeline(
        result: TvPlaybackTimelineSendResult,
        diagnosticResult: String,
        filename: String? = null,
    ) {
        diagnosticLogger(DiagnosticTag.PlaybackTimeline).i {
            "stage=playback-timeline event=send-finished result=$diagnosticResult"
        }
        publishPlaybackTimeline(result, filename)
    }

    private fun publishPlaybackTimeline(
        result: TvPlaybackTimelineSendResult,
        filename: String? = null,
    ) {
        reduce(TvSettingsChange.TimelineChanged(result, filename))
    }

    // Fixed stage/event and sanitized exception type only; never the timeline or a message.
    private fun logPlaybackTimelineFailure(
        event: String,
        exception: Throwable,
    ) {
        diagnosticLogger(DiagnosticTag.PlaybackTimeline).w {
            formatSafeFailureDiagnostic(stage = "playback-timeline", event = event, throwable = exception)
        }
    }

    private fun mutate(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        if (state.value.isLoading || state.value.loadError || confirmedPreferences == null) {
            return
        }
        val updated = transform(state.value.preferences).normalized()
        reduce(TvSettingsChange.PreferencesChanged(updated))
        pendingWrites.trySend(updated)
    }

    private suspend fun load() {
        reduce(TvSettingsChange.LoadStarted(session.serverName, session.serverUrl, session.userName))
        val preferences =
            try {
                withContext(workDispatcher) { getPlaybackPreferences(session.accountIdentity()) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                    formatSafeFailureDiagnostic(
                        stage = "settings",
                        event = "playback-preferences-failed",
                        operation = DiagnosticOperation.GetPlaybackPreferences,
                        throwable = exception,
                    )
                }
                null
            }?.normalized()
        if (preferences == null) {
            val confirmed = confirmedPreferences
            reduce(TvSettingsChange.LoadFailed(confirmed))
            return
        }
        confirmedPreferences = preferences
        reduce(TvSettingsChange.Loaded(preferences))
    }

    private suspend fun writePlaybackInfoAtStart(request: PlaybackInfoAtStartWrite) {
        try {
            withContext(workDispatcher) { setPlaybackInfoAtStartEnabledAction(request.enabled) }
            if (request.generation == playbackInfoAtStartGeneration) {
                playbackInfoAtStartIntent = null
                failedPlaybackInfoAtStart = null
                reduce { TvSettingsChange.PlaybackInfoSaved(playbackInfoAtStart.value) }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                formatSafeFailureDiagnostic(
                    stage = "settings",
                    event = "playback-info-at-start-failed",
                    operation = DiagnosticOperation.SetPlaybackInfoAtStartEnabled,
                    throwable = exception,
                )
            }
            if (request.generation == playbackInfoAtStartGeneration) {
                while (pendingPlaybackInfoWrites.tryReceive().isSuccess) {
                    // Recover from confirmed preference truth before another intent.
                }
                playbackInfoAtStartIntent = null
                failedPlaybackInfoAtStart = request.enabled
                reduce { TvSettingsChange.PlaybackInfoFailed(playbackInfoAtStart.value) }
            }
        }
    }

    // Device-wide switch: optimistic intent, serialized latest-value writes, store truth on failure.
    private inner class DeviceToggle(
        private val stored: StateFlow<Boolean>,
        private val write: suspend (Boolean) -> Unit,
        private val operation: DiagnosticOperation,
        private val failureEvent: String,
        private val publish: (TvSettingsState, Boolean) -> TvSettingsState,
    ) {
        private val pendingWrites = Channel<Boolean>(Channel.CONFLATED)
        private var intent: Boolean? = null

        fun start() {
            scope.launch {
                stored.collect { enabled ->
                    if (intent == null || intent == enabled) {
                        intent = null
                        reduce(TvSettingsChange.ToggleObserved(publish, enabled))
                    }
                }
            }
            scope.launch {
                for (enabled in pendingWrites) {
                    try {
                        withContext(workDispatcher) { write(enabled) }
                        if (intent == enabled) {
                            intent = null
                            reduce { TvSettingsChange.ToggleObserved(publish, stored.value) }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                            formatSafeFailureDiagnostic(
                                stage = "settings",
                                event = failureEvent,
                                operation = operation,
                                throwable = exception,
                            )
                        }
                        while (pendingWrites.tryReceive().isSuccess) {
                            // Keep the store's last successful value after a failed write.
                        }
                        intent = null
                        reduce { TvSettingsChange.ToggleFailed(publish, stored.value) }
                    }
                }
            }
        }

        fun set(
            enabled: Boolean,
            current: Boolean,
        ) {
            if (state.value.isLoading || current == enabled) {
                return
            }
            intent = enabled
            reduce(TvSettingsChange.ToggleChanged(publish, enabled))
            pendingWrites.trySend(enabled)
        }
    }
}

private data class PlaybackInfoAtStartWrite(
    val enabled: Boolean,
    val generation: Long,
)
