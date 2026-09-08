// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailHeroView: View {
    let content: TvItemDetailContent

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        HStack(alignment: .bottom, spacing: 44) {
            RemoteImage(url: content.posterUrl, contentMode: .fill, fallbackTitle: content.title)
                .frame(
                    width: appearance.cards.detailPosterWidth,
                    height: appearance.cards.detailPosterWidth * 1.5
                )
                .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))

            VStack(alignment: .leading, spacing: 18) {
                if let logoUrl = content.logoUrl {
                    RemoteImage(url: logoUrl, contentMode: .fit, fallbackTitle: content.title)
                        .frame(width: TvDimensions.heroLogoWidth, height: TvDimensions.heroLogoHeight)
                        .accessibilityLabel(content.title)
                } else {
                    Text(content.title)
                        .font(.largeTitle.bold())
                        .lineLimit(2)
                }

                DetailHeadlineMetadata(content: content)

                if let tagline = content.tagline, !tagline.isEmpty {
                    Text(tagline)
                        .font(.title3.italic())
                        .foregroundStyle(appearance.secondaryText)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: 760, alignment: .leading)
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.top, 54)
    }
}

private struct DetailHeadlineMetadata: View {
    let content: TvItemDetailContent

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        HStack(spacing: 14) {
            if let seriesName = content.seriesName {
                Text(seriesName)
            }
            if let episodeLabel = content.episodeLabel {
                Text(episodeLabel)
            }
            if let year = content.productionYear {
                Text(year.stringValue)
            }
            if let runtime = content.runtimeMinutes {
                Text(String(format: String(localized: "%@ min"), String(runtime.int64Value)))
            }
            if let rating = content.officialRating {
                Text(rating)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3)
                    .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 5))
            }
            if let community = content.communityRating?.doubleValue {
                Label(String(format: "%.1f", community), systemImage: "star.fill")
            }
        }
        .font(.callout.weight(.medium))
        .foregroundStyle(appearance.secondaryText)
    }
}
