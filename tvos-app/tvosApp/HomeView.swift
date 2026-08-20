// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct HomeView: View {
    @StateObject private var model: HomeModel
    @State private var lastDisappear: Date?

    init(session: Session) {
        _model = StateObject(wrappedValue: HomeModel(session: session))
    }

    var body: some View {
        Group {
            if model.state.isLoading && model.state.hero == nil && model.state.rows.isEmpty {
                ProgressView()
            } else if model.state.error != nil && model.state.hero == nil && model.state.rows.isEmpty {
                VStack(spacing: 20) {
                    Text("Could not load your home screen.")
                    Button("Retry") { model.reload() }
                }
            } else if model.state.hero == nil && model.state.rows.isEmpty {
                Text("Your libraries are empty.")
                    .foregroundStyle(.secondary)
            } else {
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 32) {
                        if let hero = model.state.hero {
                            HeroSection(hero: hero)
                        }
                        ForEach(model.state.rows, id: \.stableId) { row in
                            MediaShelf(title: row.displayTitle, cards: row.items)
                                .padding(.horizontal, 60)
                        }
                    }
                    .padding(.bottom, 40)
                }
            }
        }
        .onAppear {
            // First appearance always loads; re-entry refreshes only after 60s
            // Playback sessions exceed that cadence, so Continue
            // Watching stays fresh without hammering quick detail round-trips.
            if lastDisappear.map({ Date().timeIntervalSince($0) > 60 }) ?? true {
                model.reload()
            }
        }
        .onDisappear { lastDisappear = Date() }
    }
}

extension TvMediaRow {
    var displayTitle: String {
        switch kind {
        case .continuewatching: return String(localized: "Continue Watching")
        case .nextup: return String(localized: "Next Up")
        case .recentlyadded: return String(localized: "Recently Added")
        case .favorites: return String(localized: "Favorites")
        case .latestinlibrary:
            guard let libraryName else { return String(localized: "Latest") }
            return String(format: String(localized: "Latest in %@"), libraryName)
        default: return ""
        }
    }
}
