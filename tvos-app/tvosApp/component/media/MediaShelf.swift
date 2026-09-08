// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

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
