// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

enum class TvHomeRowKind {
    ContinueWatching,
    Favorites,
    NextUp,
    RecentlyAdded,
}

enum class TvHomeRowStatus {
    Loading,
    Content,
    Empty,
    Error,
}

data class TvMediaRow(
    val kind: TvHomeRowKind,
    val status: TvHomeRowStatus = TvHomeRowStatus.Loading,
    val items: List<TvMediaCard> = emptyList(),
    val error: TvErrorKind? = null,
    val stableId: String = kind.name,
)

data class TvHomeState(
    val rows: List<TvMediaRow> = TvHomeRowKind.entries.map { row -> TvMediaRow(kind = row) },
) {
    internal fun row(kind: TvHomeRowKind): TvMediaRow = rows.firstOrNull { row -> row.kind == kind } ?: TvMediaRow(kind = kind)
}
