// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI
import Foundation

struct PlayerNextUpView: View {
    let episode: TvMediaCard?
    let countdownSeconds: Int?
    let onPlayNow: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Up Next")
                .font(.headline)
            if let imageUrl = episode?.imageUrl {
                RemoteImage(url: imageUrl, contentMode: .fill, fallbackTitle: episode?.title)
                    .frame(width: 180, height: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            Text(episode?.title ?? String(localized: "Next Episode"))
                .font(.title2.bold())
            if let detail = episode?.episodeLabel ?? episode?.seriesName {
                Text(detail)
                    .foregroundStyle(.secondary)
            }
            if let countdownSeconds {
                Text(String(format: String(localized: "Playing in %lld seconds"), Int64(countdownSeconds)))
                    .monospacedDigit()
            }
            HStack {
                Button("Play Now", action: onPlayNow)
                Button("Dismiss", action: onDismiss)
            }
        }
        .padding(24)
        .frame(maxWidth: 560, alignment: .leading)
        .background(.black.opacity(0.88), in: RoundedRectangle(cornerRadius: 16))
    }
}
