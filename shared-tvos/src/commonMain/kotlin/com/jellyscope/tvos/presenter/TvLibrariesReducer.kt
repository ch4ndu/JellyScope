// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvLibrariesChange {
    data object LoadStarted : TvLibrariesChange

    data class Loaded(
        val tiles: List<TvLibraryTile>,
    ) : TvLibrariesChange

    data class Failed(
        val error: TvErrorKind,
    ) : TvLibrariesChange
}

internal fun reduceTvLibraries(
    current: TvLibrariesState,
    change: TvLibrariesChange,
): TvLibrariesState =
    when (change) {
        TvLibrariesChange.LoadStarted ->
            run {
                current.copy(isLoading = true, error = null)
            }
        is TvLibrariesChange.Loaded ->
            with(change) {
                current.copy(
                    isLoading = false,
                    libraries = tiles,
                    error = null,
                )
            }
        is TvLibrariesChange.Failed ->
            with(change) {
                current.copy(
                    isLoading = false,
                    error = error,
                )
            }
    }
