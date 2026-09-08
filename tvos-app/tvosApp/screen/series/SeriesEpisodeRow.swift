// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SeriesEpisodeRow: View {
    let episode: TvMediaCard
    let playTitle: String
    let canRestart: Bool
    let onPlay: () -> Void
    let onRestart: () -> Void
    let onTogglePlayed: () -> Void
    let onToggleFavorite: () -> Void

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        NavigationLink(value: MediaRoute(itemId: episode.id)) {
            HStack(spacing: 28) {
                RemoteImage(
                    url: episode.backdropUrl ?? episode.imageUrl,
                    contentMode: .fill,
                    fallbackTitle: episode.title
                )
                .frame(
                    width: appearance.cards.episodeWidth,
                    height: appearance.cards.episodeHeight
                )
                .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))

                VStack(alignment: .leading, spacing: 10) {
                    Text(episode.episodeLabel.map { "\($0) — \(episode.title)" } ?? episode.title)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                    if let overview = episode.overview {
                        Text(overview)
                            .font(.callout)
                            .foregroundStyle(appearance.secondaryText)
                            .lineLimit(3)
                    }
                    HStack(spacing: 14) {
                        if let runtime = episode.runtimeMinutes {
                            Text(String(format: String(localized: "%@ min"), String(runtime.int64Value)))
                        }
                        if episode.played {
                            Label(String(localized: "Watched"), systemImage: "checkmark.circle.fill")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(appearance.secondaryText)
                }
                Spacer()
            }
            .padding(16)
            .background(appearance.surface.opacity(0.72), in: RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
        }
        .buttonStyle(.card)
        .onPlayPauseCommand(perform: onPlay)
        .contextMenu {
            Button(playTitle, action: onPlay)
            if canRestart {
                Button(String(localized: "Play from Beginning"), action: onRestart)
            }
            Button(
                episode.played ? String(localized: "Mark Unwatched") : String(localized: "Mark Watched"),
                action: onTogglePlayed
            )
            Button(
                episode.isFavorite ? String(localized: "Remove Favorite") : String(localized: "Add Favorite"),
                action: onToggleFavorite
            )
        }
    }
}
