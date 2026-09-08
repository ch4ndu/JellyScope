// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadActionsView: View {
    let row: TvDownloadRow
    @ObservedObject var model: DownloadsModel
    let onPlay: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var destructiveAction: DownloadDestructiveAction?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if row.canPlay {
                        Button {
                            onPlay(false)
                        } label: {
                            Label(row.localResumePositionMs > 0 ? String(localized: "Resume") : String(localized: "Play"), systemImage: "play.fill")
                        }
                    }
                    if row.canRestart {
                        Button {
                            onPlay(true)
                        } label: {
                            Label(String(localized: "Play from Beginning"), systemImage: "backward.end.fill")
                        }
                    }
                    if row.canPause {
                        actionButton(String(localized: "Pause"), systemImage: "pause.fill") { model.pause(row) }
                    }
                    if row.canResume {
                        actionButton(String(localized: "Resume Download"), systemImage: "play.fill") { model.resume(row) }
                    }
                    if row.canRetry {
                        actionButton(String(localized: "Retry"), systemImage: "arrow.clockwise") { model.retry(row) }
                    }
                }

                if row.canCancel || row.state == .completed {
                    Section {
                        if row.canCancel {
                            Button(role: .destructive) {
                                destructiveAction = .cancel
                            } label: {
                                Label(String(localized: "Cancel Download"), systemImage: "xmark.circle")
                            }
                        }
                        if row.state == .completed {
                            Button(role: .destructive) {
                                destructiveAction = .delete
                            } label: {
                                Label(String(localized: "Delete Download"), systemImage: "trash")
                            }
                            .disabled(!row.canDelete)
                        }
                        if row.isLeased {
                            Text(String(localized: "Stop local playback before deleting this download."))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(row.title)
            .sheet(item: $destructiveAction) { action in
                DownloadConfirmationView(row: row, action: action, model: model) {
                    destructiveAction = nil
                    dismiss()
                }
            }
        }
    }

    private func actionButton(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            action()
            dismiss()
        } label: {
            Label(title, systemImage: systemImage)
        }
    }
}
