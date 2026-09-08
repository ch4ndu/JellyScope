// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlaybackHealthAdvisory: View {
    let guidance: PlaybackHealthGuidance
    let model: PlaybackModel
    let onOpenPlaybackSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(message)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                if guidance.canOpenPlaybackSettings {
                    Button("Playback settings", action: onOpenPlaybackSettings)
                }
                if guidance.canDismiss {
                    Button("Dismiss") { model.handlePlaybackAction(PlaybackAction.dismiss) }
                }
            }
        }
        .padding(20)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }

    private var message: String {
        let reason = switch guidance.reason.name {
        case "SlowStartup": String(localized: "Playback is taking longer than expected to start.")
        case "LongBuffering": String(localized: "Playback has been buffering for longer than expected.")
        case "CumulativeBuffering": String(localized: "Playback has spent a lot of time buffering.")
        case "RepeatedStalls": String(localized: "Playback is buffering repeatedly.")
        case "DroppedFrames": String(localized: "Playback is dropping frames.")
        case "NoVideoOutput": String(localized: "Video playback has not produced a displayed picture.")
        default: String(localized: "Playback recovered after a problem.")
        }
        if guidance.reason.name == "SlowStartup" { return reason }
        if guidance.streamMode?.name == "DirectPlay" {
            return String(
                format: String(localized: "%@ This may be a device or connection capacity issue. Consider changing Maximum Quality in Settings after leaving playback."),
                reason
            )
        }
        return String(
            format: String(localized: "%@ This may be streaming pressure. Consider changing Maximum Quality in Settings after leaving playback."),
            reason
        )
    }
}
