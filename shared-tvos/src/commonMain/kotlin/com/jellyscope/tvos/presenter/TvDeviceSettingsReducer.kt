// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode

internal sealed interface TvDeviceSettingsChange {
    data class SettingsObserved(
        val settings: PlayerDeviceSettings,
        val audioIntent: PlayerAudioMode?,
        val hdrIntent: PlayerHdrMode?,
    ) : TvDeviceSettingsChange

    data class SaveStarted(
        val settings: PlayerDeviceSettings,
        val audioIntent: PlayerAudioMode?,
        val hdrIntent: PlayerHdrMode?,
    ) : TvDeviceSettingsChange

    data class Saved(
        val settings: PlayerDeviceSettings,
    ) : TvDeviceSettingsChange

    data class SaveFailed(
        val settings: PlayerDeviceSettings,
    ) : TvDeviceSettingsChange

    data object PolicyStarted : TvDeviceSettingsChange

    data object PolicyFailed : TvDeviceSettingsChange

    data class PolicyLoaded(
        val projection: DeviceSettingsPolicyProjection,
    ) : TvDeviceSettingsChange
}

internal fun reduceTvDeviceSettings(
    current: TvDeviceSettingsState,
    change: TvDeviceSettingsChange,
): TvDeviceSettingsState =
    when (change) {
        is TvDeviceSettingsChange.SettingsObserved ->
            with(change) {
                current.withSettings(
                    settings = settings,
                    audioIntent = audioIntent,
                    hdrIntent = hdrIntent,
                )
            }
        is TvDeviceSettingsChange.SaveStarted ->
            with(change) {
                current
                    .withSettings(
                        settings = settings,
                        audioIntent = audioIntent,
                        hdrIntent = hdrIntent,
                    ).copy(
                        isSaving = true,
                        saveError = false,
                        policyReadError = false,
                    )
            }
        is TvDeviceSettingsChange.Saved ->
            with(change) {
                current.withSettings(settings).copy(isSaving = false, saveError = false)
            }
        is TvDeviceSettingsChange.SaveFailed ->
            with(change) {
                current.withSettings(settings).copy(isSaving = false, saveError = true)
            }
        TvDeviceSettingsChange.PolicyStarted ->
            run {
                current.copy(isLoading = current.videoCodecs.isEmpty(), policyReadError = false)
            }
        TvDeviceSettingsChange.PolicyFailed ->
            run {
                current.copy(isLoading = false, policyReadError = true)
            }
        is TvDeviceSettingsChange.PolicyLoaded ->
            with(change) {
                current.withPolicy(projection)
            }
    }
