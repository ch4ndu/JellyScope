// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

@MainActor
final class HomeViewAllModel: ObservableObject {
    @Published private(set) var state: TvHomeViewAllState
    private let presenter: TvHomeViewAllPresenter
    private var handle: WatchHandle?
    private var hasAppeared = false

    init(session: Session, row: TvHomeRowKind) {
        let presenter = TvosEntry.shared.homeViewAllPresenter(session: session, row: row)
        self.presenter = presenter
        self.state = presenter.state.value as! TvHomeViewAllState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        presenter.load()
    }

    func retry() {
        presenter.retry()
    }

    func viewAppeared() {
        if hasAppeared {
            presenter.refresh()
        } else {
            hasAppeared = true
        }
    }

    func retryRefresh() {
        presenter.refresh()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
