// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvDeviceSettingsIntent {
    data class SetAudioChoice(
        val choice: TvDeviceAudioChoice,
    ) : TvDeviceSettingsIntent

    data class SetHdrChoice(
        val choice: TvDeviceHdrChoice,
    ) : TvDeviceSettingsIntent

    data object RetryPolicy : TvDeviceSettingsIntent

    data object RetrySave : TvDeviceSettingsIntent
}
