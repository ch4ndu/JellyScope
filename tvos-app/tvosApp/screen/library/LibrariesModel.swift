// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class LibrariesModel: ObservableObject {
    @Published private(set) var state: TvLibrariesState
    private let presenter: TvLibrariesPresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.librariesPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvLibrariesState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func reload() {
        presenter.load()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
