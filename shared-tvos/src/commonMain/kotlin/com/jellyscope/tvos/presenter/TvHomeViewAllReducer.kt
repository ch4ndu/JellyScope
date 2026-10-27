// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvHomeViewAllChange {
    data object Started : TvHomeViewAllChange

    data class Loaded(
        val cards: List<TvMediaCard>,
    ) : TvHomeViewAllChange

    data class Failed(
        val error: TvErrorKind,
    ) : TvHomeViewAllChange

    data object RefreshStarted : TvHomeViewAllChange

    data class RefreshFailed(
        val error: TvErrorKind,
    ) : TvHomeViewAllChange
}

internal fun reduceTvHomeViewAll(
    current: TvHomeViewAllState,
    change: TvHomeViewAllChange,
): TvHomeViewAllState =
    when (change) {
        TvHomeViewAllChange.Started ->
            run {
                current.copy(
                    status = TvHomeRowStatus.Loading,
                    items = emptyList(),
                    error = null,
                    isRefreshing = false,
                )
            }
        is TvHomeViewAllChange.Loaded ->
            with(change) {
                current.copy(
                    status =
                        if (cards.isEmpty()) {
                            TvHomeRowStatus.Empty
                        } else {
                            TvHomeRowStatus.Content
                        },
                    items = cards,
                    error = null,
                    isRefreshing = false,
                )
            }
        is TvHomeViewAllChange.Failed ->
            with(change) {
                current.copy(
                    status = TvHomeRowStatus.Error,
                    items = emptyList(),
                    error = error,
                    isRefreshing = false,
                )
            }
        TvHomeViewAllChange.RefreshStarted ->
            run {
                current.copy(isRefreshing = true, error = null)
            }
        is TvHomeViewAllChange.RefreshFailed ->
            with(change) {
                current.copy(
                    error = error,
                    isRefreshing = false,
                )
            }
    }
