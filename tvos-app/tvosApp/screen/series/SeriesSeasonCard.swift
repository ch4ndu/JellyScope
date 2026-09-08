// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SeriesSeasonCard: View {
    let seriesId: String
    let season: TvSeason

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        NavigationLink(value: SeasonRoute(seriesId: seriesId, seasonId: season.id)) {
            VStack(alignment: .leading, spacing: 9) {
                RemoteImage(url: season.imageUrl, contentMode: .fill, fallbackTitle: season.name)
                    .frame(
                        width: appearance.cards.posterWidth,
                        height: appearance.cards.posterWidth * 1.5
                    )
                    .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
                Text(season.name)
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)
            }
            .frame(width: appearance.cards.posterWidth, alignment: .leading)
        }
        .buttonStyle(.card)
    }
}
