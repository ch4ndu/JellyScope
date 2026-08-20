// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

data class OpenSubtitleSearchRequest(
    val context: LocalSubtitleContext,
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val language: String,
    val sourceReleaseBasename: String? = null,
)

enum class OpenSubtitleResultPreference {
    NoPreference,
    PreferHearingImpaired,
    PreferForced,
}

data class OpenSubtitleSearchResult(
    val subtitleId: String,
    val fileId: String,
    val fileName: String,
    val language: String,
    val releaseName: String?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val rating: Double?,
    val downloadCount: Long?,
    val fps: Double?,
    val format: String?,
    val selectable: Boolean,
    val unavailableReason: String? = null,
)

data class OpenSubtitleDownload(
    val bytes: ByteArray,
    val remaining: Int?,
    val resetTime: String?,
)
