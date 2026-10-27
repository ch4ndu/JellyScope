// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SubtitleSearchView: View {
    let languageChoices: [TvLanguageChoice]
    let selectedLanguage: String
    let isSearching: Bool
    let searchResults: [TvOpenSubtitleResult]
    let searchError: Bool
    let installingFileId: String?
    let installationError: Bool
    let quotaRemaining: KotlinInt?
    let quotaResetTime: String?
    let onLanguage: (String) -> Void
    let onRetry: () -> Void
    let onInstall: (TvOpenSubtitleResult) -> Void

    var body: some View {
        Section(String(localized: "Search OpenSubtitles")) {
            Picker(String(localized: "Language"), selection: languageBinding) {
                ForEach(languageChoices, id: \.code) { choice in
                    Text(choice.localizedDisplayName).tag(choice.code)
                }
            }
            .pickerStyle(.menu)
            .disabled(isSearching || installingFileId != nil)

            quotaFeedback

            if isSearching {
                ProgressView(String(localized: "Searching…"))
            } else if searchError {
                Label(
                    String(localized: "OpenSubtitles search failed."),
                    systemImage: "exclamationmark.triangle"
                )
                .foregroundStyle(.orange)
                Button(String(localized: "Retry Search"), action: onRetry)
            } else if searchResults.isEmpty {
                Text(String(localized: "No subtitles found."))
                    .foregroundStyle(.secondary)
            } else {
                ForEach(searchResults, id: \.fileId) { result in
                    SubtitleResultRow(
                        result: result,
                        installing: installingFileId == result.fileId,
                        installDisabled: installingFileId != nil,
                        onInstall: { onInstall(result) }
                    )
                }
            }

            if installationError {
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
            get: { selectedLanguage },
            set: onLanguage
        )
    }

    @ViewBuilder
    private var quotaFeedback: some View {
        if let remaining = quotaRemaining {
            Text(
                String(
                    format: String(localized: "Quota remaining: %lld"),
                    Int64(remaining.int32Value)
                )
            )
            .foregroundStyle(.secondary)
        }
        if let reset = quotaResetTime {
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
