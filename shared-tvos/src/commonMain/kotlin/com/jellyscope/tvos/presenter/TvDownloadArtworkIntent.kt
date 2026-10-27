// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvDownloadArtworkIntent {
    data object Load : TvDownloadArtworkIntent

    data object Cancel : TvDownloadArtworkIntent
}
