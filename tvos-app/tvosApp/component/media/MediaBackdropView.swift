// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct MediaBackdropView: View {
    let card: TvMediaCard?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        ZStack {
            appearance.background
            if let card, let url = card.backdropUrl ?? card.imageUrl {
                RemoteImage(url: url)
                    .id(card.id)
                    .transition(.opacity)
            }
            appearance.backdropSideScrim
            appearance.backdropScrim
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.45), value: card?.id)
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
