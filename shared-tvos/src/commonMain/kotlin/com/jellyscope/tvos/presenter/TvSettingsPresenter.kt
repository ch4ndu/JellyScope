// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.model.MAX_AUTOPLAY_NEXT_DELAY_SECONDS
import com.jellyscope.core.domain.model.MIN_AUTOPLAY_NEXT_DELAY_SECONDS
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.playbackQualityChoices
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
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
    private val setRememberLastLibraryView: SetRememberLastLibraryViewAction,
    private val getLogCollectionState: GetLogCollectionStateUseCase,
    private val setLogCollectionEnabled: SetLogCollectionEnabledAction,
    getPlaybackInfoAtStartState: GetPlaybackInfoAtStartStateUseCase,
    setPlaybackInfoAtStartEnabled: SetPlaybackInfoAtStartEnabledAction,
    private val sendClientLogsAction: SendClientLogsAction,
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
    private val pendingRememberWrites = Channel<Boolean>(Channel.CONFLATED)
    private val pendingPlaybackInfoWrites = Channel<PlaybackInfoAtStartWrite>(Channel.CONFLATED)
    private val rememberLastLibraryView = observeRememberLastLibraryView()
    private var rememberLastLibraryViewIntent: Boolean? = null
    private var playbackInfoAtStartGeneration = 0L
    private var playbackInfoAtStartIntent: Boolean? = null
    private var failedPlaybackInfoAtStart: Boolean? = null
    private var confirmedPreferences: PlaybackPreferences? = null
    private var retryLoadJob: Job? = null

    init {
        scope.launch {
            rememberLastLibraryView.collect { enabled ->
                if (rememberLastLibraryViewIntent == null || rememberLastLibraryViewIntent == enabled) {
                    rememberLastLibraryViewIntent = null
                    _state.update { current -> current.copy(rememberLastLibraryView = enabled) }
                }
            }
        }
        scope.launch {
            getLogCollectionState().collect { enabled ->
                _state.update { current -> current.copy(diagnosticCollectionEnabled = enabled) }
            }
        }
        scope.launch {
            playbackInfoAtStart.collect { enabled ->
                if (playbackInfoAtStartIntent == null || playbackInfoAtStartIntent == enabled) {
                    _state.update { current -> current.copy(playbackInfoAtStartEnabled = enabled) }
                }
            }
        }
        scope.launch {
            getLogCollectionState.bufferSize().collect { size ->
                _state.update { current ->
                    current.copy(
                        diagnosticEntryCount = size.entryCount,
                        diagnosticByteCount = size.byteCount,
                    )
                }
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
                        _state.update { current -> current.copy(saveError = true) }
                    }
                }
            }
        }
        scope.launch {
            for (enabled in pendingRememberWrites) {
                try {
                    withContext(workDispatcher) { setRememberLastLibraryView(enabled) }
                    if (rememberLastLibraryViewIntent == enabled) {
                        rememberLastLibraryViewIntent = null
                        _state.update { current ->
                            current.copy(rememberLastLibraryView = rememberLastLibraryView.value)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
                        formatSafeFailureDiagnostic(
                            stage = "settings",
                            event = "remember-library-view-failed",
                            operation = DiagnosticOperation.SetRememberLastLibraryView,
                            throwable = exception,
                        )
                    }
                    while (pendingRememberWrites.tryReceive().isSuccess) {
                        // Keep the store's last successful value after a failed write.
                    }
                    rememberLastLibraryViewIntent = null
                    _state.update { current ->
                        current.copy(
                            rememberLastLibraryView = rememberLastLibraryView.value,
                            saveError = true,
                        )
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

    fun watchState(onChange: (TvSettingsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun setDefaultMaxBitrate(maxBitrateBps: Long?) {
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

    fun setDefaultQualityPolicy(policy: PlaybackQualityPolicy) {
        val normalized = policy.normalized()
        mutate { preferences ->
            preferences.copy(
                defaultQualityPolicy = normalized,
                defaultMaxBitrateBps = normalized.maxBitrateBps,
            )
        }
    }

    fun setDefaultQualityChoice(
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

    fun setPreferredAudioLanguage(code: String?) {
        mutate { preferences -> preferences.copy(preferredAudioLanguage = code) }
    }

    fun setPreferredSubtitleLanguage(code: String?) {
        mutate { preferences -> preferences.copy(preferredSubtitleLanguage = code) }
    }

    fun setPlaybackWarningsEnabled(enabled: Boolean) {
        mutate { preferences -> preferences.copy(playbackWarningsEnabled = enabled) }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        mutate { preferences -> preferences.copy(autoPlayNext = enabled) }
    }

    fun setStillWatchingPrompt(enabled: Boolean) {
        mutate { preferences -> preferences.copy(stillWatchingPrompt = enabled) }
    }

    fun setAutoPlayNextDelaySeconds(seconds: Int) {
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

    fun setRememberLastLibraryViewEnabled(enabled: Boolean) {
        if (state.value.isLoading || state.value.rememberLastLibraryView == enabled) {
            return
        }
        rememberLastLibraryViewIntent = enabled
        _state.update { current -> current.copy(rememberLastLibraryView = enabled, saveError = false) }
        pendingRememberWrites.trySend(enabled)
    }

    fun setSegmentPolicy(
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

    fun setDiagnosticCollectionEnabled(enabled: Boolean) {
        if (state.value.isLoading) {
            return
        }
        scope.launch {
            try {
                withContext(workDispatcher) { setLogCollectionEnabled(enabled) }
                _state.update { current -> current.copy(diagnosticPreferenceError = false) }
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
                _state.update { current -> current.copy(diagnosticPreferenceError = true) }
            }
        }
    }

    fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        if (!state.value.isSavingPlaybackInfoAtStart && state.value.playbackInfoAtStartEnabled == enabled) return
        playbackInfoAtStartGeneration += 1
        playbackInfoAtStartIntent = enabled
        failedPlaybackInfoAtStart = null
        _state.update { current ->
            current.copy(
                playbackInfoAtStartEnabled = enabled,
                isSavingPlaybackInfoAtStart = true,
                playbackInfoAtStartSaveError = false,
            )
        }
        pendingPlaybackInfoWrites.trySend(
            PlaybackInfoAtStartWrite(
                enabled = enabled,
                generation = playbackInfoAtStartGeneration,
            ),
        )
    }

    fun retryPlaybackInfoAtStart() {
        failedPlaybackInfoAtStart?.let(::setPlaybackInfoAtStartEnabled)
    }

    fun retryLoad() {
        if (!state.value.loadError || retryLoadJob?.isActive == true) return
        retryLoadJob =
            scope.launch {
                preferencesMutex.withLock {
                    if (state.value.loadError) load()
                }
            }
    }

    fun sendClientLogs() {
        scope.launch {
            _state.update { current -> current.copy(diagnosticSendResult = TvDiagnosticsSendResult.Sending) }
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
            _state.update { current ->
                current.copy(
                    diagnosticSendResult =
                        when (result) {
                            is SendClientLogsResult.Success -> TvDiagnosticsSendResult.Success
                            SendClientLogsResult.UploadDisallowed -> TvDiagnosticsSendResult.UploadDisallowed
                            SendClientLogsResult.Failure, null -> TvDiagnosticsSendResult.Failure
                        },
                )
            }
        }
    }

    private fun mutate(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        if (state.value.isLoading || state.value.loadError || confirmedPreferences == null) {
            return
        }
        val updated = transform(state.value.preferences).normalized()
        _state.update { current ->
            current.withPlaybackPreferences(updated).copy(saveError = false)
        }
        pendingWrites.trySend(updated)
    }

    private suspend fun load() {
        _state.update { current ->
            current.copy(
                isLoading = true,
                serverName = session.serverName,
                serverUrl = session.serverUrl,
                userName = session.userName,
            )
        }
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
            _state.update { current ->
                val failed = current.copy(isLoading = false, loadError = true)
                if (confirmed == null) failed else failed.withPlaybackPreferences(confirmed)
            }
            return
        }
        confirmedPreferences = preferences
        _state.update { current ->
            current.withPlaybackPreferences(preferences).copy(isLoading = false, loadError = false)
        }
    }

    private fun TvSettingsState.withPlaybackPreferences(preferences: PlaybackPreferences): TvSettingsState =
        copy(
            preferences = preferences,
            stillWatchingPrompt = preferences.stillWatchingPrompt,
            autoPlayNextDelaySeconds = preferences.autoPlayNextDelaySeconds,
            bitrateChoices = bitrateChoices(preferences.effectiveDefaultQualityPolicy()),
        )

    // Same shared quality ladder the in-player Quality menu renders, so a
    // default picked here always matches a confirmed rung during playback.
    private fun bitrateChoices(selected: PlaybackQualityPolicy): List<TvQualityChoice> =
        playbackQualityChoices(selected).map { option ->
            TvQualityChoice(
                maxBitrateBps = option.maxBitrateBps,
                resolutionHeight = option.resolutionHeight,
                isCustom = option.isCustom,
                selected = option.mode == selected.mode && option.maxBitrateBps == selected.maxBitrateBps,
                mode = option.mode,
            )
        }

    private suspend fun writePlaybackInfoAtStart(request: PlaybackInfoAtStartWrite) {
        try {
            withContext(workDispatcher) { setPlaybackInfoAtStartEnabledAction(request.enabled) }
            if (request.generation == playbackInfoAtStartGeneration) {
                playbackInfoAtStartIntent = null
                failedPlaybackInfoAtStart = null
                _state.update { current ->
                    current.copy(
                        playbackInfoAtStartEnabled = playbackInfoAtStart.value,
                        isSavingPlaybackInfoAtStart = false,
                        playbackInfoAtStartSaveError = false,
                    )
                }
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
                _state.update { current ->
                    current.copy(
                        playbackInfoAtStartEnabled = playbackInfoAtStart.value,
                        isSavingPlaybackInfoAtStart = false,
                        playbackInfoAtStartSaveError = true,
                    )
                }
            }
        }
    }
}

private data class PlaybackInfoAtStartWrite(
    val enabled: Boolean,
    val generation: Long,
)
