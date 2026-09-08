// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct RecentSearchesView: View {
    let searches: [String]
    let onSelect: (String) -> Void
    let onClear: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if searches.isEmpty {
                Text(String(localized: "Search your libraries."))
                    .foregroundStyle(.secondary)
            } else {
                Text(String(localized: "Recent Searches"))
                    .font(.title3.weight(.semibold))
                ForEach(searches, id: \.self) { recent in
                    Button(recent) {
                        onSelect(recent)
                    }
                }
                Button(String(localized: "Clear Recent Searches"), role: .destructive, action: onClear)
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
    }
}
