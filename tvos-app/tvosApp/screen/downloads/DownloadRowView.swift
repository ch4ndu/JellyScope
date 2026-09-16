// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadRowView: View {
    let session: Session
    let row: TvDownloadRow
    let isWorking: Bool
    let width: CGFloat

    private var imageHeight: CGFloat { width * 1.5 }

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        MediaCardContent(
            title: row.title,
            subtitle: row.state == .completed ? row.secondaryTitle : row.state.title,
            width: width
        ) {
            ZStack(alignment: .bottom) {
                DownloadArtworkView(session: session, row: row, role: .poster, fallbackTitle: row.title)
                    .frame(width: width, height: imageHeight)
                if let progress = progress, progress > 0 {
                    GeometryReader { proxy in
                        Rectangle()
                            .fill(appearance.accent)
                            .frame(width: proxy.size.width * progress, height: TvDimensions.progressHeight)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
                HStack {
                    Spacer()
                    if row.localWatched {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.white, appearance.accent)
                    }
                    if isWorking { ProgressView().tint(.white) }
                }
                .font(.title3)
                .padding(10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
            .frame(width: width, height: imageHeight)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private var progress: Double? {
        if row.state == .completed {
            guard let duration = row.durationMs?.doubleValue, duration > 0, row.localResumePositionMs > 0 else { return nil }
            return min(max(Double(row.localResumePositionMs) / duration, 0), 1)
        }
        return row.progressFraction
    }
}
