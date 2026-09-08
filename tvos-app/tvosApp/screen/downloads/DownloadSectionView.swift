// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadSectionView: View {
    let section: TvDownloadSection
    let inFlightDownloadId: String?
    let onSelect: (TvDownloadRow) -> Void
    let onPlay: (TvDownloadRow) -> Void

    var body: some View {
        Section(section.kind.title) {
            ForEach(section.rows, id: \.id) { row in
                Button {
                    onSelect(row)
                } label: {
                    DownloadRowView(row: row, isWorking: inFlightDownloadId == row.id)
                }
                .onPlayPauseCommand {
                    if row.canPlay {
                        onPlay(row)
                    }
                }
            }
        }
    }
}
