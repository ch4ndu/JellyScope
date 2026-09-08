// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.model.Session

internal data class ResolvedTvSubtitleContext(
    val context: LocalSubtitleContext,
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val sourceReleaseBasename: String?,
    val seriesTitle: String?,
) {
    fun searchRequest(language: String): OpenSubtitleSearchRequest =
        OpenSubtitleSearchRequest(
            context = context,
            title = title,
            year = year,
            imdbId = imdbId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            language = language,
            sourceReleaseBasename = sourceReleaseBasename,
            seriesTitle = seriesTitle,
        )
}

internal fun MediaItemDetail.resolveTvSubtitleContext(
    session: Session,
    requestedMediaSourceId: String?,
): ResolvedTvSubtitleContext? {
    if (item.kind != MediaKind.Movie && item.kind != MediaKind.Episode) return null
    val versions = tvDetailVersions()
    val selectedVersion =
        if (requestedMediaSourceId == null) {
            versions.firstOrNull()
        } else {
            versions.firstOrNull { version -> version.id == requestedMediaSourceId }
        }
            ?: return null
    return ResolvedTvSubtitleContext(
        context = LocalSubtitleContext(session.serverId, session.userId, item.id, selectedVersion.id),
        title = item.name,
        year = productionYear ?: item.productionYear,
        imdbId = imdbId,
        seasonNumber = item.parentIndexNumber,
        episodeNumber = item.indexNumber,
        sourceReleaseBasename = selectedVersion.releaseBasename,
        seriesTitle = item.seriesName,
    )
}

internal fun tvSubtitleLanguageCode(preferredLanguage: String?): String =
    when (preferredLanguage?.trim()?.lowercase()) {
        "en", "eng" -> "eng"
        "es", "spa" -> "spa"
        "fr", "fra", "fre" -> "fre"
        "de", "deu", "ger" -> "ger"
        "it", "ita" -> "ita"
        "pt", "por" -> "por"
        "ja", "jpn" -> "jpn"
        "ko", "kor" -> "kor"
        "zh", "zho", "chi" -> "chi"
        "hi", "hin" -> "hin"
        "te", "tel" -> "tel"
        "ta", "tam" -> "tam"
        else -> DEFAULT_TV_SUBTITLE_LANGUAGE
    }

internal fun OpenSubtitleSearchResult.toTvOpenSubtitleResult(): TvOpenSubtitleResult =
    TvOpenSubtitleResult(
        fileId = fileId,
        title = releaseName?.takeIf(String::isNotBlank) ?: fileName,
        language = language,
        format = format,
        hearingImpaired = hearingImpaired,
        forced = forced,
        trusted = trusted,
        rating = rating,
        downloadCount = downloadCount,
        fps = fps,
        available = selectable,
        unavailableReason = unavailableReason.takeUnless { selectable },
    )

internal fun LocalSubtitleAsset.toTvLocalSubtitle(selectedAssetId: String?): TvLocalSubtitle =
    TvLocalSubtitle(
        id = id,
        label = label,
        language = language,
        releaseName = releaseName,
        hearingImpaired = hearingImpaired,
        forced = forced,
        trusted = trusted,
        selected = id == selectedAssetId,
        sync = syncState.toTvLocalSubtitleSyncPresentation(),
        canRetrySync = syncState == LocalSubtitleSyncState.UploadedUnconfirmed,
    )

private fun LocalSubtitleSyncState.toTvLocalSubtitleSyncPresentation(): TvLocalSubtitleSyncPresentation =
    when (this) {
        LocalSubtitleSyncState.Pending -> TvLocalSubtitleSyncPresentation.Pending
        LocalSubtitleSyncState.Uploading -> TvLocalSubtitleSyncPresentation.Uploading
        LocalSubtitleSyncState.Reconciling -> TvLocalSubtitleSyncPresentation.Reconciling
        is LocalSubtitleSyncState.Confirmed -> TvLocalSubtitleSyncPresentation.Confirmed
        LocalSubtitleSyncState.UploadedUnconfirmed -> TvLocalSubtitleSyncPresentation.UploadedUnconfirmed
        LocalSubtitleSyncState.LocalOnlyAlternateSource -> TvLocalSubtitleSyncPresentation.LocalOnlyAlternateSource
        LocalSubtitleSyncState.PermissionDenied -> TvLocalSubtitleSyncPresentation.PermissionDenied
        LocalSubtitleSyncState.FailedPermanent -> TvLocalSubtitleSyncPresentation.FailedPermanent
    }
