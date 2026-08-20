// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.playbackQualityChoices
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvLanguageChoice(
    val code: String,
)

data class TvSettingsState(
    val isLoading: Boolean = true,
    val serverName: String = "",
    val userName: String = "",
    val preferences: PlaybackPreferences = PlaybackPreferences(),
    val bitrateChoices: List<TvQualityChoice> = emptyList(),
    val languageChoices: List<TvLanguageChoice> = LANGUAGE_CHOICES,
    val saveError: Boolean = false,
    val diagnosticPreferenceError: Boolean = false,
    val diagnosticCollectionEnabled: Boolean = true,
    val diagnosticEntryCount: Int = 0,
    val diagnosticByteCount: Int = 0,
    val diagnosticSendResult: TvDiagnosticsSendResult = TvDiagnosticsSendResult.Idle,
)

enum class TvDiagnosticsSendResult {
    Idle,
    Sending,
    Success,
    UploadDisallowed,
    Failure,
}

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
    private val getLogCollectionState: GetLogCollectionStateUseCase,
    private val setLogCollectionEnabled: SetLogCollectionEnabledAction,
    private val sendClientLogsAction: SendClientLogsAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSettingsState())
    val state: StateFlow<TvSettingsState> = _state.asStateFlow()

    private val pendingWrites = Channel<PlaybackPreferences>(Channel.CONFLATED)

    init {
        scope.launch {
            getLogCollectionState().collect { enabled ->
                _state.update { current -> current.copy(diagnosticCollectionEnabled = enabled) }
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
            load()
            for (snapshot in pendingWrites) {
                try {
                    withContext(workDispatcher) {
                        savePlaybackPreferences(session.serverId, snapshot)
                    }
                } catch (exception: kotlinx.coroutines.CancellationException) {
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
                    // Atomic failure policy: drop unflushed edits (never retry
                    // against a reverted baseline), reload truth, surface it.
                    while (pendingWrites.tryReceive().isSuccess) {
                        // drain
                    }
                    load()
                    _state.update { current -> current.copy(saveError = true) }
                }
            }
        }
    }

    fun watchState(onChange: (TvSettingsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun setDefaultMaxBitrate(maxBitrateBps: Long?) {
        val policy = maxBitrateBps?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) } ?: PlaybackQualityPolicy.Auto
        mutate { preferences -> preferences.copy(defaultQualityPolicy = policy, defaultMaxBitrateBps = policy.maxBitrateBps) }
    }

    fun setDefaultQualityPolicy(policy: PlaybackQualityPolicy) {
        val normalized = policy.normalized()
        mutate { preferences -> preferences.copy(defaultQualityPolicy = normalized, defaultMaxBitrateBps = normalized.maxBitrateBps) }
    }

    fun setDefaultQualityChoice(
        modeName: String,
        maxBitrateBps: Long?,
    ) {
        val policy =
            when (modeName) {
                PlaybackQualityMode.Original.name -> PlaybackQualityPolicy.Original
                PlaybackQualityMode.Fixed.name -> maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
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
        scope.launch {
            try {
                withContext(workDispatcher) { setLogCollectionEnabled(enabled) }
                _state.update { current -> current.copy(diagnosticPreferenceError = false) }
            } catch (exception: kotlinx.coroutines.CancellationException) {
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

    fun sendClientLogs() {
        scope.launch {
            _state.update { current -> current.copy(diagnosticSendResult = TvDiagnosticsSendResult.Sending) }
            val result =
                try {
                    withContext(workDispatcher) { sendClientLogsAction() }
                } catch (exception: kotlinx.coroutines.CancellationException) {
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
        val updated = transform(state.value.preferences)
        _state.update { current ->
            current.copy(
                preferences = updated,
                saveError = false,
                bitrateChoices = bitrateChoices(updated.effectiveDefaultQualityPolicy()),
            )
        }
        pendingWrites.trySend(updated)
    }

    private suspend fun load() {
        val preferences =
            try {
                withContext(workDispatcher) { getPlaybackPreferences(session.serverId) }
            } catch (exception: kotlinx.coroutines.CancellationException) {
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
                PlaybackPreferences()
            }
        _state.update { current ->
            current.copy(
                isLoading = false,
                serverName = session.serverName,
                userName = session.userName,
                preferences = preferences,
                bitrateChoices = bitrateChoices(preferences.effectiveDefaultQualityPolicy()),
            )
        }
    }

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
}

private val LANGUAGE_CHOICES =
    listOf(
        TvLanguageChoice("eng"),
        TvLanguageChoice("spa"),
        TvLanguageChoice("fre"),
        TvLanguageChoice("ger"),
        TvLanguageChoice("ita"),
        TvLanguageChoice("por"),
        TvLanguageChoice("jpn"),
        TvLanguageChoice("kor"),
        TvLanguageChoice("chi"),
        TvLanguageChoice("hin"),
        TvLanguageChoice("tel"),
        TvLanguageChoice("tam"),
    )
