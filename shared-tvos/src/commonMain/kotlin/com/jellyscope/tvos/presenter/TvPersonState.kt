// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

data class TvPersonHeader(
    val id: String,
    val name: String,
    val overview: String? = null,
    val imageUrl: String? = null,
)

data class TvPersonState(
    val headerLoading: Boolean = true,
    val header: TvPersonHeader? = null,
    val headerError: TvErrorKind? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val movies: List<TvMediaCard> = emptyList(),
    val shows: List<TvMediaCard> = emptyList(),
    val totalCount: Int? = null,
    val consumedOffset: Int = 0,
    val endReached: Boolean = false,
    val error: TvErrorKind? = null,
)
