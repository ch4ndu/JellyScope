// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SubtitleResultRow: View {
    let result: TvOpenSubtitleResult
    let installing: Bool
    let installDisabled: Bool
    let onInstall: () -> Void

    var body: some View {
        Button(action: onInstall) {
            HStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(result.title)
                    Text(result.localizedMetadata)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if installing {
                    ProgressView()
                } else if result.available {
                    Text(String(localized: "Install"))
                        .foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!result.available || installDisabled)
    }
}
