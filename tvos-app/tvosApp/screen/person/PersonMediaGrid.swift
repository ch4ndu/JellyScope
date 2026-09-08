// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private struct PersonMediaFocusTarget: Hashable {
    let section: String
    let itemId: String
}

private struct PersonMediaFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

private extension FocusedValues {
    var personMediaFocusOwner: Bool? {
        get { self[PersonMediaFocusOwnerKey.self] }
        set { self[PersonMediaFocusOwnerKey.self] = newValue }
    }
}

struct PersonMediaGrid: View {
    let state: TvPersonState
    let scrollProxy: ScrollViewProxy
    let onRetry: () -> Void
    let onLoadMore: (Int) -> Void
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.personMediaFocusOwner) private var focusedMediaOwner
    @FocusState private var focusedTarget: PersonMediaFocusTarget?
    @State private var mediaOwnsFocus = false
    @State private var lastFocusedTarget: PersonMediaFocusTarget?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    var body: some View {
        LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
            if state.movies.isEmpty && state.shows.isEmpty {
                emptyContent
                    .focusedValue(\.personMediaFocusOwner, true)
            } else {
                if !state.movies.isEmpty {
                    section(title: String(localized: "Movies"), key: "movies", cards: state.movies, baseIndex: 0)
                }
                if !state.shows.isEmpty {
                    section(
                        title: String(localized: "Shows"),
                        key: "shows",
                        cards: state.shows,
                        baseIndex: state.movies.count
                    )
                }
                if state.isLoadingMore {
                    ProgressView()
                        .padding(24)
                } else if state.error != nil {
                    MediaStatusView(
                        status: .error,
                        title: String(localized: "Could not load more titles."),
                        actionTitle: String(localized: "Retry"),
                        action: onRetry
                    )
                    .focusedValue(\.personMediaFocusOwner, true)
                }
            }
        }
        .onChange(of: topology) { _, _ in
            guard isVisible, mediaOwnsFocus || focusRestorePending else { return }
            if focusedMediaOwner == true,
               let focusedTarget,
               index(for: focusedTarget) != nil { return }
            focusRestorePending = true
            restoreFocus()
        }
        .onAppear {
            isVisible = true
            if lastFocusedTarget != nil {
                focusRestorePending = true
                restoreFocus()
            }
        }
        .onDisappear {
            isVisible = false
            restoreTask?.cancel()
            focusDepartureTask?.cancel()
            focusRestorePending = false
        }
        .onChange(of: focusedTarget) { _, target in
            guard let target, let index = index(for: target) else { return }
            lastFocusedTarget = target
            lastFocusedIndex = index
            onLoadMore(index)
        }
        .onChange(of: focusedMediaOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                mediaOwnsFocus = true
                focusRestorePending = false
            } else if mediaOwnsFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedMediaOwner != true,
                          !focusRestorePending
                    else { return }
                    mediaOwnsFocus = false
                    restoreTask?.cancel()
                }
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
    }

    private func section(
        title: String,
        key: String,
        cards: [TvMediaCard],
        baseIndex: Int
    ) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.title2.weight(.semibold))
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: appearance.cards.posterWidth), spacing: TvDimensions.ribbonCardSpacing)],
                spacing: TvDimensions.ribbonSpacing
            ) {
                ForEach(Array(cards.enumerated()), id: \.element.id) { offset, card in
                    let target = PersonMediaFocusTarget(section: key, itemId: card.id)
                    MediaCardView(
                        card: card,
                        width: appearance.cards.posterWidth,
                        style: .poster,
                        onPlay: { selected in
                            guard selected.isDirectlyPlayable else { return }
                            onPlay(
                                PlaybackRoute(
                                    itemId: selected.id,
                                    mediaSourceId: nil,
                                    startPositionTicks: selected.playbackPositionTicks
                                )
                            )
                        }
                    )
                    .id(target)
                    .focused($focusedTarget, equals: target)
                    .focusedValue(\.personMediaFocusOwner, true)
                    .defaultFocus($focusedTarget, initialTarget)
                    .onAppear { onLoadMore(baseIndex + offset) }
                }
            }
            .padding(.vertical, TvDimensions.ribbonFocusReserve)
        }
        .focusSection()
    }

    @ViewBuilder
    private var emptyContent: some View {
        if state.isLoading {
            MediaStatusView(status: .loading, title: String(localized: "Loading…"))
        } else if state.error != nil {
            MediaStatusView(
                status: .error,
                title: String(localized: "Could not load filmography."),
                actionTitle: String(localized: "Retry"),
                action: onRetry
            )
        } else {
            MediaStatusView(status: .empty, title: String(localized: "No filmography found."))
        }
    }

    private var initialTarget: PersonMediaFocusTarget? {
        if let card = state.movies.first {
            return PersonMediaFocusTarget(section: "movies", itemId: card.id)
        }
        return state.shows.first.map { PersonMediaFocusTarget(section: "shows", itemId: $0.id) }
    }

    private var topology: String {
        "movies:\(state.movies.map(\.id).joined(separator: ","))|shows:\(state.shows.map(\.id).joined(separator: ","))"
    }

    private func cards(in section: String) -> [TvMediaCard] {
        section == "movies" ? state.movies : state.shows
    }

    private func index(for target: PersonMediaFocusTarget) -> Int? {
        guard let offset = cards(in: target.section).firstIndex(where: { $0.id == target.itemId }) else { return nil }
        return target.section == "movies" ? offset : state.movies.count + offset
    }

    private func restoreFocus() {
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            for _ in 0..<3 {
                await Task.yield()
                guard !Task.isCancelled, isVisible, let target = restoredTarget else { return }
                scrollProxy.scrollTo(target, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
                if restoredTarget == target, index(for: target) != nil {
                    focusedTarget = target
                    return
                }
            }
        }
    }

    private var restoredTarget: PersonMediaFocusTarget? {
        if let lastFocusedTarget, index(for: lastFocusedTarget) != nil {
            return lastFocusedTarget
        }
        let all = state.movies.map { PersonMediaFocusTarget(section: "movies", itemId: $0.id) } +
            state.shows.map { PersonMediaFocusTarget(section: "shows", itemId: $0.id) }
        guard !all.isEmpty else { return nil }
        return all[min(lastFocusedIndex ?? 0, all.count - 1)]
    }
}
