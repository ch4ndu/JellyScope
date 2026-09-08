// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

private enum LibraryBrowseControl: Hashable {
    case sort
    case filter
}

struct LibraryBrowseView: View {
    @ObservedObject var model: LibraryBrowseModel
    let onPlay: (TvMediaCard) -> Void

    @FocusState private var focusedControl: LibraryBrowseControl?
    @State private var showsSort = false
    @State private var showsFilters = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            controls
            LibraryGridView(
                state: model.state,
                onRetry: model.retry,
                onLoadMore: model.loadMoreIfNeeded,
                onPlay: onPlay
            )
        }
        .onAppear {
            model.load()
            model.refreshSilently()
        }
        .sheet(isPresented: $showsSort, onDismiss: restoreSortFocus) {
            LibrarySortView(
                options: model.state.sortOptions,
                selectedSort: model.state.sortBy,
                selectedOrder: model.state.sortOrder,
                onApply: { sortBy, sortOrder in
                    model.setSort(sortBy: sortBy, sortOrder: sortOrder)
                    showsSort = false
                },
                onCancel: { showsSort = false }
            )
        }
        .sheet(isPresented: $showsFilters, onDismiss: dismissFilters) {
            LibraryFilterView(
                state: model.state,
                onToggle: model.toggleDraftFilter,
                onReset: model.resetDraftFilters,
                onApply: {
                    model.applyFilters()
                    showsFilters = false
                },
                onCancel: {
                    model.cancelFilterEditing()
                    showsFilters = false
                },
                onRetryFacets: model.retryFilters
            )
        }
    }

    private var controls: some View {
        HStack(spacing: 20) {
            Button {
                showsSort = true
            } label: {
                Label(
                    model.state.sortBy.libraryDisplayTitle,
                    systemImage: "arrow.up.arrow.down"
                )
            }
            .focused($focusedControl, equals: .sort)

            Button {
                model.beginEditingFilters()
                showsFilters = true
            } label: {
                Label(filterTitle, systemImage: "line.3.horizontal.decrease.circle")
            }
            .focused($focusedControl, equals: .filter)

            Spacer()
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.bottom, 12)
        .focusSection()
    }

    private var filterTitle: String {
        guard model.state.appliedFilterCount > 0 else {
            return String(localized: "Filter")
        }
        return String(
            format: String(localized: "Filter (%lld)"),
            Int64(model.state.appliedFilterCount)
        )
    }

    private func restoreSortFocus() {
        focusedControl = .sort
    }

    private func dismissFilters() {
        model.cancelFilterEditing()
        focusedControl = .filter
    }
}
