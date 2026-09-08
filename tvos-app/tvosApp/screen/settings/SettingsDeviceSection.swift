// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsDeviceSection: View {
    @StateObject private var model: DeviceSettingsModel

    private let audioChoices: [TvDeviceAudioChoice] = [.auto_, .stereopcm]
    private let hdrChoices: [TvDeviceHdrChoice] = [.auto_, .prefersdr]

    init() {
        _model = StateObject(wrappedValue: DeviceSettingsModel())
    }

    var body: some View {
        Section(String(localized: "Audio & Video")) {
            Text(String(localized: "These settings apply to online playback on the next play."))
                .font(.footnote)
                .foregroundStyle(.secondary)

            Picker(String(localized: "Audio Mode"), selection: audioBinding) {
                ForEach(audioChoices, id: \.name) { choice in
                    Text(audioLabel(choice)).tag(choice.name)
                }
            }
            .pickerStyle(.navigationLink)
            .disabled(model.state.isLoading)

            Picker(String(localized: "HDR Mode"), selection: hdrBinding) {
                ForEach(hdrChoices, id: \.name) { choice in
                    Text(hdrLabel(choice)).tag(choice.name)
                }
            }
            .pickerStyle(.navigationLink)
            .disabled(model.state.isLoading)

            if model.state.isLoading {
                ProgressView(String(localized: "Loading device capabilities…"))
            } else {
                capabilitySummary
            }

            if model.state.isSaving {
                ProgressView(String(localized: "Saving…"))
            }

            if model.state.policyReadError {
                retryRow(
                    String(localized: "Device capabilities could not be read."),
                    action: model.retryPolicy
                )
            }

            if model.state.saveError {
                retryRow(
                    String(localized: "The audio and video settings could not be saved."),
                    action: model.retrySave
                )
            }

            if let reason = model.state.audioFallbackReason {
                Label(fallbackLabel(reason), systemImage: "info.circle")
                    .foregroundStyle(.secondary)
            }
            if let reason = model.state.hdrFallbackReason {
                Label(fallbackLabel(reason), systemImage: "info.circle")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var capabilitySummary: some View {
        Group {
            LabeledContent(
                String(localized: "Detected Video Codecs"),
                value: codecList(model.state.videoCodecs)
            )
            LabeledContent(
                String(localized: "Detected Audio Codecs"),
                value: codecList(model.state.audioCodecs)
            )
            LabeledContent(
                String(localized: "Effective Audio Channels"),
                value: audioChannelLabel
            )
            LabeledContent(
                String(localized: "Effective Audio Mode"),
                value: audioLabel(model.state.effectiveAudioChoice)
            )
            LabeledContent(
                String(localized: "Effective HDR Mode"),
                value: hdrLabel(model.state.effectiveHdrChoice)
            )
        }
    }

    private var audioBinding: Binding<String> {
        Binding(
            get: { model.state.audioChoice.name },
            set: { name in
                guard let choice = audioChoices.first(where: { $0.name == name }) else { return }
                model.setAudioChoice(choice)
            }
        )
    }

    private var hdrBinding: Binding<String> {
        Binding(
            get: { model.state.hdrChoice.name },
            set: { name in
                guard let choice = hdrChoices.first(where: { $0.name == name }) else { return }
                model.setHdrChoice(choice)
            }
        )
    }

    private var audioChannelLabel: String {
        guard let channels = model.state.maxAudioChannels?.int32Value else {
            return String(localized: "Unknown")
        }
        return String(format: String(localized: "%d channels"), channels)
    }

    private func codecList(_ codecs: [String]) -> String {
        codecs.isEmpty ? String(localized: "Unknown") : codecs.joined(separator: ", ").uppercased()
    }

    private func audioLabel(_ choice: TvDeviceAudioChoice) -> String {
        choice.name == "StereoPcm"
            ? String(localized: "Stereo PCM")
            : String(localized: "Auto")
    }

    private func hdrLabel(_ choice: TvDeviceHdrChoice) -> String {
        choice.name == "PreferSdr"
            ? String(localized: "Prefer SDR")
            : String(localized: "Auto")
    }

    private func fallbackLabel(_ reason: TvDeviceSettingFallbackReason) -> String {
        switch reason.name {
        case "SavedPassthroughUsesAuto":
            String(localized: "The saved passthrough choice is unavailable here, so Audio Auto is used.")
        case "AudioRouteUnsupported":
            String(localized: "The current audio route does not support the saved mode, so Audio Auto is used.")
        default:
            String(localized: "The display does not report HDR support, so Prefer SDR is used.")
        }
    }

    private func retryRow(_ message: String, action: @escaping () -> Void) -> some View {
        LabeledContent {
            Button(String(localized: "Retry"), action: action)
        } label: {
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
    }
}
