// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SubtitleSearchView: View {
    @ObservedObject var model: SubtitlesModel

    var body: some View {
        Section(String(localized: "Search OpenSubtitles")) {
            Picker(String(localized: "Language"), selection: languageBinding) {
                ForEach(model.state.languageChoices, id: \.code) { choice in
                    Text(choice.localizedDisplayName).tag(choice.code)
                }
            }
            .pickerStyle(.menu)
            .disabled(model.state.isSearching || model.state.installingFileId != nil)

            quotaFeedback

            if model.state.isSearching {
                ProgressView(String(localized: "Searching…"))
            } else if model.state.searchError {
                Label(
                    String(localized: "OpenSubtitles search failed."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
                Button(String(localized: "Retry Search"), action: model.retrySearch)
            } else if model.state.searchResults.isEmpty {
                Text(String(localized: "No subtitles found."))
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.state.searchResults, id: \.fileId) { result in
                    SubtitleResultRow(
                        result: result,
                        installing: model.state.installingFileId == result.fileId,
                        installDisabled: model.state.installingFileId != nil,
                        onInstall: { model.install(result) }
                    )
                }
            }

            if model.state.installationError {
                Label(
                    String(localized: "Subtitle installation failed."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
            }
        }
    }

    private var languageBinding: Binding<String> {
        Binding(
            get: { model.state.selectedLanguage },
            set: model.setLanguage
        )
    }

    @ViewBuilder
    private var quotaFeedback: some View {
        if let remaining = model.state.quotaRemaining {
            Text(
                String(
                    format: String(localized: "Quota remaining: %lld"),
                    Int64(remaining.int32Value)
                )
            )
            .foregroundStyle(.secondary)
        }
        if let reset = model.state.quotaResetTime {
            Text(
                String(
                    format: String(localized: "Quota resets: %@"),
                    reset
                )
            )
            .foregroundStyle(.secondary)
        }
    }
}
