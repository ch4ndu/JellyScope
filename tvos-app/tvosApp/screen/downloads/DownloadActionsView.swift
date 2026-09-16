// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

/// State-valid local actions embedded in the offline detail destination.
struct DownloadActionsView: View {
    let row: TvDownloadRow
    @ObservedObject var model: DownloadsModel
    let onPlay: (Bool) -> Void

    @State private var destructiveAction: DownloadDestructiveAction?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 18) {
                if row.canPlay {
                    Button {
                        onPlay(false)
                    } label: {
                        Label(
                            row.localResumePositionMs > 0 ? String(localized: "Resume") : String(localized: "Play"),
                            systemImage: "play.fill"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                }
                if row.canRestart {
                    Button {
                        onPlay(true)
                    } label: {
                        Label(String(localized: "Play from Beginning"), systemImage: "backward.end.fill")
                    }
                }
                if row.canPause {
                    Button {
                        model.pause(row)
                    } label: {
                        Label(String(localized: "Pause"), systemImage: "pause.fill")
                    }
                    .disabled(isCommandBusy)
                }
                if row.canResume {
                    Button {
                        model.resume(row)
                    } label: {
                        Label(String(localized: "Resume Download"), systemImage: "play.fill")
                    }
                    .disabled(isCommandBusy)
                }
                if row.canRetry {
                    Button {
                        model.retry(row)
                    } label: {
                        Label(String(localized: "Retry"), systemImage: "arrow.clockwise")
                    }
                    .disabled(isCommandBusy)
                }
            }

            if row.canCancel || row.state == .completed {
                HStack(spacing: 18) {
                    if row.canCancel {
                        Button(role: .destructive) {
                            destructiveAction = .cancel
                        } label: {
                            Label(String(localized: "Cancel Download"), systemImage: "xmark.circle")
                        }
                        .disabled(isCommandBusy)
                    }
                    if row.state == .completed {
                        Button(role: .destructive) {
                            destructiveAction = .delete
                        } label: {
                            Label(String(localized: "Delete Download"), systemImage: "trash")
                        }
                        .disabled(!row.canDelete || isCommandBusy)
                    }
                }
                if row.isLeased {
                    Text(String(localized: "Stop local playback before deleting this download."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if isCommandBusy {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(String(localized: "Loading downloads…"))
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityElement(children: .combine)
            }
        }
        .sheet(item: $destructiveAction) { action in
            DownloadConfirmationView(row: row, action: action, model: model) {
                destructiveAction = nil
            }
        }
    }

    private var isCommandBusy: Bool {
        model.state.inFlightDownloadId != nil ||
            model.state.isBulkResumeInFlight ||
            model.state.isQueueWakeInFlight
    }
}
