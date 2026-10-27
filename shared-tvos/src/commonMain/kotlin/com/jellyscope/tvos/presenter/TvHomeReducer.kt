// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvHomeChange {
    data object Started : TvHomeChange

    data class Loaded(
        val cards: List<TvMediaCard>,
    ) : TvHomeChange

    data class Failed(
        val error: TvErrorKind,
    ) : TvHomeChange
}

internal fun reduceTvHome(
    current: TvHomeState,
    row: TvHomeRowKind,
    change: TvHomeChange,
): TvHomeState {
    val rows =
        current.rows.map { candidate ->
            if (candidate.kind != row) {
                candidate
            } else {
                when (change) {
                    TvHomeChange.Started -> candidate.copy(status = TvHomeRowStatus.Loading, items = emptyList(), error = null)
                    is TvHomeChange.Loaded ->
                        candidate.copy(
                            status = if (change.cards.isEmpty()) TvHomeRowStatus.Empty else TvHomeRowStatus.Content,
                            items = change.cards,
                            error = null,
                        )
                    is TvHomeChange.Failed -> candidate.copy(status = TvHomeRowStatus.Error, items = emptyList(), error = change.error)
                }
            }
        }
    return if (current.rows == rows) current else current.copy(rows = rows)
}
