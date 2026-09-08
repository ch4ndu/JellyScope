// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct HomeViewAllView: View {
    let row: TvHomeRowKind
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.tvAppearance) private var appearance
    @StateObject private var model: HomeViewAllModel
    @FocusState private var focusedCardId: String?
    @State private var lastFocusedCardId: String?
    @State private var restoreTask: Task<Void, Never>?
    @State private var isVisible = false

    init(session: Session, row: TvHomeRowKind, onPlay: @escaping (TvMediaCard) -> Void) {
        self.row = row
        self.onPlay = onPlay
        _model = StateObject(wrappedValue: HomeViewAllModel(session: session, row: row))
    }

    var body: some View {
        Group {
            switch model.state.status {
            case .loading:
                status(
                    .loading,
                    title: String(localized: "Loading…"),
                    actionTitle: nil,
                    action: {}
                )
            case .error:
                status(
                    .error,
                    title: String(localized: "Could not load this row."),
                    actionTitle: String(localized: "Retry"),
                    action: model.retry
                )
            case .empty:
                status(
                    .empty,
                    title: String(localized: "No titles found."),
                    actionTitle: nil,
                    action: {}
                )
            case .content:
                ZStack(alignment: .top) {
                    grid
                    refreshStatus
                }
            default:
                EmptyView()
            }
        }
        .background(appearance.background.ignoresSafeArea())
        .navigationTitle(row.displayTitle)
        .onChange(of: focusedCardId) { _, cardId in
            guard let cardId else { return }
            lastFocusedCardId = cardId
        }
        .onAppear {
            isVisible = true
            model.viewAppeared()
        }
        .onDisappear {
            isVisible = false
            restoreTask?.cancel()
        }
    }

    private var grid: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: cardWidth), spacing: TvDimensions.ribbonCardSpacing)],
                    spacing: TvDimensions.ribbonSpacing
                ) {
                    ForEach(model.state.items, id: \.id) { card in
                        MediaCardView(card: card, width: cardWidth, style: cardStyle, onPlay: onPlay)
                            .id(card.id)
                            .focused($focusedCardId, equals: card.id)
                    }
                }
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.vertical, TvDimensions.ribbonFocusReserve)
            }
            .scrollClipDisabled()
            .onAppear {
                isVisible = true
                restoreFocus(using: proxy)
            }
            .onChange(of: itemIdentity) { _, _ in
                guard let focusedCardId,
                      !model.state.items.contains(where: { card in card.id == focusedCardId })
                else { return }
                restoreFocus(using: proxy)
            }
        }
    }

    private var itemIdentity: String {
        model.state.items.map(\.id).joined(separator: ",")
    }

    private var cardStyle: MediaCardStyle {
        row == .continuewatching || row == .nextup ? .landscape : .poster
    }

    private var cardWidth: CGFloat {
        cardStyle == .landscape ? appearance.cards.landscapeWidth : appearance.cards.posterWidth
    }

    @ViewBuilder
    private var refreshStatus: some View {
        if model.state.isRefreshing {
            ProgressView(String(localized: "Refreshing…"))
                .padding(16)
                .background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 20)
        } else if model.state.error != nil {
            HStack(spacing: 18) {
                Label(
                    String(localized: "Could not refresh this row."),
                    systemImage: "exclamationmark.triangle"
                )
                Button(String(localized: "Retry"), action: model.retryRefresh)
            }
            .padding(16)
            .background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 12))
            .padding(.top, 20)
        }
    }

    private func status(
        _ status: MediaStatus,
        title: String,
        actionTitle: String?,
        action: @escaping () -> Void
    ) -> some View {
        MediaStatusView(
            status: status,
            title: title,
            actionTitle: actionTitle,
            action: action
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func restoreFocus(using proxy: ScrollViewProxy) {
        guard let target =
            lastFocusedCardId.flatMap({ id in
                model.state.items.first(where: { card in card.id == id })?.id
            }) ?? model.state.items.first?.id
        else { return }

        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            guard isVisible else { return }
            proxy.scrollTo(target, anchor: .center)
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            focusedCardId = target
        }
    }
}
