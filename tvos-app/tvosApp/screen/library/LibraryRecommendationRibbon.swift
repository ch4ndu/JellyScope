// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

enum LibraryRecommendationFocusKind: Hashable {
    case card(String)
    case retry
    case status
}

struct LibraryRecommendationFocusTarget: Hashable {
    let rowId: String
    let kind: LibraryRecommendationFocusKind
}

private struct LibraryRecommendationFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

extension FocusedValues {
    var libraryRecommendationFocusOwner: Bool? {
        get { self[LibraryRecommendationFocusOwnerKey.self] }
        set { self[LibraryRecommendationFocusOwnerKey.self] = newValue }
    }
}

struct LibraryRecommendationRibbon: View {
    let row: TvLibraryRecommendationRow
    let focus: FocusState<LibraryRecommendationFocusTarget?>.Binding
    let restoreTarget: LibraryRecommendationFocusTarget?
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.tvAppearance) private var appearance
    @State private var restoreTask: Task<Void, Never>?
    @State private var isVisible = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(row.libraryDisplayTitle)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, TvDimensions.screenInset)

            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                        ForEach(row.items, id: \.id) { card in
                            let target = LibraryRecommendationFocusTarget(
                                rowId: row.stableId,
                                kind: .card(card.id)
                            )
                            let style = cardStyle(for: card)
                            MediaCardView(
                                card: card,
                                width: cardWidth(for: style),
                                style: style,
                                focusedMedia: FocusedMediaValue(rowId: row.stableId, card: card),
                                onPlay: onPlay
                            )
                            .id(target)
                            .focused(focus, equals: target)
                            .focusedValue(\.libraryRecommendationFocusOwner, true)
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
        .focusSection()
    }

    private func cardStyle(for card: TvMediaCard) -> MediaCardStyle {
        if row.section == .continuewatching || row.section == .nextup || card.kind == .episode {
            return .landscape
        }
        return .poster
    }

    private func cardWidth(for style: MediaCardStyle) -> CGFloat {
        style == .landscape ? appearance.cards.landscapeWidth : appearance.cards.posterWidth
    }

    private func applyRestore(using proxy: ScrollViewProxy) {
        guard let restoreTarget, isValid(restoreTarget) else { return }
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled,
                  isVisible,
                  self.restoreTarget == restoreTarget,
                  isValid(restoreTarget)
            else { return }
            proxy.scrollTo(restoreTarget, anchor: .center)
            await Task.yield()
            guard !Task.isCancelled,
                  isVisible,
                  self.restoreTarget == restoreTarget,
                  isValid(restoreTarget)
            else { return }
            focus.wrappedValue = restoreTarget
        }
    }

    private func isValid(_ target: LibraryRecommendationFocusTarget) -> Bool {
        guard target.rowId == row.stableId,
              case let .card(itemId) = target.kind
        else { return false }
        return row.items.contains(where: { card in card.id == itemId })
    }
}
