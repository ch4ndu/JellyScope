// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
final class SubtitlesModel: ObservableObject {
    @Published private(set) var state: TvSubtitlesState
    private let presenter: TvSubtitlesPresenter
    private let onSelection: (String?) -> Void
    private var handle: WatchHandle?
    private var handledSelectionRevision: Int64
    private var closed = false

    init(
        session: Session,
        itemId: String,
        mediaSourceId: String?,
        onSelection: @escaping (String?) -> Void
    ) {
        let presenter = TvosEntry.shared.subtitlesPresenter(
            session: session,
            itemId: itemId,
            mediaSourceId: mediaSourceId
        )
        let initialState = presenter.state.value as! TvSubtitlesState
        self.presenter = presenter
        self.state = initialState
        self.onSelection = onSelection
        self.handledSelectionRevision = initialState.selectionRevision
        self.handle = presenter.watchState { [weak self] state in
            guard let self else { return }
            self.state = state
            if state.selectionRevision > self.handledSelectionRevision {
                self.handledSelectionRevision = state.selectionRevision
                self.onSelection(state.selectionAssetId)
            }
        }
    }

    func reload() {
        presenter.accept(intent: TvSubtitlesIntentLoad.shared)
    }

    func setLanguage(_ language: String) {
        presenter.accept(intent: TvSubtitlesIntentSetLanguage(language: language))
    }

    func retrySearch() {
        presenter.accept(intent: TvSubtitlesIntentSearch.shared)
    }

    func install(_ result: TvOpenSubtitleResult) {
        presenter.accept(intent: TvSubtitlesIntentInstall(fileId: result.fileId))
    }

    func select(_ assetId: String?) {
        presenter.accept(intent: TvSubtitlesIntentSelectLocalSubtitle(assetId: assetId))
    }

    func delete(_ assetId: String) {
        presenter.accept(intent: TvSubtitlesIntentDelete(assetId: assetId))
    }

    func retrySync(_ assetId: String) {
        presenter.accept(intent: TvSubtitlesIntentRetrySync(assetId: assetId))
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        handle = nil
        presenter.close()
    }

    deinit {
        if !closed {
            handle?.close()
            presenter.close()
        }
    }
}
