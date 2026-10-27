// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlayerDiagnosticsView: View {
    let diagnostics: [TvPlaybackDiagnosticRow]
    let onVisibilityChanged: (Bool) -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Playback Info")
                .font(.title2.bold())
            if diagnostics.isEmpty {
                ProgressView()
            } else {
                ForEach(diagnostics, id: \.field.name) { row in
                    HStack(alignment: .firstTextBaseline) {
                        Text(row.field.localizedTitle)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(row.value ?? String(localized: "Unavailable"))
                            .monospacedDigit()
                    }
                }
            }
            Button("Done", action: onClose)
        }
        .padding(36)
        .frame(width: 680)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .onAppear { onVisibilityChanged(true) }
        .onDisappear { onVisibilityChanged(false) }
        .onExitCommand(perform: onClose)
    }
}
