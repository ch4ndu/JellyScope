// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.MediaPersonType

data class TvSeason(
    val id: String,
    val name: String,
    val indexNumber: Int?,
    val imageUrl: String? = null,
)

enum class TvDetailPlayMode {
    Play,
    Resume,
    Restart,
}

data class TvItemDetailContent(
    val id: String,
    val title: String,
    val kind: TvCardKind,
    val overview: String? = null,
    val tagline: String? = null,
    val productionYear: Int? = null,
    val runtimeMinutes: Long? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val playedPercentage: Double? = null,
    val resumePositionTicks: Long = 0L,
    val playStartPositionTicks: Long = 0L,
    val playMode: TvDetailPlayMode = TvDetailPlayMode.Play,
    val canRestart: Boolean = false,
    val isPlayable: Boolean = false,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val badges: TvItemBadges = TvItemBadges(),
)

data class TvDetailVersionChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
)

data class TvDetailTrackChoice(
    val streamIndex: Int,
    val label: String,
    val language: String? = null,
    val selected: Boolean,
)

enum class TvDetailMediaInfoKind {
    Source,
    Video,
    Audio,
    Subtitle,
}

enum class TvDetailMediaInfoField {
    Name,
    Container,
    Runtime,
    Size,
    Resolution,
    Codec,
    FrameRate,
    Language,
    Channels,
    Bitrate,
}

data class TvDetailMediaInfoRow(
    val kind: TvDetailMediaInfoKind,
    val field: TvDetailMediaInfoField,
    val value: String,
    val streamLabel: String? = null,
)

data class TvDetailPersonCredit(
    val role: String? = null,
    val type: MediaPersonType? = null,
)

data class TvDetailPerson(
    val id: String,
    val name: String,
    val credits: List<TvDetailPersonCredit> = emptyList(),
    val imageUrl: String? = null,
)

data class TvDetailPlaybackSelection(
    val itemId: String,
    val mediaSourceId: String?,
    val startPositionTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleMode: TvPlaybackSubtitleMode,
    val subtitleStreamIndex: Int?,
    val subtitleAssetId: String? = null,
)

data class TvItemDetailState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val content: TvItemDetailContent? = null,
    val versions: List<TvDetailVersionChoice> = emptyList(),
    val selectedMediaSourceId: String? = null,
    val audioTracks: List<TvDetailTrackChoice> = emptyList(),
    val subtitleTracks: List<TvDetailTrackChoice> = emptyList(),
    val subtitleMode: TvPlaybackSubtitleMode = TvPlaybackSubtitleMode.Off,
    val selectedSubtitleStreamIndex: Int? = null,
    val selectedSubtitleAssetId: String? = null,
    val isSubtitleSelectionPending: Boolean = false,
    val subtitleSelectionFailed: Boolean = false,
    val mediaInfo: List<TvDetailMediaInfoRow> = emptyList(),
    val cast: List<TvDetailPerson> = emptyList(),
    val crew: List<TvDetailPerson> = emptyList(),
    val seasons: List<TvSeason> = emptyList(),
    val seasonsLoading: Boolean = false,
    val seasonsError: TvErrorKind? = null,
    val selectedSeasonId: String? = null,
    val episodes: List<TvMediaCard> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: TvErrorKind? = null,
    val nextUpLoading: Boolean = false,
    val nextUpError: TvErrorKind? = null,
    val nextUpEpisodeId: String? = null,
    val nextUpEpisode: TvMediaCard? = null,
    val relatedGroups: List<TvRelatedGroup> = emptyList(),
    val relatedLoading: Boolean = false,
    val error: TvErrorKind? = null,
    val actionError: TvErrorKind? = null,
)
