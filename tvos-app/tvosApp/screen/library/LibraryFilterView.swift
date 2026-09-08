// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibraryFilterView: View {
    let state: TvLibraryBrowseState
    let onToggle: (TvLibraryFilterGroupKind, String) -> Void
    let onReset: () -> Void
    let onApply: () -> Void
    let onCancel: () -> Void
    let onRetryFacets: () -> Void

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        NavigationStack {
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 28) {
                    facetStatus
                    ForEach(state.filterGroups, id: \.kind.name) { group in
                        VStack(alignment: .leading, spacing: 14) {
                            Text(group.kind.libraryDisplayTitle)
                                .font(.title2.bold())
                            LazyVGrid(
                                columns: [GridItem(.adaptive(minimum: 260), spacing: 16)],
                                spacing: 16
                            ) {
                                ForEach(group.options, id: \.key) { option in
                                    Button {
                                        onToggle(group.kind, option.key)
                                    } label: {
                                        HStack {
                                            Text(option.libraryDisplayTitle)
                                                .lineLimit(1)
                                            Spacer()
                                            if option.isSelected {
                                                Image(systemName: "checkmark")
                                                    .foregroundStyle(appearance.accent)
                                            }
                                        }
                                        .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }
                        }
                    }

                    HStack(spacing: 20) {
                        Button(String(localized: "Cancel"), action: onCancel)
                        Button(String(localized: "Reset"), action: onReset)
                        Button(String(localized: "Apply"), action: onApply)
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(.top, 20)
                }
                .padding(60)
            }
            .navigationTitle(
                state.draftFilterCount > 0
                    ? String(
                        format: String(localized: "Filters (%lld)"),
                        Int64(state.draftFilterCount)
                    )
                    : String(localized: "Filters")
            )
        }
    }

    @ViewBuilder
    private var facetStatus: some View {
        switch state.facetStatus {
        case .loading:
            HStack(spacing: 14) {
                ProgressView()
                Text(String(localized: "Loading filter choices…"))
                    .foregroundStyle(appearance.secondaryText)
            }
        case .error:
            MediaStatusView(
                status: .error,
                title: String(localized: "Could not load all filter choices."),
                actionTitle: String(localized: "Retry"),
                action: onRetryFacets
            )
        default:
            EmptyView()
        }
    }
}
