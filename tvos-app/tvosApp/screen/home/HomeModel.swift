// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

@MainActor
final class HomeModel: ObservableObject {
    @Published private(set) var state: TvHomeState
    private let presenter: TvHomePresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.homePresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvHomeState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func reload() {
        presenter.load()
    }

    func retry(_ row: TvHomeRowKind) {
        presenter.retry(row: row)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
