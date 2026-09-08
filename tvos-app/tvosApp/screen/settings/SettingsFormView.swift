// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsFormView: View {
    private let session: Session
    @StateObject private var model: SettingsModel
    @StateObject private var accountModel: AccountModel

    init(session: Session) {
        self.session = session
        _model = StateObject(wrappedValue: SettingsModel(session: session))
        _accountModel = StateObject(wrappedValue: AccountModel())
    }

    var body: some View {
        Form {
            if model.state.isLoading {
                Section {
                    ProgressView(String(localized: "Loading settings…"))
                }
            }

            if model.state.saveError {
                Section {
                    Label(
                        String(localized: "Your last change could not be saved."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)
                }
            }

            if model.state.loadError {
                Section {
                    Label(
                        String(localized: "Playback preferences could not be loaded."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)

                    Button(String(localized: "Retry"), action: model.retryLoad)
                        .disabled(model.state.isLoading)
                }
            }

            SettingsAppearanceSection()
            SettingsPlaybackSection(model: model)
                .disabled(model.state.isLoading || model.state.loadError)
            SettingsDeviceSection()
            SettingsLanguageSection(model: model)
                .disabled(model.state.isLoading || model.state.loadError)
            SettingsSegmentSection(model: model)
                .disabled(model.state.isLoading || model.state.loadError)
            SettingsBrowsingSection(model: model)
            SettingsSubtitlesSection()
            if session.enableContentDownloading {
                Section("Downloads") {
                    NavigationLink("Download Storage") {
                        DownloadSettingsView(session: session)
                    }
                }
            }
            SettingsDiagnosticsSection(model: model)
            SettingsAccountSection(settingsState: model.state, accountModel: accountModel)
            SettingsAboutSection()
        }
        .navigationTitle(String(localized: "Settings"))
    }
}
