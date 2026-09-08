// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct TvCardDimensions {
    let posterWidth: CGFloat
    let landscapeWidth: CGFloat
    let statusWidth: CGFloat
    let statusHeight: CGFloat
    let peopleWidth: CGFloat
    let detailPosterWidth: CGFloat
    let episodeWidth: CGFloat
    let episodeHeight: CGFloat
}

enum TvDimensions {
    static let screenInset: CGFloat = 60
    static let screenBottomInset: CGFloat = 72
    static let heroHeight: CGFloat = 430
    static let backdropHeight: CGFloat = 760
    static let heroLogoWidth: CGFloat = 430
    static let heroLogoHeight: CGFloat = 132
    static let heroTextWidth: CGFloat = 700
    static let ribbonSpacing: CGFloat = 38
    static let ribbonCardSpacing: CGFloat = 34
    static let ribbonFocusReserve: CGFloat = 28
    static let cardCornerRadius: CGFloat = 14
    static let progressHeight: CGFloat = 6

    static let mediumCards = TvCardDimensions(
        posterWidth: 230,
        landscapeWidth: 370,
        statusWidth: 370,
        statusHeight: 150,
        peopleWidth: 180,
        detailPosterWidth: 270,
        episodeWidth: 360,
        episodeHeight: 203
    )

    static func cards(for tileSize: TileSizeId) -> TvCardDimensions {
        let scale: CGFloat =
            switch tileSize.name {
            case "Small": 0.85
            case "Large": 1.15
            default: 1
            }
        return TvCardDimensions(
            posterWidth: mediumCards.posterWidth * scale,
            landscapeWidth: mediumCards.landscapeWidth * scale,
            statusWidth: mediumCards.statusWidth * scale,
            statusHeight: mediumCards.statusHeight * scale,
            peopleWidth: mediumCards.peopleWidth * scale,
            detailPosterWidth: mediumCards.detailPosterWidth * scale,
            episodeWidth: mediumCards.episodeWidth * scale,
            episodeHeight: mediumCards.episodeHeight * scale
        )
    }
}
