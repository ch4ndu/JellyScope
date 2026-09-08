// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct MediaHeroView: View {
    let card: TvMediaCard?
    var fallbackTitle: String = String(localized: "Home")

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Spacer()
            if let card {
                headline(card)
                metadata(card)
                if let overview = card.overview, !overview.isEmpty {
                    Text(overview)
                        .font(.body)
                        .foregroundStyle(appearance.secondaryText)
                        .lineLimit(3)
                        .frame(maxWidth: TvDimensions.heroTextWidth, alignment: .leading)
                }
            } else {
                Text(fallbackTitle)
                    .font(.largeTitle.bold())
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func headline(_ card: TvMediaCard) -> some View {
        if let logoUrl = card.logoUrl {
            RemoteImage(url: logoUrl, contentMode: .fit, fallbackTitle: card.title)
                .frame(width: TvDimensions.heroLogoWidth, height: TvDimensions.heroLogoHeight)
        } else {
            Text(card.title)
                .font(.largeTitle.bold())
                .lineLimit(2)
                .frame(maxWidth: TvDimensions.heroTextWidth, alignment: .leading)
        }
    }

    private func metadata(_ card: TvMediaCard) -> some View {
        HStack(spacing: 14) {
            if let seriesName = card.seriesName {
                Text(seriesName)
            }
            if let episodeLabel = card.episodeLabel {
                Text(episodeLabel)
            }
            if let year = card.productionYear {
                Text(year.stringValue)
            }
            if let rating = card.officialRating {
                Text(rating)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3)
                    .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 5))
            }
            if let communityRating = card.communityRating?.doubleValue {
                Label(String(format: "%.1f", communityRating), systemImage: "star.fill")
                    .labelStyle(.titleAndIcon)
            }
            if let runtime = card.runtimeMinutes {
                Text(String(format: String(localized: "%@ min"), String(runtime.int64Value)))
            }
        }
        .font(.callout.weight(.medium))
        .foregroundStyle(appearance.secondaryText)
    }
}
