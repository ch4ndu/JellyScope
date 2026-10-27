// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
final class PersonModel: ObservableObject {
    @Published private(set) var state: TvPersonState
    private let presenter: TvPersonPresenter
    private var handle: WatchHandle?

    init(session: Session, personId: String) {
        let presenter = TvosEntry.shared.personPresenter(session: session, personId: personId)
        self.presenter = presenter
        self.state = presenter.state.value as! TvPersonState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func load() {
        presenter.accept(intent: TvPersonIntentLoad.shared)
    }

    func retryHeader() {
        presenter.accept(intent: TvPersonIntentRetryHeader.shared)
    }

    func retry() {
        presenter.accept(intent: TvPersonIntentRetry.shared)
    }

    func loadMoreIfNeeded(_ index: Int) {
        presenter.accept(intent: TvPersonIntentLoadMoreIfNeeded(focusedIndex: Int32(index)))
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
