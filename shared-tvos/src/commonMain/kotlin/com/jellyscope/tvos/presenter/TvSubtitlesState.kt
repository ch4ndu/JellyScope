// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.Session

data class TvSubtitlesRequest(
    val session: Session,
    val itemId: String,
    val mediaSourceId: String?,
)

enum class TvSubtitlesAvailability {
    Loading,
    Available,
    Unsupported,
    Failed,
}

enum class TvLocalSubtitleSyncPresentation {
    Pending,
    Uploading,
    Reconciling,
    Confirmed,
    UploadedUnconfirmed,
    LocalOnlyAlternateSource,
    PermissionDenied,
    FailedPermanent,
}

data class TvOpenSubtitleResult(
    val fileId: String,
    val title: String,
    val language: String,
    val format: String?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val rating: Double?,
    val downloadCount: Long?,
    val fps: Double?,
    val available: Boolean,
    val unavailableReason: String?,
)

data class TvLocalSubtitle(
    val id: String,
    val label: String,
    val language: String,
    val releaseName: String?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val selected: Boolean,
    val sync: TvLocalSubtitleSyncPresentation,
    val canRetrySync: Boolean,
)

data class TvSubtitlesState(
    val availability: TvSubtitlesAvailability = TvSubtitlesAvailability.Loading,
    val languageChoices: List<TvLanguageChoice> = TV_SUBTITLE_LANGUAGE_CHOICES,
    val selectedLanguage: String = DEFAULT_TV_SUBTITLE_LANGUAGE,
    val localSubtitles: List<TvLocalSubtitle> = emptyList(),
    val selectedLocalAssetId: String? = null,
    val isSearching: Boolean = false,
    val searchResults: List<TvOpenSubtitleResult> = emptyList(),
    val searchError: Boolean = false,
    val installingFileId: String? = null,
    val installationError: Boolean = false,
    val quotaRemaining: Int? = null,
    val quotaResetTime: String? = null,
    val deletingAssetId: String? = null,
    val deleteErrorAssetId: String? = null,
    val syncingAssetId: String? = null,
    val syncErrorAssetId: String? = null,
    val isSelecting: Boolean = false,
    val selectionError: Boolean = false,
    val selectionRevision: Long = 0L,
    val selectionAssetId: String? = null,
)

internal const val DEFAULT_TV_SUBTITLE_LANGUAGE = "eng"

private val TV_SUBTITLE_LANGUAGE_CHOICES =
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
