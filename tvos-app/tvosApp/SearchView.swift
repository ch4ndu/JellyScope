// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

/// Sectioned search over the system tvOS search keyboard. The idle state
/// shows recent searches; results group by type (Movies / Shows / Episodes).
struct SearchView: View {
    @StateObject private var model: SearchModel
    @State private var query = ""

    init(session: Session) {
        _model = StateObject(wrappedValue: SearchModel(session: session))
    }

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: 32) {
                if model.state.query.isEmpty {
                    recents
                } else if model.state.isSearching {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(60)
                } else if model.state.hasResults {
                    if !model.state.movies.isEmpty {
                        MediaShelf(title: "Movies", cards: model.state.movies)
                    }
                    if !model.state.shows.isEmpty {
                        MediaShelf(title: "TV Shows", cards: model.state.shows)
                    }
                    if !model.state.episodes.isEmpty {
                        MediaShelf(title: "Episodes", cards: model.state.episodes)
                    }
                } else if model.state.error != nil {
                    Text("Search failed. Check the server connection.")
                        .foregroundStyle(.secondary)
                } else {
                    Text(String(format: String(localized: "No results for “%@”."), model.state.query))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 60)
            .padding(.vertical, 40)
        }
        .searchable(text: $query, prompt: String(localized: "Movies, shows, episodes"))
        .onChange(of: query) { _, text in
            model.setQuery(text)
        }
        .onSubmit(of: .search) {
            model.submit(query)
        }
    }

    private var recents: some View {
        VStack(alignment: .leading, spacing: 20) {
            if !model.state.recentSearches.isEmpty {
                Text("Recent Searches")
                    .font(.headline)
                ForEach(model.state.recentSearches, id: \.self) { recent in
                    Button(recent) {
                        query = recent
                        model.submit(recent)
                    }
                }
                Button("Clear Recent Searches", role: .destructive) {
                    model.clearRecents()
                }
            } else {
                Text("Search your libraries.")
                    .foregroundStyle(.secondary)
            }
        }
    }
}
