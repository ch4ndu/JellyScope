// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

enum class DiscoverSection {
    Genres,
    Studios,
    Collections,
    Suggestions,
    Upcoming,
}

val discoverSectionsInOrder: List<DiscoverSection> = DiscoverSection.entries.toList()

fun DiscoverUiState.sectionHasItems(section: DiscoverSection): Boolean =
    when (section) {
        DiscoverSection.Genres -> genres.hasContent
        DiscoverSection.Studios -> studios.hasContent
        DiscoverSection.Collections -> collections.hasContent
        DiscoverSection.Suggestions -> suggestions.hasContent
        DiscoverSection.Upcoming -> upcoming.hasContent
    }

private val DiscoverListState<*>.hasContent: Boolean
    get() = (this as? DiscoverListState.Content<*>)?.items?.isNotEmpty() == true

private val DiscoverSuggestionsState.hasContent: Boolean
    get() = (this as? DiscoverSuggestionsState.Content)?.items?.isNotEmpty() == true
