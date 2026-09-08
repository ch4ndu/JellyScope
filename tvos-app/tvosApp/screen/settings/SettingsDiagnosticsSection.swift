// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsDiagnosticsSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section(String(localized: "Diagnostics")) {
            Toggle(String(localized: "Show playback info at start"), isOn: playbackInfoAtStartBinding)
                .disabled(model.state.isLoading)

            Text(String(localized: "Opens playback information once when a new player becomes active."))
                .font(.footnote)
                .foregroundStyle(.secondary)

            if model.state.isSavingPlaybackInfoAtStart {
                ProgressView(String(localized: "Saving…"))
            } else if model.state.playbackInfoAtStartSaveError {
                LabeledContent {
                    Button(String(localized: "Retry"), action: model.retryPlaybackInfoAtStart)
                } label: {
                    Label(
                        String(localized: "The playback information setting could not be saved."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)
                }
            }

            Text(
                String(localized: "Collection is off by default. Sanitized logs stay on this device until you send them to your Jellyfin server. Turning collection off deletes stored logs.")
            )
            .font(.footnote)
            .foregroundStyle(.secondary)

            LabeledContent(
                String(localized: "Captured entries"),
                value: String(model.state.diagnosticEntryCount)
            )
            LabeledContent(
                String(localized: "Captured size"),
                value: String(
                    format: String(localized: "%@ bytes"),
                    String(model.state.diagnosticByteCount)
                )
            )

            Toggle(String(localized: "Collect diagnostic logs"), isOn: diagnosticCollectionBinding)
                .disabled(model.state.isLoading)

            if model.state.diagnosticPreferenceError {
                Label(
                    String(localized: "The diagnostic collection setting could not be saved."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
            }

            Button(String(localized: "Send Client Logs"), action: model.sendClientLogs)
                .disabled(model.state.isLoading || model.state.diagnosticSendResult == .sending)

            diagnosticSendFeedback
        }
    }

    private var diagnosticCollectionBinding: Binding<Bool> {
        Binding(
            get: { model.state.diagnosticCollectionEnabled },
            set: model.setDiagnosticCollectionEnabled
        )
    }

    private var playbackInfoAtStartBinding: Binding<Bool> {
        Binding(
            get: { model.state.playbackInfoAtStartEnabled },
            set: model.setPlaybackInfoAtStartEnabled
        )
    }

    @ViewBuilder
    private var diagnosticSendFeedback: some View {
        switch model.state.diagnosticSendResult {
        case .idle:
            EmptyView()
        case .sending:
            ProgressView(String(localized: "Sending…"))
        case .success:
            Label(String(localized: "Client logs sent."), systemImage: "checkmark.circle")
                .foregroundStyle(.green)
        case .uploaddisallowed:
            Label(
                String(localized: "This Jellyfin server does not allow client logs."),
                systemImage: "exclamationmark.triangle"
            )
            .foregroundStyle(.orange)
        case .failure:
            Label(
                String(localized: "Client logs could not be sent."),
                systemImage: "exclamationmark.triangle"
            )
            .foregroundStyle(.orange)
        default:
            EmptyView()
        }
    }
}
