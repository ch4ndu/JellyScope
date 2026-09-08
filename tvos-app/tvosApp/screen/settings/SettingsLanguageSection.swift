// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsLanguageSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section(String(localized: "Languages")) {
            languagePicker(
                title: String(localized: "Preferred Audio"),
                selected: model.state.preferences.preferredAudioLanguage,
                onSelect: model.setPreferredAudioLanguage
            )
            languagePicker(
                title: String(localized: "Preferred Subtitles"),
                selected: model.state.preferences.preferredSubtitleLanguage,
                onSelect: model.setPreferredSubtitleLanguage
            )
        }
    }

    private func languagePicker(
        title: String,
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
            Text(String(localized: "No preference")).tag("")
            ForEach(model.state.languageChoices, id: \.code) { choice in
                Text(choice.localizedDisplayName).tag(choice.code)
            }
        }
        .pickerStyle(.navigationLink)
        .disabled(model.state.isLoading)
    }
}
