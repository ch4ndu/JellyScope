// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibraryTileView: View {
    let library: TvLibraryTile

    var body: some View {
        NavigationLink(value: LibraryRoute(library: library)) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(
                    url: library.imageUrl,
                    contentMode: .fill,
                    fallbackTitle: library.name
                )
                .frame(height: 240)

                Text(library.name)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.black.opacity(0.58))
            }
            .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
        }
        .buttonStyle(.card)
    }
}
