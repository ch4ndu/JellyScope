// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference

sealed interface TvSubtitleSettingsIntent {
    class SaveApiKey(
        val value: String,
    ) : TvSubtitleSettingsIntent

    data object ClearApiKey : TvSubtitleSettingsIntent

    data object RetryApiKeyLoad : TvSubtitleSettingsIntent

    data class SetResultPreference(
        val preference: OpenSubtitleResultPreference,
    ) : TvSubtitleSettingsIntent

    data object RetryResultPreferenceLoad : TvSubtitleSettingsIntent

    data object ClearDownloadedSubtitles : TvSubtitleSettingsIntent
}
