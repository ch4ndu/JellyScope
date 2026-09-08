// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct HomeRibbonView: View {
    let row: TvMediaRow
    let focus: FocusState<HomeFocusTarget?>.Binding
    let restoreTarget: HomeFocusTarget?
    let onRetry: () -> Void
    let onViewAll: () -> Void
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.tvAppearance) private var appearance
    @State private var restoreTask: Task<Void, Never>?
    @State private var isVisible = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(row.displayTitle)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, TvDimensions.screenInset)

            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                        switch row.status {
                        case .loading:
                            statusView(
                                status: .loading,
                                title: String(localized: "Loading…"),
                                focusKind: .status,
                                actionTitle: nil,
                                action: {}
                            )
                        case .error:
                            statusView(
                                status: .error,
                                title: String(localized: "Could not load this row."),
                                focusKind: .retry,
                                actionTitle: String(localized: "Retry"),
                                action: onRetry
                            )
                        case .content:
                            ForEach(row.items, id: \.id) { card in
                                let target = HomeFocusTarget(rowId: row.stableId, kind: .card(card.id))
                                let style = cardStyle(for: card)
                                let width = cardWidth(for: style)
                                MediaCardView(
                                    card: card,
                                    width: width,
                                    style: style,
                                    focusedMedia: FocusedMediaValue(rowId: row.stableId, card: card),
                                    onPlay: onPlay
                                )
                                .id(target)
                                .focused(focus, equals: target)
                                .focusedValue(\.homeFocusOwner, true)
                            }
                            viewAllButton
                        case .empty:
                            EmptyView()
                        default:
                            EmptyView()
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
        if row.kind == .continuewatching || row.kind == .nextup || card.kind == .episode {
            return .landscape
        }
        return .poster
    }

    private func cardWidth(for style: MediaCardStyle) -> CGFloat {
        style == .landscape ? appearance.cards.landscapeWidth : appearance.cards.posterWidth
    }

    private var viewAllStyle: MediaCardStyle {
        row.kind == .continuewatching || row.kind == .nextup ? .landscape : .poster
    }

    private var viewAllWidth: CGFloat {
        cardWidth(for: viewAllStyle)
    }

    private var viewAllButton: some View {
        let target = HomeFocusTarget(rowId: row.stableId, kind: .viewAll)
        return Button(action: onViewAll) {
            VStack(spacing: 12) {
                Image(systemName: "rectangle.grid.2x2")
                    .font(.title)
                Text(String(localized: "View All"))
                    .font(.headline)
            }
            .frame(width: viewAllWidth, height: viewAllStyle.height(for: viewAllWidth))
            .background(appearance.surface.opacity(0.84), in: RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
        }
        .buttonStyle(.card)
        .id(target)
        .focused(focus, equals: target)
        .focusedValue(\.homeFocusOwner, true)
    }

    private func statusView(
        status: MediaStatus,
        title: String,
        focusKind: HomeFocusKind,
        actionTitle: String?,
        action: @escaping () -> Void
    ) -> some View {
        let target = HomeFocusTarget(rowId: row.stableId, kind: focusKind)
        return MediaStatusView(
            status: status,
            title: title,
            actionTitle: actionTitle,
            action: action
        )
        .id(target)
        .focused(focus, equals: target)
        .focusedValue(\.homeFocusOwner, true)
    }

    private func applyRestore(using proxy: ScrollViewProxy) {
        guard let restoreTarget, restoreTarget.rowId == row.stableId else { return }
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            guard isVisible else { return }
            proxy.scrollTo(restoreTarget, anchor: .center)
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            focus.wrappedValue = restoreTarget
        }
    }
}

extension TvHomeRowKind {
    var displayTitle: String {
        switch self {
        case .continuewatching: String(localized: "Continue Watching")
        case .favorites: String(localized: "Favorites")
        case .nextup: String(localized: "Next Up")
        case .recentlyadded: String(localized: "Recently Added")
        default: ""
        }
    }
}

extension TvMediaRow {
    var displayTitle: String {
        kind.displayTitle
    }
}
