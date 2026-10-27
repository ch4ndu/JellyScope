// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SavePlayerDeviceSettingsAction
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerSettingDisabledReason
import com.jellyscope.core.domain.usecase.GetPlayerDevicePolicyUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
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
import kotlinx.coroutines.withContext

class TvDeviceSettingsPresenter(
    observePlayerDeviceSettings: ObservePlayerDeviceSettingsUseCase,
    private val savePlayerDeviceSettings: SavePlayerDeviceSettingsAction,
    private val getPlayerDevicePolicy: GetPlayerDevicePolicyUseCase,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val observedSettings = observePlayerDeviceSettings()
    private var latestObservedSettings = observedSettings.value
    private val _state = MutableStateFlow(TvDeviceSettingsState().withSettings(latestObservedSettings))
    val state: StateFlow<TvDeviceSettingsState> = _state.asStateFlow()

    private val pendingWrites = Channel<Long>(Channel.CONFLATED)
    private var writeGeneration = 0L
    private var policyGeneration = 0L
    private var policyJob: Job? = null
    private var audioIntent: PlayerAudioMode? = null
    private var hdrIntent: PlayerHdrMode? = null
    private var failedIntent: DeviceSettingsIntent? = null

    init {
        scope.launch {
            observedSettings.collect { settings ->
                latestObservedSettings = settings
                reduce { TvDeviceSettingsChange.SettingsObserved(settings, audioIntent, hdrIntent) }
                if (audioIntent == null && hdrIntent == null) {
                    loadPolicy()
                }
            }
        }
        scope.launch {
            for (generation in pendingWrites) {
                writeSettings(generation)
            }
        }
    }

    private fun reduce(change: TvDeviceSettingsChange) = reduce { change }

    // Evaluate observed store values inside each atomic update attempt.
    private fun reduce(change: () -> TvDeviceSettingsChange) {
        _state.update { current -> reduceTvDeviceSettings(current, change()) }
    }

    fun accept(intent: TvDeviceSettingsIntent) {
        when (intent) {
            is TvDeviceSettingsIntent.SetAudioChoice -> setAudioChoice(intent.choice)
            is TvDeviceSettingsIntent.SetHdrChoice -> setHdrChoice(intent.choice)
            TvDeviceSettingsIntent.RetryPolicy -> retryPolicy()
            TvDeviceSettingsIntent.RetrySave -> retrySave()
        }
    }

    fun watchState(onChange: (TvDeviceSettingsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun setAudioChoice(choice: TvDeviceAudioChoice) {
        val mode = choice.toPlayerAudioMode()
        if (audioIntent == null && latestObservedSettings.audioMode == mode) return
        audioIntent = mode
        enqueueWrite()
    }

    private fun setHdrChoice(choice: TvDeviceHdrChoice) {
        val mode = choice.toPlayerHdrMode()
        if (hdrIntent == null && latestObservedSettings.hdrMode == mode) return
        hdrIntent = mode
        enqueueWrite()
    }

    private fun retryPolicy() {
        loadPolicy()
    }

    private fun retrySave() {
        val retry = failedIntent ?: return
        audioIntent = retry.audioMode
        hdrIntent = retry.hdrMode
        enqueueWrite()
    }

    override fun close() {
        policyJob?.cancel()
        super.close()
    }

    private fun enqueueWrite() {
        failedIntent = null
        writeGeneration += 1
        policyGeneration += 1
        policyJob?.cancel()
        reduce { TvDeviceSettingsChange.SaveStarted(latestObservedSettings, audioIntent, hdrIntent) }
        pendingWrites.trySend(writeGeneration)
    }

    private suspend fun writeSettings(generation: Long) {
        latestObservedSettings = observedSettings.value
        val intent = DeviceSettingsIntent(audioIntent, hdrIntent)
        val snapshot =
            latestObservedSettings.copy(
                audioMode = intent.audioMode ?: latestObservedSettings.audioMode,
                hdrMode = intent.hdrMode ?: latestObservedSettings.hdrMode,
            )
        try {
            withContext(workDispatcher) { savePlayerDeviceSettings(snapshot) }
            if (generation != writeGeneration) return

            latestObservedSettings = observedSettings.value
            if (audioIntent == latestObservedSettings.audioMode) audioIntent = null
            if (hdrIntent == latestObservedSettings.hdrMode) hdrIntent = null
            if (audioIntent != null || hdrIntent != null) {
                pendingWrites.trySend(writeGeneration)
                return
            }
            reduce { TvDeviceSettingsChange.Saved(latestObservedSettings) }
            loadPolicy()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            deviceSettingsLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "device-settings-write",
                    event = "failed",
                    operation = DiagnosticOperation.SavePlayerDeviceSettings,
                    throwable = exception,
                )
            }
            if (generation == writeGeneration) {
                while (pendingWrites.tryReceive().isSuccess) {
                    // Recover from the latest observable settings before retry.
                }
                failedIntent = intent
                audioIntent = null
                hdrIntent = null
                latestObservedSettings = observedSettings.value
                reduce { TvDeviceSettingsChange.SaveFailed(latestObservedSettings) }
                loadPolicy()
            }
        }
    }

    private fun loadPolicy() {
        policyJob?.cancel()
        val generation = ++policyGeneration
        reduce(TvDeviceSettingsChange.PolicyStarted)
        policyJob =
            scope.launch {
                val projection =
                    try {
                        withContext(workDispatcher) {
                            getPlayerDevicePolicy().toDeviceSettingsPolicyProjection()
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        deviceSettingsLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "device-policy-read",
                                event = "failed",
                                operation = DiagnosticOperation.GetPlayerDevicePolicy,
                                throwable = exception,
                            )
                        }
                        if (generation == policyGeneration) {
                            reduce(TvDeviceSettingsChange.PolicyFailed)
                        }
                        return@launch
                    }
                if (generation != policyGeneration || audioIntent != null || hdrIntent != null) return@launch
                latestObservedSettings = observedSettings.value
                reduce(TvDeviceSettingsChange.PolicyLoaded(projection))
            }
    }
}

