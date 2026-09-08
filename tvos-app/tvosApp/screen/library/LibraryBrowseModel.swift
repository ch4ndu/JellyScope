// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class LibraryBrowseModel: ObservableObject {
    @Published private(set) var state: TvLibraryBrowseState
    private let presenter: TvLibraryBrowsePresenter
    private var handle: WatchHandle?

    init(session: Session, library: TvLibraryTile) {
        let presenter = TvosEntry.shared.libraryBrowsePresenter(session: session, library: library)
        self.presenter = presenter
        self.state = presenter.state.value as! TvLibraryBrowseState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func load() {
        presenter.load()
    }

    func retry() {
        presenter.retry()
    }

    func loadMoreIfNeeded(focusedIndex: Int) {
        presenter.loadMoreIfNeeded(focusedIndex: Int32(focusedIndex))
    }

    func refreshSilently() {
        presenter.refreshSilently()
    }

    func setSort(sortBy: LibrarySortBy, sortOrder: LibrarySortOrder) {
        presenter.setSort(sortBy: sortBy, sortOrder: sortOrder)
    }

    func beginEditingFilters() {
        presenter.beginEditingFilters()
    }

    func toggleDraftFilter(group: TvLibraryFilterGroupKind, key: String) {
        presenter.toggleDraftFilter(group: group, key: key)
    }

    func resetDraftFilters() {
        presenter.resetDraftFilters()
    }

    func applyFilters() {
        presenter.applyFilters()
    }

    func cancelFilterEditing() {
        presenter.cancelFilterEditing()
    }

    func retryFilters() {
        presenter.retryFilters()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
