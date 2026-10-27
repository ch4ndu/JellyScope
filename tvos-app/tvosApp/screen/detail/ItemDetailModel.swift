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
        presenter.accept(intent: TvItemDetailIntentLoad.shared)
    }

    func selectVersion(_ mediaSourceId: String) {
        presenter.accept(intent: TvItemDetailIntentSelectVersion(mediaSourceId: mediaSourceId))
    }

    func selectAudio(_ streamIndex: Int32) {
        presenter.accept(intent: TvItemDetailIntentSelectAudio(streamIndex: streamIndex))
    }

    func selectSubtitle(_ streamIndex: Int32?) {
        presenter.accept(intent: TvItemDetailIntentSelectSubtitle(streamIndex: streamIndex.map { KotlinInt(int: $0) }))
    }

    func selectLocalSubtitle(_ assetId: String?) {
        presenter.accept(intent: TvItemDetailIntentSelectLocalSubtitle(assetId: assetId))
    }

    func selectSubtitleTrack(_ streamIndex: Int32) {
        selectSubtitle(streamIndex)
    }

    func selectSeason(_ seasonId: String) {
        presenter.accept(intent: TvItemDetailIntentSelectSeason(seasonId: seasonId))
    }

    func retrySeasons() {
        presenter.accept(intent: TvItemDetailIntentRetrySeasons.shared)
    }

    func retryEpisodes() {
        presenter.accept(intent: TvItemDetailIntentRetryEpisodes.shared)
    }

    func togglePlayed() {
        presenter.accept(intent: TvItemDetailIntentTogglePlayed.shared)
    }

    func toggleFavorite() {
        presenter.accept(intent: TvItemDetailIntentToggleFavorite.shared)
    }

    func toggleEpisodePlayed(_ itemId: String) {
        presenter.accept(intent: TvItemDetailIntentToggleEpisodePlayed(itemId: itemId))
    }

    func toggleEpisodeFavorite(_ itemId: String) {
        presenter.accept(intent: TvItemDetailIntentToggleEpisodeFavorite(itemId: itemId))
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
