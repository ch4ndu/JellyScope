// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchContentView: View {
    let state: TvSearchState
    @Binding var query: String
    let onSubmit: () -> Void
    let onSelectRecent: (String) -> Void
    let onRetry: () -> Void
    let onClearRecents: () -> Void
    let onToggleGenre: (TvSearchGenre) -> Void
    let onSetRuntimeBucket: (RuntimeBucket) -> Void
    let onSetWatchedFilter: (WatchedFilter) -> Void
    let onSelectPerson: (TvSearchPerson) -> Void
    let onClearPerson: () -> Void
    let onSelectResultCategory: (TvSearchResultCategory) -> Void
    let onPlay: (TvMediaCard) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                    SearchQuestionnaireView(
                        state: state,
                        onToggleGenre: onToggleGenre,
                        onSetRuntimeBucket: onSetRuntimeBucket,
                        onSetWatchedFilter: onSetWatchedFilter
                    )

                    if state.hasActiveQuery {
                        SearchPersonSuggestionsView(
                            selectedPerson: state.selectedPerson,
                            suggestions: state.personSuggestions,
                            onSelect: onSelectPerson,
                            onClear: onClearPerson
                        )

                        if state.error != nil {
                            MediaStatusView(
                                status: .error,
                                title: String(localized: "Search failed. Check the server connection."),
                                actionTitle: String(localized: "Retry"),
                                action: onRetry
                            )
                            .frame(maxWidth: .infinity)
                        }

                        if state.isSearching {
                            ProgressView(String(localized: "Searching…"))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, state.hasResults ? 12 : 60)
                        }

                        if state.hasResults {
                            SearchResultTabsView(
                                selected: state.selectedResultCategory,
                                onSelect: onSelectResultCategory
                            )
                            if state.hasVisibleResults {
                                SearchResultsView(state: state, scrollProxy: proxy, onPlay: onPlay)
                            } else if !state.isSearching {
                                emptyState
                            }
                        } else if !state.isSearching && state.error == nil {
                            emptyState
                        }
                    } else {
                        RecentSearchesView(
                            searches: state.recentSearches,
                            onSelect: onSelectRecent,
                            onClear: onClearRecents
                        )
                    }
                }
                .padding(.vertical, 40)
            }
        }
        .searchable(text: $query, prompt: String(localized: "Movies, shows, episodes"))
        .onSubmit(of: .search) {
            onSubmit()
        }
    }

    private var emptyState: some View {
        MediaStatusView(
            status: .empty,
            title: emptyMessage
        )
        .frame(maxWidth: .infinity)
    }

    private var emptyMessage: String {
        if state.query.isEmpty {
            return String(localized: "No results match these filters.")
        }
        return String(
            format: String(localized: "No results for “%@”."),
            state.query
        )
    }
}
