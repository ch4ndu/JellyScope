// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchResultTabsView: View {
    let selected: TvSearchResultCategory
    let onSelect: (TvSearchResultCategory) -> Void

    var body: some View {
        Picker(String(localized: "Results"), selection: selection) {
            ForEach(SearchLabels.resultCategories, id: \.name) { category in
                Text(SearchLabels.resultCategory(category)).tag(category.name)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, TvDimensions.screenInset)
        .focusSection()
    }

    private var selection: Binding<String> {
        Binding(
            get: { selected.name },
            set: { name in
                guard let category = SearchLabels.resultCategories.first(where: { $0.name == name }) else { return }
                onSelect(category)
            }
        )
    }
}
