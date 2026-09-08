// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.PlaybackPreferences

data class TvLanguageChoice(
    val code: String,
)

data class TvSettingsState(
    val isLoading: Boolean = true,
    val loadError: Boolean = false,
    val serverName: String = "",
    val serverUrl: String = "",
    val userName: String = "",
    val preferences: PlaybackPreferences = PlaybackPreferences(),
    val bitrateChoices: List<TvQualityChoice> = emptyList(),
    val languageChoices: List<TvLanguageChoice> = LANGUAGE_CHOICES,
    val rememberLastLibraryView: Boolean = true,
    val stillWatchingPrompt: Boolean = true,
    val autoPlayNextDelaySeconds: Int = com.jellyscope.core.domain.model.DEFAULT_AUTOPLAY_NEXT_DELAY_SECONDS,
    val saveError: Boolean = false,
    val diagnosticPreferenceError: Boolean = false,
    val diagnosticCollectionEnabled: Boolean = true,
    val playbackInfoAtStartEnabled: Boolean = false,
    val isSavingPlaybackInfoAtStart: Boolean = false,
    val playbackInfoAtStartSaveError: Boolean = false,
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
