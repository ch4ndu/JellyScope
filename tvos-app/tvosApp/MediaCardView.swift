// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

/// Poster/episode card used by shelves and grids: artwork, title, optional
/// watch-progress bar; pushes the item's detail route on select.
struct MediaCardView: View {
    let card: TvMediaCard
    var width: CGFloat = 260

    var body: some View {
        NavigationLink(value: MediaRoute(itemId: card.id)) {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .bottom) {
                    RemoteImage(url: card.imageUrl)
                        .frame(width: width, height: width * 1.5)
                    if let progress = card.progressPercent?.doubleValue, progress > 0 {
                        GeometryReader { proxy in
                            Rectangle()
                                .fill(Color.accentColor)
                                .frame(width: proxy.size.width * progress / 100.0, height: 6)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                    if card.played {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.white, Color.accentColor)
                            .padding(10)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                }
                .frame(width: width, height: width * 1.5)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text(card.title)
                    .font(.caption)
                    .lineLimit(1)
                if let subtitle = cardSubtitle {
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: width)
        }
        .buttonStyle(.card)
    }

    private var cardSubtitle: String? {
        if let episodeLabel = card.episodeLabel, let seriesName = card.seriesName {
            return String(format: String(localized: "%@ • %@"), seriesName, episodeLabel)
        }
        if let seriesName = card.seriesName {
            return seriesName
        }
        if let year = card.productionYear {
            return year.stringValue
        }
        return nil
    }
}

struct MediaShelf: View {
    let title: String
    let cards: [TvMediaCard]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
                .padding(.leading, 4)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 32) {
                    ForEach(cards, id: \.id) { card in
                        MediaCardView(card: card)
                    }
                }
                .padding(.vertical, 24)
            }
            .scrollClipDisabled()
        }
    }
}
