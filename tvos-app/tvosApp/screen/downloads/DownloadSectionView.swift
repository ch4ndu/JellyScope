// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadSectionView: View {
    let session: Session
    let section: TvDownloadSection
    let inFlightDownloadId: String?
    let focusedDownloadId: FocusState<String?>.Binding
    let onPlay: (TvDownloadRow) -> Void

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(section.kind.title)
                .font(.title2.weight(.semibold))

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: appearance.cards.posterWidth), spacing: TvDimensions.ribbonCardSpacing)],
                spacing: TvDimensions.ribbonSpacing
            ) {
                ForEach(section.rows, id: \.id) { row in
                    NavigationLink(value: DownloadDetailRoute(downloadId: row.id)) {
                        DownloadRowView(
                            session: session,
                            row: row,
                            isWorking: inFlightDownloadId == row.id,
                            width: appearance.cards.posterWidth
                        )
                    }
                    .buttonStyle(.card)
                    .id(row.id)
                    .focused(focusedDownloadId, equals: row.id)
                    .onPlayPauseCommand {
                        if row.canPlay {
                            onPlay(row)
                        }
                    }
                }
            }
            .padding(.vertical, TvDimensions.ribbonFocusReserve)
        }
    }
}
