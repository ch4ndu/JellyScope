// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference

data class TvSubtitleSettingsState(
    val isLoadingApiKey: Boolean = false,
    val isApiKeyLoaded: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val apiKeyLoadError: Boolean = false,
    val isSavingApiKey: Boolean = false,
    val apiKeySaveError: Boolean = false,
    val apiKeyMutationRevision: Long = 0L,
    val isLoadingResultPreference: Boolean = false,
    val isResultPreferenceLoaded: Boolean = false,
    val resultPreference: OpenSubtitleResultPreference = OpenSubtitleResultPreference.NoPreference,
    val resultPreferenceLoadError: Boolean = false,
    val isSavingResultPreference: Boolean = false,
    val resultPreferenceSaveError: Boolean = false,
    val isClearingDownloadedSubtitles: Boolean = false,
    val clearDownloadedSubtitlesError: Boolean = false,
    val clearDownloadedSubtitlesRevision: Long = 0L,
)
