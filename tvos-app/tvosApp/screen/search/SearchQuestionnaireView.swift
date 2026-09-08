// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchQuestionnaireView: View {
    let state: TvSearchState
    let onToggleGenre: (TvSearchGenre) -> Void
    let onSetRuntimeBucket: (RuntimeBucket) -> Void
    let onSetWatchedFilter: (WatchedFilter) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(String(localized: "Find"))
                .font(.title3.weight(.semibold))

            HStack(spacing: 12) {
                ForEach(SearchLabels.genres, id: \.name) { genre in
                    Button {
                        onToggleGenre(genre)
                    } label: {
                        Label(
                            SearchLabels.genre(genre),
                            systemImage: state.selectedGenres.contains(genre) ? "checkmark.circle.fill" : "circle"
                        )
                    }
                }
            }

            HStack(spacing: 28) {
                Picker(String(localized: "Runtime"), selection: runtimeBinding) {
                    ForEach(SearchLabels.runtimeBuckets, id: \.name) { bucket in
                        Text(SearchLabels.runtime(bucket)).tag(bucket.name)
                    }
                }
                .pickerStyle(.navigationLink)

                Picker(String(localized: "Watch Status"), selection: watchedBinding) {
                    ForEach(SearchLabels.watchedFilters, id: \.name) { filter in
                        Text(SearchLabels.watched(filter)).tag(filter.name)
                    }
                }
                .pickerStyle(.navigationLink)
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .focusSection()
    }

    private var runtimeBinding: Binding<String> {
        Binding(
            get: { state.runtimeBucket.name },
            set: { name in
                guard let bucket = SearchLabels.runtimeBuckets.first(where: { $0.name == name }) else { return }
                onSetRuntimeBucket(bucket)
            }
        )
    }

    private var watchedBinding: Binding<String> {
        Binding(
            get: { state.watchedFilter.name },
            set: { name in
                guard let filter = SearchLabels.watchedFilters.first(where: { $0.name == name }) else { return }
                onSetWatchedFilter(filter)
            }
        )
    }
}
