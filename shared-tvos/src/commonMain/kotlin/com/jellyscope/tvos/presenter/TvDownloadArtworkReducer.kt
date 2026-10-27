// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvDownloadArtworkChange {
    data class Started(
        val generation: Long,
    ) : TvDownloadArtworkChange

    class Loaded(
        val bytes: ByteArray?,
        val generation: Long,
    ) : TvDownloadArtworkChange

    data class Cancelled(
        val generation: Long,
    ) : TvDownloadArtworkChange
}

internal fun reduceTvDownloadArtwork(change: TvDownloadArtworkChange): TvDownloadArtworkState =
    when (change) {
        is TvDownloadArtworkChange.Started -> TvDownloadArtworkState(isLoading = true, readGeneration = change.generation)
        is TvDownloadArtworkChange.Loaded ->
            TvDownloadArtworkState(
                imageBytes = change.bytes,
                isLoading = false,
                readGeneration = change.generation,
            )
        is TvDownloadArtworkChange.Cancelled -> TvDownloadArtworkState(isLoading = false, readGeneration = change.generation)
    }
