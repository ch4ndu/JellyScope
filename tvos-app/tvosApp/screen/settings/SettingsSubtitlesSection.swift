// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
final class SubtitleSettingsModel: ObservableObject {
    @Published private(set) var state: TvSubtitleSettingsState
    private let presenter: TvSubtitleSettingsPresenter
    private var handle: WatchHandle?

    init() {
        let presenter = TvosEntry.shared.subtitleSettingsPresenter()
        self.presenter = presenter
        self.state = presenter.state.value as! TvSubtitleSettingsState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func saveApiKey(_ value: String) {
        presenter.saveApiKey(value: value)
    }

    func clearApiKey() {
        presenter.clearApiKey()
    }

    func retryApiKeyLoad() {
        presenter.retryApiKeyLoad()
    }

    func setResultPreference(named name: String) {
        let preference: OpenSubtitleResultPreference =
            switch name {
            case "PreferHearingImpaired": .preferhearingimpaired
            case "PreferForced": .preferforced
            default: .nopreference
            }
        presenter.setResultPreference(preference: preference)
    }

    func retryResultPreferenceLoad() {
        presenter.retryResultPreferenceLoad()
    }

    func clearDownloadedSubtitles() {
        presenter.clearDownloadedSubtitles()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

struct SettingsSubtitlesSection: View {
    @StateObject private var model: SubtitleSettingsModel
    @State private var confirmsClear = false

    init() {
        _model = StateObject(wrappedValue: SubtitleSettingsModel())
    }

    var body: some View {
        Section(String(localized: "Subtitles")) {
            NavigationLink {
                SubtitleKeyEditor(model: model)
            } label: {
                LabeledContent(
                    String(localized: "OpenSubtitles key"),
                    value: keyStatus
                )
            }
            .disabled(!model.state.isApiKeyLoaded || model.state.isSavingApiKey)

            if model.state.isLoadingApiKey {
                ProgressView(String(localized: "Loading OpenSubtitles key…"))
            } else if model.state.apiKeyLoadError {
                Label(
                    String(localized: "The OpenSubtitles key could not be loaded."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
                Button(String(localized: "Retry Key"), action: model.retryApiKeyLoad)
            }

            resultPreferencePicker

            if model.state.isLoadingResultPreference {
                ProgressView(String(localized: "Loading result preference…"))
            } else if model.state.resultPreferenceLoadError {
                Label(
                    String(localized: "The result preference could not be loaded."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
                Button(String(localized: "Retry Preference"), action: model.retryResultPreferenceLoad)
            } else if model.state.resultPreferenceSaveError {
                Label(
                    String(localized: "The result preference could not be saved."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
            }

            Button(role: .destructive) {
                confirmsClear = true
            } label: {
                if model.state.isClearingDownloadedSubtitles {
                    ProgressView(String(localized: "Clearing downloaded subtitles…"))
                } else {
                    Label(String(localized: "Clear Downloaded Subtitles"), systemImage: "trash")
                }
            }
            .disabled(model.state.isClearingDownloadedSubtitles)

            if model.state.clearDownloadedSubtitlesError {
                Label(
                    String(localized: "Downloaded subtitles could not be cleared."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
            } else if model.state.clearDownloadedSubtitlesRevision > 0 {
                Label(String(localized: "Downloaded subtitles cleared."), systemImage: "checkmark.circle")
                    .foregroundStyle(.green)
            }
        }
        .confirmationDialog(
            String(localized: "Delete all downloaded subtitles?"),
            isPresented: $confirmsClear,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Clear Downloaded Subtitles"), role: .destructive) {
                model.clearDownloadedSubtitles()
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "Downloaded subtitle files, metadata, and selections will be removed."))
        }
    }

    private var keyStatus: String {
        if model.state.isLoadingApiKey {
            return String(localized: "Loading…")
        }
        return model.state.apiKeyConfigured
            ? String(localized: "Configured")
            : String(localized: "Not configured")
    }

    private var resultPreferencePicker: some View {
        Picker(String(localized: "Result Preference"), selection: resultPreferenceBinding) {
            Text(String(localized: "No preference")).tag("NoPreference")
            Text(String(localized: "Prefer hearing impaired")).tag("PreferHearingImpaired")
            Text(String(localized: "Prefer forced")).tag("PreferForced")
        }
        .pickerStyle(.navigationLink)
        .disabled(!model.state.isResultPreferenceLoaded || model.state.isSavingResultPreference)
    }

    private var resultPreferenceBinding: Binding<String> {
        Binding(
            get: { model.state.resultPreference.name },
            set: model.setResultPreference
        )
    }
}
