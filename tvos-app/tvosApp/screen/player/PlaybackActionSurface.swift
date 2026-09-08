// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlaybackActionSurface: View {
    let notice: PlaybackActionNotice
    let availableActions: [PlaybackAction]
    let qualityChoices: [TvQualityChoice]
    let model: PlaybackModel
    let onOpenPlaybackSettings: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(message)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                ForEach(availableActions, id: \.name) { action in
                    actionButton(action)
                }
            }
        }
        .padding(24)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func actionButton(_ action: PlaybackAction) -> some View {
        if action === PlaybackAction.acceptauto || action === PlaybackAction.clearqualityoverride {
            Button("Use Auto") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.keepcurrentquality {
            Button("Keep this quality") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.chooselowerquality {
            Menu("Choose lower quality") {
                ForEach(qualityChoices.filter { $0.mode.name == "Fixed" }, id: \.recoveryChoiceIdentity) { choice in
                    Button(choice.localizedPlayerLabel) { model.selectQuality(choice) }
                }
            }
        } else if action === PlaybackAction.tryhigherquality {
            Button("Try higher") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.tryoriginal {
            Button("Try Original") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.retry {
            Button("Retry") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.openplaybacksettings {
            Button("Playback settings", action: onOpenPlaybackSettings)
        } else if action === PlaybackAction.dismiss {
            Button("Dismiss") { model.handlePlaybackAction(action) }
        } else if action === PlaybackAction.close {
            Button("Close", action: onClose)
        }
    }

    private var message: String {
        switch notice.reason.name {
        case "OriginalPlaybackFailed": String(localized: "Original playback could not start.")
        case "FixedQualityFailed": String(localized: "The selected quality could not play.")
        case "QualityRecoveryApplied": String(localized: "Playback recovered at a lower quality for this session.")
        default: String(localized: "Playback needs your choice before trying another stream.")
        }
    }
}

private extension TvQualityChoice {
    var recoveryChoiceIdentity: String {
        "\(mode.name):\(maxBitrateBps?.int64Value ?? -1)"
    }
}
