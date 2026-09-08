// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchFocusTarget: Hashable {
    let rowId: String
    let itemId: String
}

private struct SearchResultsFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

extension FocusedValues {
    var searchResultsFocusOwner: Bool? {
        get { self[SearchResultsFocusOwnerKey.self] }
        set { self[SearchResultsFocusOwnerKey.self] = newValue }
    }
}

struct SearchRibbonView: View {
    let rowId: String
    let title: String
    let cards: [TvMediaCard]
    let style: MediaCardStyle
    let focus: FocusState<SearchFocusTarget?>.Binding
    let restoreTarget: SearchFocusTarget?
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.tvAppearance) private var appearance
    @State private var restoreTask: Task<Void, Never>?
    @State private var isVisible = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, TvDimensions.screenInset)

            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                        ForEach(cards, id: \.id) { card in
                            let target = SearchFocusTarget(rowId: rowId, itemId: card.id)
                            MediaCardView(
                                card: card,
                                width: style == .landscape
                                    ? appearance.cards.landscapeWidth
                                    : appearance.cards.posterWidth,
                                style: style,
                                onPlay: onPlay
                            )
                            .id(target)
                            .focused(focus, equals: target)
                            .focusedValue(\.searchResultsFocusOwner, true)
                        }
                    }
                    .padding(.horizontal, TvDimensions.screenInset)
                    .padding(.vertical, TvDimensions.ribbonFocusReserve)
                }
                .scrollClipDisabled()
                .onAppear {
                    isVisible = true
                    applyRestore(using: proxy)
                }
                .onChange(of: restoreTarget) { _, _ in
                    applyRestore(using: proxy)
                }
                .onDisappear {
                    isVisible = false
                    restoreTask?.cancel()
                }
            }
        }
        .id(rowId)
        .focusSection()
    }

    private func applyRestore(using proxy: ScrollViewProxy) {
        guard let restoreTarget, restoreTarget.rowId == rowId else {
            return
        }
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            for _ in 0..<3 {
                guard !Task.isCancelled, isVisible else {
                    return
                }
                guard let currentTarget = self.restoreTarget,
                      currentTarget.rowId == rowId,
                      cards.contains(where: { $0.id == currentTarget.itemId }) else {
                    return
                }
                proxy.scrollTo(currentTarget, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else {
                    return
                }
                if self.restoreTarget == currentTarget,
                   cards.contains(where: { $0.id == currentTarget.itemId }) {
                    focus.wrappedValue = currentTarget
                    return
                }
            }
        }
    }
}
