// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailMetadataView: View {
    let content: TvItemDetailContent

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let overview = content.overview, !overview.isEmpty {
                Text(overview)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !content.genres.isEmpty {
                LabeledContent(String(localized: "Genres"), value: content.genres.joined(separator: ", "))
            }

            if !content.studios.isEmpty {
                LabeledContent(String(localized: "Studios"), value: content.studios.joined(separator: ", "))
            }

            if content.kind == .episode, let seriesId = content.seriesId {
                HStack(spacing: 18) {
                    NavigationLink(
                        content.seriesName ?? String(localized: "Series"),
                        value: MediaRoute(itemId: seriesId)
                    )
                    if let seasonId = content.seasonId {
                        NavigationLink(
                            String(localized: "Season"),
                            value: SeasonRoute(seriesId: seriesId, seasonId: seasonId)
                        )
                    }
                }
            }

            HStack(spacing: 12) {
                if let rating = content.badges.officialRating {
                    badge(rating)
                }
                if let resolution = content.badges.resolution {
                    badge(resolution)
                }
                if content.kind != .series, let audio = content.badges.audioLayout {
                    badge(audio)
                }
                if content.badges.hasSubtitles {
                    badge(String(localized: "CC"))
                }
                if let critic = content.criticRating?.doubleValue {
                    badge(String(format: "%.0f%%", critic))
                }
            }
        }
        .frame(maxWidth: 980, alignment: .leading)
        .padding(.horizontal, TvDimensions.screenInset)
    }

    private func badge(_ value: String) -> some View {
        Text(value)
            .font(.caption.bold())
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(.white.opacity(0.14), in: Capsule())
    }
}
