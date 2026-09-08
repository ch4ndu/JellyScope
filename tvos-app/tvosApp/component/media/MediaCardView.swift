// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

enum MediaCardStyle: Equatable {
    case poster
    case landscape

    func height(for width: CGFloat) -> CGFloat {
        switch self {
        case .poster: width * 1.5
        case .landscape: width * 9 / 16
        }
    }
}

struct MediaCardView: View {
    let card: TvMediaCard
    var width: CGFloat = 260
    var style: MediaCardStyle = .poster
    var focusedMedia: FocusedMediaValue?
    var onPlay: ((TvMediaCard) -> Void)?

    @Environment(\.tvAppearance) private var appearance

    init(
        card: TvMediaCard,
        width: CGFloat = 260,
        style: MediaCardStyle = .poster,
        focusedMedia: FocusedMediaValue? = nil,
        onPlay: ((TvMediaCard) -> Void)? = nil
    ) {
        self.card = card
        self.width = width
        self.style = style
        self.focusedMedia = focusedMedia
        self.onPlay = onPlay
    }

    private var imageHeight: CGFloat {
        style.height(for: width)
    }

    private var isHomeVariant: Bool {
        focusedMedia?.rowId.isEmpty == false || onPlay != nil || style == .landscape
    }

    @ViewBuilder
    var body: some View {
        if let onPlay {
            link
                .onPlayPauseCommand {
                    onPlay(card)
                }
        } else {
            link
        }
    }

    @ViewBuilder
    private var link: some View {
        if let focusedMedia {
            baseLink
                .focusedValue(\.focusedMedia, focusedMedia)
        } else {
            baseLink
        }
    }

    private var baseLink: some View {
        NavigationLink(value: MediaRoute(itemId: card.id)) {
            VStack(alignment: .leading, spacing: isHomeVariant ? 9 : 8) {
                artwork
                Text(card.title)
                    .font(isHomeVariant ? .callout.weight(.semibold) : .caption)
                    .lineLimit(1)
                if isHomeVariant {
                    Text(cardSubtitle ?? " ")
                        .font(.caption)
                        .foregroundStyle(appearance.secondaryText)
                        .lineLimit(1)
                } else if let cardSubtitle {
                    Text(cardSubtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
        }
        .buttonStyle(.card)
    }

    private var artwork: some View {
        ZStack(alignment: .bottom) {
            RemoteImage(url: artworkUrl)
                .frame(width: width, height: imageHeight)

            if let progress = card.progressPercent?.doubleValue, progress > 0 {
                GeometryReader { proxy in
                    Rectangle()
                        .fill(appearance.accent)
                        .frame(
                            width: proxy.size.width * min(max(progress, 0), 100) / 100,
                            height: TvDimensions.progressHeight
                        )
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
            }

            HStack(spacing: 8) {
                Spacer()
                if isHomeVariant && card.isFavorite {
                    Image(systemName: "heart.fill")
                        .foregroundStyle(.white, Color.pink)
                }
                if card.played {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.white, appearance.accent)
                }
            }
            .font(isHomeVariant ? .body : .title3)
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        }
        .frame(width: width, height: imageHeight)
        .clipShape(RoundedRectangle(cornerRadius: isHomeVariant ? TvDimensions.cardCornerRadius : 12))
    }

    private var artworkUrl: String? {
        switch style {
        case .landscape: card.backdropUrl ?? card.imageUrl
        case .poster: card.imageUrl
        }
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

extension TvMediaCard {
    var isDirectlyPlayable: Bool {
        kind == .movie || kind == .episode
    }
}
