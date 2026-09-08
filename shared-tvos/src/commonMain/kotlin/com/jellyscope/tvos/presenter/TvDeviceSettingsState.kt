// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

enum class TvDeviceAudioChoice {
    Auto,
    StereoPcm,
}

enum class TvDeviceHdrChoice {
    Auto,
    PreferSdr,
}

enum class TvDeviceSettingFallbackReason {
    SavedPassthroughUsesAuto,
    AudioRouteUnsupported,
    HdrDisplayUnsupported,
}

data class TvDeviceSettingsState(
    val isLoading: Boolean = true,
    val audioChoice: TvDeviceAudioChoice = TvDeviceAudioChoice.Auto,
    val hdrChoice: TvDeviceHdrChoice = TvDeviceHdrChoice.Auto,
    val effectiveAudioChoice: TvDeviceAudioChoice = TvDeviceAudioChoice.Auto,
    val effectiveHdrChoice: TvDeviceHdrChoice = TvDeviceHdrChoice.Auto,
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val maxAudioChannels: Int? = null,
    val audioFallbackReason: TvDeviceSettingFallbackReason? = null,
    val hdrFallbackReason: TvDeviceSettingFallbackReason? = null,
    val isSaving: Boolean = false,
    val policyReadError: Boolean = false,
    val saveError: Boolean = false,
)
