// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private struct SeasonEpisodesFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

private extension FocusedValues {
    var seasonEpisodesFocusOwner: Bool? {
        get { self[SeasonEpisodesFocusOwnerKey.self] }
        set { self[SeasonEpisodesFocusOwnerKey.self] = newValue }
    }
}

struct SeasonDetailView: View {
    let session: Session
    @ObservedObject var model: ItemDetailModel
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.seasonEpisodesFocusOwner) private var focusedEpisodesOwner
    @FocusState private var focusedEpisodeId: String?
    @State private var episodesOwnFocus = false
    @State private var lastFocusedEpisodeId: String?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 24) {
                    header
                    episodesContent
                }
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.vertical, 44)
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
            .onChange(of: episodeIdentity) { _, _ in
                guard isVisible, episodesOwnFocus || focusRestorePending else { return }
                if focusedEpisodesOwner == true,
                   let focusedEpisodeId,
                   model.state.episodes.contains(where: { $0.id == focusedEpisodeId }) { return }
                focusRestorePending = true
                restoreFocus(using: proxy)
            }
            .onAppear {
                isVisible = true
                if lastFocusedEpisodeId != nil {
                    focusRestorePending = true
                    restoreFocus(using: proxy)
                }
            }
            .onDisappear {
                isVisible = false
                restoreTask?.cancel()
                focusDepartureTask?.cancel()
                focusRestorePending = false
            }
        }
        .onChange(of: focusedEpisodeId) { _, episodeId in
            guard let episodeId,
                  let index = model.state.episodes.firstIndex(where: { $0.id == episodeId })
            else { return }
            lastFocusedEpisodeId = episodeId
            lastFocusedIndex = index
        }
        .onChange(of: focusedEpisodesOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                episodesOwnFocus = true
                focusRestorePending = false
            } else if episodesOwnFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedEpisodesOwner != true,
                          !focusRestorePending
                    else { return }
                    episodesOwnFocus = false
                    restoreTask?.cancel()
                }
            }
        }
        .background(appearance.background.ignoresSafeArea())
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let seriesTitle = model.state.content?.title {
                Text(seriesTitle)
                    .font(.title2)
                    .foregroundStyle(appearance.secondaryText)
            }
            Text(selectedSeason?.name ?? String(localized: "Episodes"))
                .font(.largeTitle.bold())
        }
    }

    @ViewBuilder
    private var episodesContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            if model.state.seasonsLoading &&
                model.state.seasons.isEmpty &&
                model.state.episodes.isEmpty {
                MediaStatusView(status: .loading, title: String(localized: "Loading…"))
                    .focusedValue(\.seasonEpisodesFocusOwner, true)
            } else {
                if model.state.seasonsError != nil {
                    MediaStatusView(
                        status: .error,
                        title: String(localized: "Could not load seasons."),
                        actionTitle: String(localized: "Retry"),
                        action: model.retrySeasons
                    )
                    .focusedValue(\.seasonEpisodesFocusOwner, true)
                }

                if model.state.seasonsError == nil || !model.state.episodes.isEmpty {
                    episodeRows
                }
            }

            if model.state.actionError != nil {
                Text(String(localized: "The change could not be saved. Please try again."))
                    .font(.callout)
                    .foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder
    private var episodeRows: some View {
        if model.state.episodesLoading && model.state.episodes.isEmpty {
            MediaStatusView(status: .loading, title: String(localized: "Loading…"))
                .focusedValue(\.seasonEpisodesFocusOwner, true)
        } else {
            if model.state.episodesError != nil {
                MediaStatusView(
                    status: .error,
                    title: String(localized: "Could not load episodes."),
                    actionTitle: String(localized: "Retry"),
                    action: model.retryEpisodes
                )
                .focusedValue(\.seasonEpisodesFocusOwner, true)
            } else if model.state.episodesLoading {
                ProgressView()
            }

            if model.state.episodes.isEmpty && model.state.episodesError == nil {
                MediaStatusView(status: .empty, title: String(localized: "No episodes found."))
                    .focusedValue(\.seasonEpisodesFocusOwner, true)
            } else if !model.state.episodes.isEmpty {
                LazyVStack(alignment: .leading, spacing: 22) {
                    ForEach(model.state.episodes, id: \.id) { episode in
                        SeriesEpisodeRow(
                            episode: episode,
                            playTitle: model.episodePlayTitle(itemId: episode.id),
                            canRestart: model.episodeCanRestart(itemId: episode.id),
                            onPlay: {
                                guard let route = model.episodePlaybackRoute(itemId: episode.id) else { return }
                                onPlay(route)
                            },
                            onRestart: {
                                guard let route = model.episodePlaybackRoute(itemId: episode.id, restart: true) else { return }
                                onPlay(route)
                            },
                            onTogglePlayed: { model.toggleEpisodePlayed(episode.id) },
                            onToggleFavorite: { model.toggleEpisodeFavorite(episode.id) }
                        )
                        .id(episode.id)
                        .focused($focusedEpisodeId, equals: episode.id)
                        .focusedValue(\.seasonEpisodesFocusOwner, true)
                        .defaultFocus($focusedEpisodeId, initialEpisodeId)
                    }
                }
            }
        }
    }

    private var selectedSeason: TvSeason? {
        guard let selectedSeasonId = model.state.selectedSeasonId else { return nil }
        return model.state.seasons.first(where: { $0.id == selectedSeasonId })
    }

    private var initialEpisodeId: String? {
        if let nextUpId = model.state.nextUpEpisodeId,
           model.state.episodes.contains(where: { $0.id == nextUpId }) {
            return nextUpId
        }
        return model.state.episodes.first?.id
    }

    private var episodeIdentity: String {
        model.state.episodes.map(\.id).joined(separator: ",")
    }

    private func restoreFocus(using proxy: ScrollViewProxy) {
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            for _ in 0..<3 {
                await Task.yield()
                guard !Task.isCancelled, isVisible, let target = restoredEpisodeId else { return }
                proxy.scrollTo(target, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
                if restoredEpisodeId == target,
                   model.state.episodes.contains(where: { $0.id == target }) {
                    focusedEpisodeId = target
                    return
                }
            }
        }
    }

    private var restoredEpisodeId: String? {
        if let lastFocusedEpisodeId,
           model.state.episodes.contains(where: { $0.id == lastFocusedEpisodeId }) {
            return lastFocusedEpisodeId
        }
        guard !model.state.episodes.isEmpty else { return nil }
        return model.state.episodes[min(lastFocusedIndex ?? 0, model.state.episodes.count - 1)].id
    }
}
