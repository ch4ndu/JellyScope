// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

/// Playback-preferences settings form: default quality, preferred languages,
/// and per-type segment skip policies — persisted through
/// the shared server-scoped store. Sign-out stays here too.
struct SettingsFormView: View {
    let session: Session
    let onSignOut: () -> Void

    @StateObject private var model: SettingsModel

    init(session: Session, onSignOut: @escaping () -> Void) {
        self.session = session
        self.onSignOut = onSignOut
        _model = StateObject(wrappedValue: SettingsModel(session: session))
    }

    var body: some View {
        Form {
            if model.state.saveError {
                Section {
                    Label("Your last change could not be saved.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }

            Section("Diagnostics") {
                Text("Safe, sanitized breadcrumbs are retained in a bounded on-device store. Collection is disabled by default. Upload occurs only when you press Send to your Jellyfin server. Turning collection off deletes retained safe diagnostics.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                LabeledContent("Captured entries", value: String(model.state.diagnosticEntryCount))
                LabeledContent(
                    "Captured size",
                    value: String(format: String(localized: "%@ bytes"), String(model.state.diagnosticByteCount))
                )
                Toggle("Collect diagnostic logs", isOn: diagnosticCollectionBinding)
                if model.state.diagnosticPreferenceError {
                    Label("The diagnostic collection setting could not be saved.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                Button("Send Client Logs") {
                    model.sendClientLogs()
                }
                .disabled(model.state.diagnosticSendResult == .sending)
                diagnosticSendFeedback
            }

            Section("Playback Quality") {
                Picker("Maximum Quality", selection: bitrateBinding) {
                    ForEach(model.state.bitrateChoices, id: \.qualityChoiceIdentity) { choice in
                        Text(choice.localizedSettingsLabel).tag(choice.qualityChoiceIdentity)
                    }
                }
                .pickerStyle(.navigationLink)
                Toggle("Playback Warnings", isOn: playbackWarningsBinding)
            }

            Section("Languages") {
                languagePicker(
                    title: "Preferred Audio",
                    selected: model.state.preferences.preferredAudioLanguage
                ) { code in
                    model.setPreferredAudioLanguage(code)
                }
                languagePicker(
                    title: "Preferred Subtitles",
                    selected: model.state.preferences.preferredSubtitleLanguage
                ) { code in
                    model.setPreferredSubtitleLanguage(code)
                }
            }

            Section("Skip Segments") {
                segmentPicker("Intros", type: .intro, current: model.state.preferences.introSkip)
                segmentPicker("Outros", type: .outro, current: model.state.preferences.outroSkip)
                segmentPicker("Recaps", type: .recap, current: model.state.preferences.recapSkip)
                segmentPicker("Previews", type: .preview, current: model.state.preferences.previewSkip)
                segmentPicker("Commercials", type: .commercial, current: model.state.preferences.commercialSkip)
            }

            Section("Account") {
                LabeledContent("Server", value: model.state.serverName)
                LabeledContent("User", value: model.state.userName)
                Button("Sign Out", role: .destructive, action: onSignOut)
            }

            Section("About") {
                LabeledContent(
                    "App Version",
                    value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                        ?? String(localized: "Unknown")
                )
                NavigationLink("Source & Open-Source Notices") {
                    OpenSourceNoticesView(sourceRevision: TvosEntry.shared.sourceRevision())
                }
            }
        }
    }

    private var bitrateBinding: Binding<String> {
        Binding(
            get: {
                let policy = model.state.preferences.effectiveDefaultQualityPolicy()
                return "\(policy.mode.name):\(policy.maxBitrateBps?.int64Value ?? -1)"
            },
            set: { value in
                if let choice = model.state.bitrateChoices.first(where: { $0.qualityChoiceIdentity == value }) {
                    model.setDefaultQualityChoice(choice)
                }
            }
        )
    }

    private var playbackWarningsBinding: Binding<Bool> {
        Binding(
            get: { model.state.preferences.playbackWarningsEnabled },
            set: { enabled in model.setPlaybackWarningsEnabled(enabled) }
        )
    }

    private var diagnosticCollectionBinding: Binding<Bool> {
        Binding(
            get: { model.state.diagnosticCollectionEnabled },
            set: { enabled in model.setDiagnosticCollectionEnabled(enabled) }
        )
    }

    @ViewBuilder
    private var diagnosticSendFeedback: some View {
        switch model.state.diagnosticSendResult {
        case .idle:
            EmptyView()
        case .sending:
            ProgressView("Sending…")
        case .success:
            Label("Client logs sent.", systemImage: "checkmark.circle")
                .foregroundStyle(.green)
        case .uploaddisallowed:
            Label("This Jellyfin server does not allow client logs.", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        case .failure:
            Label("Client logs could not be sent.", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        default:
            EmptyView()
        }
    }

    private func languagePicker(
        title: LocalizedStringKey,
        selected: String?,
        onSelect: @escaping (String?) -> Void
    ) -> some View {
        Picker(
            title,
            selection: Binding(
                get: { selected ?? "" },
                set: { code in onSelect(code.isEmpty ? nil : code) }
            )
        ) {
            Text("No preference").tag("")
            ForEach(model.state.languageChoices, id: \.code) { choice in
                Text(choice.localizedDisplayName).tag(choice.code)
            }
        }
        .pickerStyle(.navigationLink)
    }

    private func segmentPicker(
        _ title: LocalizedStringKey,
        type: MediaSegmentType,
        current: SegmentSkipPolicy
    ) -> some View {
        Picker(
            title,
            selection: Binding(
                get: { current.name },
                set: { name in model.setSegmentPolicy(type: type, policyName: name) }
            )
        ) {
            Text("Skip automatically").tag("AutoSkip")
            Text("Ask").tag("Ask")
            Text("Do nothing").tag("Ignore")
        }
        .pickerStyle(.navigationLink)
    }
}

private struct OpenSourceNoticesView: View {
    let sourceRevision: String

    private var sourceURL: URL? {
        URL(string: "https://github.com/ch4ndu/JellyScope/tree/\(sourceRevision)")
    }

    var body: some View {
        List {
            Section("JellyScope") {
                Text("JellyScope-owned source is licensed under MPL-2.0.")
                LabeledContent("Revision", value: sourceRevision)
                if let sourceURL {
                    Link("View Source Revision", destination: sourceURL)
                }
                Text("Development builds may include uncommitted changes not represented by that revision.")
            }
            Section("Third-Party Software") {
                Text("JellyScope uses separately licensed third-party software. Release packages include the applicable notices, license texts, native manifests, and source routes. Third-party licenses remain unchanged.")
            }
        }
        .navigationTitle("Source & Notices")
    }
}
