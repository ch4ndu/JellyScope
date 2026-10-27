// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvItemDetailIntent {
    data object Load : TvItemDetailIntent

    data class SelectVersion(
        val mediaSourceId: String,
    ) : TvItemDetailIntent

    data class SelectAudio(
        val streamIndex: Int,
    ) : TvItemDetailIntent

    data class SelectSubtitle(
        val streamIndex: Int?,
    ) : TvItemDetailIntent

    data class SelectLocalSubtitle(
        val assetId: String?,
    ) : TvItemDetailIntent

    data object TogglePlayed : TvItemDetailIntent

    data object ToggleFavorite : TvItemDetailIntent

    data class SelectSeason(
        val seasonId: String,
    ) : TvItemDetailIntent

    data object RetrySeasons : TvItemDetailIntent

    data object RetryEpisodes : TvItemDetailIntent

    data class ToggleEpisodePlayed(
        val itemId: String,
    ) : TvItemDetailIntent

    data class ToggleEpisodeFavorite(
        val itemId: String,
    ) : TvItemDetailIntent
}
