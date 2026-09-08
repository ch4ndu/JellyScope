// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct PlayerDiagnosticsView: View {
    @ObservedObject var model: PlaybackModel
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Playback Info")
                .font(.title2.bold())
            if model.diagnostics.isEmpty {
                ProgressView()
            } else {
                ForEach(model.diagnostics, id: \.field.name) { row in
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
        .onAppear { model.setDiagnosticsVisible(true) }
        .onDisappear { model.setDiagnosticsVisible(false) }
        .onExitCommand(perform: onClose)
    }
}
