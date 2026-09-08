// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
final class ItemDetailModel: ObservableObject {
    @Published private(set) var state: TvItemDetailState
    private let presenter: TvItemDetailPresenter
    private var handle: WatchHandle?

    init(session: Session, itemId: String, initialSeasonId: String? = nil) {
        let presenter = TvosEntry.shared.itemDetailPresenter(
            session: session,
            itemId: itemId,
            initialSeasonId: initialSeasonId
        )
        self.presenter = presenter
        self.state = presenter.state.value as! TvItemDetailState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func load() {
        presenter.load()
    }

    func selectVersion(_ mediaSourceId: String) {
        presenter.selectVersion(mediaSourceId: mediaSourceId)
    }

    func selectAudio(_ streamIndex: Int32) {
        presenter.selectAudio(streamIndex: streamIndex)
    }

    func selectSubtitle(_ streamIndex: Int32?) {
        presenter.selectSubtitle(streamIndex: streamIndex.map { KotlinInt(int: $0) })
    }

    func selectLocalSubtitle(_ assetId: String?) {
        presenter.selectLocalSubtitle(assetId: assetId)
    }

    func selectSubtitleTrack(_ streamIndex: Int32) {
        selectSubtitle(streamIndex)
    }

    func selectSeason(_ seasonId: String) {
        presenter.selectSeason(seasonId: seasonId)
    }

    func retrySeasons() {
        presenter.retrySeasons()
    }

    func retryEpisodes() {
        presenter.retryEpisodes()
    }

    func togglePlayed() {
        presenter.togglePlayed()
    }

    func toggleFavorite() {
        presenter.toggleFavorite()
    }

    func toggleEpisodePlayed(_ itemId: String) {
        presenter.toggleEpisodePlayed(itemId: itemId)
    }

    func toggleEpisodeFavorite(_ itemId: String) {
        presenter.toggleEpisodeFavorite(itemId: itemId)
    }

    func downloadSelection() -> TvDetailPlaybackSelection? {
        presenter.playbackSelection(restart: true)
    }

    func playbackRoute(restart: Bool = false) -> PlaybackRoute? {
        presenter.playbackSelection(restart: restart)?.playbackRoute
    }

    func nextUpPlaybackRoute() -> PlaybackRoute? {
        presenter.nextUpPlaybackSelection()?.playbackRoute
    }

    func episodePlaybackRoute(itemId: String, restart: Bool = false) -> PlaybackRoute? {
        presenter.episodePlaybackSelection(itemId: itemId, restart: restart)?.playbackRoute
    }

    func episodePlayTitle(itemId: String) -> String {
        presenter.episodePlayMode(itemId: itemId).actionTitle
    }

    func episodeCanRestart(itemId: String) -> Bool {
        presenter.episodeCanRestart(itemId: itemId)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
