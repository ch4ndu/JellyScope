// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct LocalSubtitleRow: View {
    let subtitle: TvLocalSubtitle
    let selecting: Bool
    let deleting: Bool
    let syncing: Bool
    let deleteFailed: Bool
    let syncFailed: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void
    let onRetrySync: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 20) {
                Button(action: onSelect) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Text(subtitle.label)
                            if subtitle.selected {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                                    .accessibilityLabel(String(localized: "Selected"))
                            }
                        }
                        Text(subtitle.localizedMetadata)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(selecting || deleting || syncing)

                Spacer()

                if subtitle.canRetrySync {
                    Button(action: onRetrySync) {
                        if syncing {
                            ProgressView()
                        } else {
                            Label(String(localized: "Retry Sync"), systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(selecting || deleting || syncing)
                }

                Button(role: .destructive, action: onDelete) {
                    if deleting {
                        ProgressView()
                    } else {
                        Label(String(localized: "Delete"), systemImage: "trash")
                    }
                }
                .disabled(selecting || deleting || syncing)
            }

            if deleteFailed {
                Text(String(localized: "The downloaded subtitle could not be deleted."))
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            if syncFailed {
                Text(String(localized: "Subtitle sync could not be retried."))
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }
}
