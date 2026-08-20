// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

/// Cinematic home hero: a full-height section whose background crossfades to
/// the focused card's backdrop (debounced so fast D-pad sweeps don't thrash),
/// with the focused item's logo (or title) above a row of landscape cards.
struct HeroSection: View {
    let hero: TvHeroRow

    @FocusState private var focusedCardId: String?
    @State private var displayedCard: TvMediaCard?
    @State private var debounceTask: Task<Void, Never>?

    private var activeCard: TvMediaCard? {
        displayedCard ?? hero.items.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer(minLength: 60)
            if let card = activeCard {
                heroHeadline(for: card)
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(hero.items, id: \.id) { card in
                        heroCard(card)
                            .focused($focusedCardId, equals: card.id)
                    }
                }
                .padding(.vertical, 30)
            }
            .scrollClipDisabled()
        }
        .padding(.horizontal, 60)
        .frame(maxWidth: .infinity, minHeight: 760, alignment: .leading)
        .background(alignment: .center) {
            heroBackdrop
        }
        .onChange(of: focusedCardId) { _, cardId in
            guard let cardId, let card = hero.items.first(where: { $0.id == cardId }) else { return }
            debounceTask?.cancel()
            debounceTask = Task {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.5)) {
                    displayedCard = card
                }
            }
        }
    }

    private var heroBackdrop: some View {
        ZStack {
            RemoteImage(url: activeCard?.backdropUrl ?? activeCard?.imageUrl)
                .id(activeCard?.id ?? "none")
                .transition(.opacity)
            LinearGradient(
                colors: [.clear, .black.opacity(0.55), .black],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .ignoresSafeArea()
    }

    private func heroHeadline(for card: TvMediaCard) -> some View {
        Group {
            if let logoUrl = card.logoUrl {
                RemoteImage(url: logoUrl)
                    .frame(width: 420, height: 130)
            } else {
                Text(card.title)
                    .font(.largeTitle.bold())
                    .lineLimit(1)
            }
        }
    }

    private func heroCard(_ card: TvMediaCard) -> some View {
        NavigationLink(value: MediaRoute(itemId: card.id)) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: card.backdropUrl ?? card.imageUrl)
                    .frame(width: 420, height: 236)
                if let progress = card.progressPercent?.doubleValue, progress > 0 {
                    GeometryReader { proxy in
                        Rectangle()
                            .fill(Color.accentColor)
                            .frame(width: proxy.size.width * progress / 100.0, height: 6)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            }
            .frame(width: 420, height: 236)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.card)
    }
}