private data class DeviceSettingsIntent(
    val audioMode: PlayerAudioMode?,
    val hdrMode: PlayerHdrMode?,
)

internal data class DeviceSettingsPolicyProjection(
    val audioChoice: TvDeviceAudioChoice,
    val hdrChoice: TvDeviceHdrChoice,
    val effectiveAudioChoice: TvDeviceAudioChoice,
    val effectiveHdrChoice: TvDeviceHdrChoice,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val maxAudioChannels: Int?,
    val audioFallbackReason: TvDeviceSettingFallbackReason?,
    val hdrFallbackReason: TvDeviceSettingFallbackReason?,
)

internal fun TvDeviceSettingsState.withSettings(
    settings: PlayerDeviceSettings,
    audioIntent: PlayerAudioMode? = null,
    hdrIntent: PlayerHdrMode? = null,
): TvDeviceSettingsState =
    copy(
        audioChoice = (audioIntent ?: settings.audioMode).toTvDeviceAudioChoice(),
        hdrChoice = (hdrIntent ?: settings.hdrMode).toTvDeviceHdrChoice(),
        audioFallbackReason =
            if (audioIntent == null && settings.audioMode == PlayerAudioMode.PassthroughWhenSupported) {
                TvDeviceSettingFallbackReason.SavedPassthroughUsesAuto
            } else {
                null
            },
    )

private fun EffectivePlayerDevicePolicy.toDeviceSettingsPolicyProjection(): DeviceSettingsPolicyProjection =
    DeviceSettingsPolicyProjection(
        audioChoice = settings.audioMode.toTvDeviceAudioChoice(),
        hdrChoice = settings.hdrMode.toTvDeviceHdrChoice(),
        effectiveAudioChoice = effectiveAudioMode.toTvDeviceAudioChoice(),
        effectiveHdrChoice = effectiveHdrMode.toTvDeviceHdrChoice(),
        videoCodecs = capabilities.videoCodecs.distinct().sorted(),
        audioCodecs = capabilities.audioCodecs.distinct().sorted(),
        maxAudioChannels = maxAudioChannels,
        audioFallbackReason = audioFallbackReason(),
        hdrFallbackReason = hdrFallbackReason(),
    )

internal fun TvDeviceSettingsState.withPolicy(projection: DeviceSettingsPolicyProjection): TvDeviceSettingsState =
    copy(
        isLoading = false,
        audioChoice = projection.audioChoice,
        hdrChoice = projection.hdrChoice,
        effectiveAudioChoice = projection.effectiveAudioChoice,
        effectiveHdrChoice = projection.effectiveHdrChoice,
        videoCodecs = projection.videoCodecs,
        audioCodecs = projection.audioCodecs,
        maxAudioChannels = projection.maxAudioChannels,
        audioFallbackReason = projection.audioFallbackReason,
        hdrFallbackReason = projection.hdrFallbackReason,
        policyReadError = false,
    )

private fun EffectivePlayerDevicePolicy.audioFallbackReason(): TvDeviceSettingFallbackReason? =
    when {
        settings.audioMode == PlayerAudioMode.PassthroughWhenSupported ->
            TvDeviceSettingFallbackReason.SavedPassthroughUsesAuto
        audioDisabledReason == PlayerSettingDisabledReason.AudioRouteUnsupported ->
            TvDeviceSettingFallbackReason.AudioRouteUnsupported
        else -> null
    }

private fun EffectivePlayerDevicePolicy.hdrFallbackReason(): TvDeviceSettingFallbackReason? =
    when (hdrDisabledReason) {
        PlayerSettingDisabledReason.HdrDisplayUnsupported -> TvDeviceSettingFallbackReason.HdrDisplayUnsupported
        else -> null
    }

private fun PlayerAudioMode.toTvDeviceAudioChoice(): TvDeviceAudioChoice =
    when (this) {
        PlayerAudioMode.StereoPcm -> TvDeviceAudioChoice.StereoPcm
        else -> TvDeviceAudioChoice.Auto
    }

private fun PlayerHdrMode.toTvDeviceHdrChoice(): TvDeviceHdrChoice =
    when (this) {
        PlayerHdrMode.PreferSdr -> TvDeviceHdrChoice.PreferSdr
        PlayerHdrMode.Auto -> TvDeviceHdrChoice.Auto
    }

private fun TvDeviceAudioChoice.toPlayerAudioMode(): PlayerAudioMode =
    when (this) {
        TvDeviceAudioChoice.Auto -> PlayerAudioMode.Auto
        TvDeviceAudioChoice.StereoPcm -> PlayerAudioMode.StereoPcm
    }

private fun TvDeviceHdrChoice.toPlayerHdrMode(): PlayerHdrMode =
    when (this) {
        TvDeviceHdrChoice.Auto -> PlayerHdrMode.Auto
        TvDeviceHdrChoice.PreferSdr -> PlayerHdrMode.PreferSdr
    }

private val deviceSettingsLogger = diagnosticLogger(DiagnosticTag.TvSettingsPresenter)
