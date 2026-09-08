// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

enum DownloadDestructiveAction: String, Identifiable {
    case cancel
    case delete

    var id: String { rawValue }
}

struct DownloadConfirmationView: View {
    let row: TvDownloadRow
    let action: DownloadDestructiveAction
    @ObservedObject var model: DownloadsModel
    let onComplete: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 28) {
            Image(systemName: action == .delete ? "trash" : "xmark.circle")
                .font(.system(size: 54))
                .foregroundStyle(.orange)

            Text(action == .delete ? String(localized: "Delete this download?") : String(localized: "Cancel this download?"))
                .font(.title2)

            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            HStack(spacing: 24) {
                Button(String(localized: "Keep Download")) {
                    dismiss()
                }
                Button(action == .delete ? String(localized: "Delete Download") : String(localized: "Cancel Download"), role: .destructive) {
                    if action == .delete {
                        model.delete(row)
                    } else {
                        model.cancel(row)
                    }
                    onComplete()
                }
                .disabled(action == .delete && row.isLeased)
            }
        }
        .padding(70)
        .frame(minWidth: 700)
    }

    private var message: String {
        if action == .delete && row.isLeased {
            return String(localized: "This copy is playing now. Stop playback before deleting it.")
        }
        return action == .delete
            ? String(localized: "The local media and its download record will be removed from this Apple TV.")
            : String(localized: "Downloaded data for this unfinished copy will be removed.")
    }
}
