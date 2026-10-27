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
        presenter.accept(intent: TvLibraryBrowseIntentLoad.shared)
    }

    func retry() {
        presenter.accept(intent: TvLibraryBrowseIntentRetry.shared)
    }

    func loadMoreIfNeeded(focusedIndex: Int) {
        presenter.accept(intent: TvLibraryBrowseIntentLoadMoreIfNeeded(focusedIndex: Int32(focusedIndex)))
    }

    func refreshSilently() {
        presenter.accept(intent: TvLibraryBrowseIntentRefreshSilently.shared)
    }

    func setSort(sortBy: LibrarySortBy, sortOrder: LibrarySortOrder) {
        presenter.accept(intent: TvLibraryBrowseIntentSetSort(sortBy: sortBy, sortOrder: sortOrder))
    }

    func beginEditingFilters() {
        presenter.accept(intent: TvLibraryBrowseIntentBeginEditingFilters.shared)
    }

    func toggleDraftFilter(group: TvLibraryFilterGroupKind, key: String) {
        presenter.accept(intent: TvLibraryBrowseIntentToggleDraftFilter(group: group, key: key))
    }

    func resetDraftFilters() {
        presenter.accept(intent: TvLibraryBrowseIntentResetDraftFilters.shared)
    }

    func applyFilters() {
        presenter.accept(intent: TvLibraryBrowseIntentApplyFilters.shared)
    }

    func cancelFilterEditing() {
        presenter.accept(intent: TvLibraryBrowseIntentCancelFilterEditing.shared)
    }

    func retryFilters() {
        presenter.accept(intent: TvLibraryBrowseIntentRetryFilters.shared)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
