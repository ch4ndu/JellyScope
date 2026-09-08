// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsPlaybackSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section(String(localized: "Playback")) {
            Picker(String(localized: "Maximum Quality"), selection: qualityBinding) {
                ForEach(model.state.bitrateChoices, id: \.qualityChoiceIdentity) { choice in
                    Text(choice.localizedSettingsLabel).tag(choice.qualityChoiceIdentity)
                }
            }
            .pickerStyle(.navigationLink)
            .disabled(model.state.isLoading)

            Toggle(String(localized: "Autoplay Next Episode"), isOn: autoPlayNextBinding)
                .disabled(model.state.isLoading)

            Picker(String(localized: "Autoplay Delay"), selection: autoPlayDelayBinding) {
                ForEach(0...60, id: \.self) { seconds in
                    Text(delayLabel(seconds)).tag(seconds)
                }
            }
            .pickerStyle(.navigationLink)
            .disabled(model.state.isLoading || !model.state.preferences.autoPlayNext)

            Toggle(String(localized: "Still Watching Prompt"), isOn: stillWatchingBinding)
                .disabled(model.state.isLoading)

            Toggle(String(localized: "Playback Warnings"), isOn: playbackWarningsBinding)
                .disabled(model.state.isLoading)
        }
    }

    private var qualityBinding: Binding<String> {
        Binding(
            get: {
                let policy = model.state.preferences.effectiveDefaultQualityPolicy()
                return "\(policy.mode.name):\(policy.maxBitrateBps?.int64Value ?? -1)"
            },
            set: { value in
                guard let choice = model.state.bitrateChoices.first(where: {
                    $0.qualityChoiceIdentity == value
                }) else {
                    return
                }
                model.setDefaultQualityChoice(choice)
            }
        )
    }

    private var autoPlayNextBinding: Binding<Bool> {
        Binding(
            get: { model.state.preferences.autoPlayNext },
            set: model.setAutoPlayNext
        )
    }

    private var playbackWarningsBinding: Binding<Bool> {
        Binding(
            get: { model.state.preferences.playbackWarningsEnabled },
            set: model.setPlaybackWarningsEnabled
        )
    }

    private var autoPlayDelayBinding: Binding<Int> {
        Binding(
            get: { Int(model.state.autoPlayNextDelaySeconds) },
            set: model.setAutoPlayNextDelaySeconds
        )
    }

    private var stillWatchingBinding: Binding<Bool> {
        Binding(
            get: { model.state.stillWatchingPrompt },
            set: model.setStillWatchingPrompt
        )
    }

    private func delayLabel(_ seconds: Int) -> String {
        guard seconds > 0 else { return String(localized: "Immediately") }
        return String(format: String(localized: "%lld seconds"), Int64(seconds))
    }
}
