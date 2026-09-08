// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

private struct LibraryGridFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

private extension FocusedValues {
    var libraryGridFocusOwner: Bool? {
        get { self[LibraryGridFocusOwnerKey.self] }
        set { self[LibraryGridFocusOwnerKey.self] = newValue }
    }
}

struct LibraryGridView: View {
    let state: TvLibraryBrowseState
    let onRetry: () -> Void
    let onLoadMore: (Int) -> Void
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.libraryGridFocusOwner) private var focusedGridOwner
    @FocusState private var focusedCardId: String?
    @State private var gridOwnsFocus = false
    @State private var lastFocusedCardId: String?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                VStack(spacing: 0) {
                    Color.clear
                        .frame(height: 1)
                        .id("library-grid-top")
                    content
                }
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
            .onAppear {
                isVisible = true
                if lastFocusedCardId != nil {
                    focusRestorePending = true
                    restoreFocus(using: proxy)
                }
            }
            .onChange(of: state.queryRevision) { _, _ in
                restoreTask?.cancel()
                focusDepartureTask?.cancel()
                focusRestorePending = false
                gridOwnsFocus = false
                focusedCardId = nil
                lastFocusedCardId = nil
                lastFocusedIndex = nil
                proxy.scrollTo("library-grid-top", anchor: .top)
            }
            .onChange(of: itemIdentity) { _, _ in
                guard isVisible, gridOwnsFocus || focusRestorePending else { return }
                if focusedGridOwner == true,
                   let focusedCardId,
                   state.items.contains(where: { $0.id == focusedCardId }) {
                    return
                }
                focusRestorePending = true
                restoreFocus(using: proxy)
            }
            .onDisappear {
                isVisible = false
                restoreTask?.cancel()
                focusDepartureTask?.cancel()
                focusRestorePending = false
            }
        }
        .onChange(of: focusedCardId) { _, itemId in
            guard let itemId,
                  let index = state.items.firstIndex(where: { $0.id == itemId })
            else { return }
            lastFocusedCardId = itemId
            lastFocusedIndex = index
            onLoadMore(index)
        }
        .onChange(of: focusedGridOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                gridOwnsFocus = true
                focusRestorePending = false
            } else if gridOwnsFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedGridOwner != true,
                          !focusRestorePending
                    else { return }
                    gridOwnsFocus = false
                    restoreTask?.cancel()
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if state.items.isEmpty {
            emptyContent
                .focusedValue(\.libraryGridFocusOwner, true)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.top, 32)
        } else {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: appearance.cards.posterWidth), spacing: TvDimensions.ribbonCardSpacing)],
                spacing: TvDimensions.ribbonSpacing
            ) {
                ForEach(Array(state.items.enumerated()), id: \.element.id) { index, card in
                    MediaCardView(
                        card: card,
                        width: appearance.cards.posterWidth,
                        style: .poster,
                        focusedMedia: FocusedMediaValue(rowId: "library-grid", card: card),
                        onPlay: onPlay
                    )
                    .id(card.id)
                    .focused($focusedCardId, equals: card.id)
                    .focusedValue(\.libraryGridFocusOwner, true)
                    .onAppear {
                        onLoadMore(index)
                    }
                }
            }
            .padding(.horizontal, TvDimensions.screenInset)
            .padding(.vertical, TvDimensions.ribbonFocusReserve)

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
                .focusedValue(\.libraryGridFocusOwner, true)
                .padding(24)
            }
        }
    }

    @ViewBuilder
    private var emptyContent: some View {
        if state.isLoading {
            MediaStatusView(
                status: .loading,
                title: String(localized: "Loading…")
            )
        } else if state.error != nil {
            MediaStatusView(
                status: .error,
                title: String(localized: "Could not load this library."),
                actionTitle: String(localized: "Retry"),
                action: onRetry
            )
        } else {
            MediaStatusView(
                status: .empty,
                title: String(localized: "No titles found.")
            )
        }
    }

    private var itemIdentity: String {
        state.items.map(\.id).joined(separator: ",")
    }

    private func restoreFocus(using proxy: ScrollViewProxy) {
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            guard var target = restoredCardId else { return }
            proxy.scrollTo(target, anchor: .center)
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            let latestTarget = restoredCardId
            if let latestTarget, latestTarget != target {
                target = latestTarget
                proxy.scrollTo(target, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
            }
            guard state.items.contains(where: { $0.id == target }) else { return }
            focusedCardId = target
        }
    }

    private var restoredCardId: String? {
        if let lastFocusedCardId,
           state.items.contains(where: { $0.id == lastFocusedCardId }) {
            return lastFocusedCardId
        }
        if let lastFocusedIndex,
           !state.items.isEmpty {
            return state.items[min(lastFocusedIndex, state.items.count - 1)].id
        }
        return state.items.first?.id
    }
}
