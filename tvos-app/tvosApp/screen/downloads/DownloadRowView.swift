// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadRowView: View {
    let row: TvDownloadRow
    let isWorking: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.title)
                        .font(.headline)
                    if let secondaryTitle = row.secondaryTitle {
                        Text(secondaryTitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if isWorking {
                    ProgressView()
                } else {
                    Text(row.state.title)
                        .foregroundStyle(.secondary)
                }
            }

            if row.state == .downloading || row.state == .finalizing {
                ProgressView(value: row.progressFraction)
            }

            HStack(spacing: 12) {
                Text(String(format: String(localized: "%@ of %@"), DownloadLabels.bytes(row.physicalBytes), DownloadLabels.bytes(row.expectedBytes)))
                if let sourceLabel = row.sourceLabel, !sourceLabel.isEmpty {
                    Text(sourceLabel)
                }
                if let bitrate = row.qualityBitrateBps?.int64Value {
                    Text(String(format: String(localized: "%.1f Mbps"), Double(bitrate) / 1_000_000))
                } else {
                    Text(String(localized: "Original"))
                }
                if row.canPlay && row.localResumePositionMs > 0 {
                    Text(String(format: String(localized: "Resume at %@"), DownloadLabels.duration(milliseconds: row.localResumePositionMs)))
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)

            if let failure = row.failure {
                Text(failure.message)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            } else if row.state == .completed && !row.canPlay {
                Text(String(localized: "The local media is unavailable. Delete this record and download it again."))
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 8)
    }
}
