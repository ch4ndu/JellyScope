// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibrarySortView: View {
    let options: [TvLibrarySortOption]
    let onApply: (LibrarySortBy, LibrarySortOrder) -> Void
    let onCancel: () -> Void

    @Environment(\.tvAppearance) private var appearance
    @State private var selectedSort: LibrarySortBy
    @State private var selectedOrder: LibrarySortOrder

    init(
        options: [TvLibrarySortOption],
        selectedSort: LibrarySortBy,
        selectedOrder: LibrarySortOrder,
        onApply: @escaping (LibrarySortBy, LibrarySortOrder) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.options = options
        self.onApply = onApply
        self.onCancel = onCancel
        _selectedSort = State(initialValue: selectedSort)
        _selectedOrder = State(initialValue: selectedOrder)
    }

    var body: some View {
        NavigationStack {
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 20) {
                    Text(String(localized: "Sort By"))
                        .font(.title2.bold())
                    ForEach(options, id: \.sortBy.name) { option in
                        choice(
                            title: option.sortBy.libraryDisplayTitle,
                            selected: selectedSort == option.sortBy
                        ) {
                            selectedSort = option.sortBy
                        }
                    }

                    Text(String(localized: "Direction"))
                        .font(.title2.bold())
                        .padding(.top, 12)
                    choice(
                        title: LibrarySortOrder.ascending.libraryDisplayTitle,
                        selected: selectedOrder == .ascending
                    ) {
                        selectedOrder = .ascending
                    }
                    choice(
                        title: LibrarySortOrder.descending.libraryDisplayTitle,
                        selected: selectedOrder == .descending
                    ) {
                        selectedOrder = .descending
                    }

                    HStack(spacing: 20) {
                        Button(String(localized: "Cancel"), action: onCancel)
                        Button(String(localized: "Apply")) {
                            onApply(selectedSort, selectedOrder)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.top, 24)
                }
                .padding(60)
            }
            .navigationTitle(String(localized: "Sort"))
        }
    }

    private func choice(
        title: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(appearance.accent)
                }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}
