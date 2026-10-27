// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import com.jellyscope.ui.component.MediaCardUi

internal sealed interface DiscoverChange {
    data class Genres(
        val state: DiscoverListState<DiscoverFacetUi>,
    ) : DiscoverChange

    data class Studios(
        val state: DiscoverListState<DiscoverFacetUi>,
    ) : DiscoverChange

    data class Collections(
        val state: DiscoverListState<MediaCardUi>,
    ) : DiscoverChange

    data class Suggestions(
        val state: DiscoverSuggestionsState,
    ) : DiscoverChange

    data class Upcoming(
        val state: DiscoverListState<MediaCardUi>,
    ) : DiscoverChange
}

internal fun reduceDiscover(
    current: DiscoverUiState,
    change: DiscoverChange,
): DiscoverUiState =
    when (change) {
        is DiscoverChange.Genres -> current.copy(genres = change.state)
        is DiscoverChange.Studios -> current.copy(studios = change.state)
        is DiscoverChange.Collections -> current.copy(collections = change.state)
        is DiscoverChange.Suggestions -> current.copy(suggestions = change.state)
        is DiscoverChange.Upcoming -> current.copy(upcoming = change.state)
    }
