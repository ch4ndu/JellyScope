// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.Session

enum class TvPlaybackSubtitleMode {
    Unspecified,
    Off,
    Track,
    LocalAsset,
}

/**
 * Playback launch parameters, bundled so the Koin factory takes one holder
 * (nullable values inside destructured Koin parameters are fragile).
 */
data class TvPlaybackRequest(
    val session: Session,
    val itemId: String,
    val mediaSourceId: String? = null,
    val startPositionTicks: Long = 0L,
    val audioStreamIndex: Int? = null,
    val subtitleMode: TvPlaybackSubtitleMode = TvPlaybackSubtitleMode.Unspecified,
    val subtitleStreamIndex: Int? = null,
    val subtitleAssetId: String? = null,
)

/** Local playback identity; snapshot and artifact resolution stay in the offline presenter. */
data class TvOfflinePlaybackRequest(
    val session: Session,
    val downloadId: String,
    val restart: Boolean = false,
)
