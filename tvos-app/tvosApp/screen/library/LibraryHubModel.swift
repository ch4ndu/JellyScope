// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class LibraryHubModel: ObservableObject {
    @Published private(set) var state: TvLibraryHubState
    private let presenter: TvLibraryHubPresenter
    private var handle: WatchHandle?

    init(session: Session, library: TvLibraryTile) {
        let presenter = TvosEntry.shared.libraryHubPresenter(session: session, library: library)
        self.presenter = presenter
        self.state = presenter.state.value as! TvLibraryHubState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func selectView(_ view: LibraryInnerView) {
        presenter.selectView(view: view)
    }

    func retryRecommendation(_ section: LibraryRecommendationSection) {
        presenter.retryRecommendation(section: section)
    }

    func refreshRecommendedSilently() {
        presenter.refreshRecommendedSilently()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
