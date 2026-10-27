// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference

internal sealed interface TvSubtitleSettingsChange {
    data object ApiKeySaveStarted : TvSubtitleSettingsChange

    data class PreferenceSaveStarted(
        val preference: OpenSubtitleResultPreference,
    ) : TvSubtitleSettingsChange

    data object ClearStarted : TvSubtitleSettingsChange

    data object ClearFinished : TvSubtitleSettingsChange

    data object ClearFailed : TvSubtitleSettingsChange

    data object ApiKeyLoadStarted : TvSubtitleSettingsChange

    data object ApiKeyLoadFailed : TvSubtitleSettingsChange

    data class ApiKeyLoaded(
        val configured: Boolean,
    ) : TvSubtitleSettingsChange

    data object PreferenceLoadStarted : TvSubtitleSettingsChange

    data object PreferenceLoadFailed : TvSubtitleSettingsChange

    data class PreferenceLoaded(
        val preference: OpenSubtitleResultPreference,
    ) : TvSubtitleSettingsChange

    data class ApiKeyWriteFinished(
        val result: ApiKeyWriteResult,
    ) : TvSubtitleSettingsChange

    data class PreferenceWriteFinished(
        val result: ResultPreferenceWriteResult,
    ) : TvSubtitleSettingsChange
}

internal fun reduceTvSubtitleSettings(
    current: TvSubtitleSettingsState,
    change: TvSubtitleSettingsChange,
): TvSubtitleSettingsState =
    when (change) {
        TvSubtitleSettingsChange.ApiKeySaveStarted ->
            run {
                current.copy(
                    isSavingApiKey = true,
                    apiKeySaveError = false,
                    apiKeyLoadError = false,
                )
            }
        is TvSubtitleSettingsChange.PreferenceSaveStarted ->
            with(change) {
                current.copy(
                    resultPreference = preference,
                    isSavingResultPreference = true,
                    resultPreferenceSaveError = false,
                    resultPreferenceLoadError = false,
                )
            }
        TvSubtitleSettingsChange.ClearStarted ->
            run {
                current.copy(
                    isClearingDownloadedSubtitles = true,
                    clearDownloadedSubtitlesError = false,
                )
            }
        TvSubtitleSettingsChange.ClearFinished ->
            run {
                current.copy(
                    isClearingDownloadedSubtitles = false,
                    clearDownloadedSubtitlesError = false,
                    clearDownloadedSubtitlesRevision = current.clearDownloadedSubtitlesRevision + 1L,
                )
            }
        TvSubtitleSettingsChange.ClearFailed ->
            run {
                current.copy(
                    isClearingDownloadedSubtitles = false,
                    clearDownloadedSubtitlesError = true,
                )
            }
        TvSubtitleSettingsChange.ApiKeyLoadStarted ->
            run {
                current.copy(
                    isLoadingApiKey = true,
                    apiKeyLoadError = false,
                )
            }
        TvSubtitleSettingsChange.ApiKeyLoadFailed ->
            run {
                current.copy(
                    isLoadingApiKey = false,
                    isApiKeyLoaded = false,
                    apiKeyLoadError = true,
                )
            }
        is TvSubtitleSettingsChange.ApiKeyLoaded ->
            with(change) {
                current.copy(
                    isLoadingApiKey = false,
                    isApiKeyLoaded = true,
                    apiKeyConfigured = configured,
                    apiKeyLoadError = false,
                )
            }
        TvSubtitleSettingsChange.PreferenceLoadStarted ->
            run {
                current.copy(
                    isLoadingResultPreference = true,
                    resultPreferenceLoadError = false,
                )
            }
        TvSubtitleSettingsChange.PreferenceLoadFailed ->
            run {
                current.copy(
                    isLoadingResultPreference = false,
                    isResultPreferenceLoaded = false,
                    resultPreferenceLoadError = true,
                )
            }
        is TvSubtitleSettingsChange.PreferenceLoaded ->
            with(change) {
                current.copy(
                    isLoadingResultPreference = false,
                    isResultPreferenceLoaded = true,
                    resultPreference = preference,
                    resultPreferenceLoadError = false,
                )
            }
        is TvSubtitleSettingsChange.ApiKeyWriteFinished ->
            with(change) {
                current.copy(
                    isLoadingApiKey = false,
                    isApiKeyLoaded = result.recovered,
                    apiKeyConfigured = result.configured,
                    apiKeyLoadError = !result.recovered,
                    isSavingApiKey = false,
                    apiKeySaveError = !result.saved,
                    apiKeyMutationRevision =
                        if (result.saved) current.apiKeyMutationRevision + 1L else current.apiKeyMutationRevision,
                )
            }
        is TvSubtitleSettingsChange.PreferenceWriteFinished ->
            with(change) {
                current.copy(
                    isLoadingResultPreference = false,
                    isResultPreferenceLoaded = result.recovered,
                    resultPreference = result.preference,
                    resultPreferenceLoadError = !result.recovered,
                    isSavingResultPreference = false,
                    resultPreferenceSaveError = !result.saved,
                )
            }
    }
