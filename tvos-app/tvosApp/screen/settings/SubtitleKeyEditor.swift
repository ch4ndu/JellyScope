// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct SubtitleKeyEditor: View {
    @ObservedObject var model: SubtitleSettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var pendingRevision: Int64?

    var body: some View {
        Form {
            Section {
                SecureField(String(localized: "Enter a new consumer key"), text: $draft)

                LabeledContent(
                    String(localized: "Status"),
                    value: model.state.apiKeyConfigured
                        ? String(localized: "Configured")
                        : String(localized: "Not configured")
                )
            } footer: {
                Text(String(localized: "The saved key stays hidden. Enter a new value to replace it."))
            }

            Section {
                Button(String(localized: "Save"), action: save)
                    .disabled(trimmedDraft.isEmpty || model.state.isSavingApiKey)

                Button(String(localized: "Clear"), role: .destructive, action: clear)
                    .disabled(!model.state.apiKeyConfigured || model.state.isSavingApiKey)

                if model.state.isSavingApiKey {
                    ProgressView(String(localized: "Saving…"))
                } else if model.state.apiKeySaveError {
                    Label(
                        String(localized: "The OpenSubtitles key could not be saved."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle(String(localized: "OpenSubtitles Key"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "Cancel"), action: dismiss.callAsFunction)
            }
        }
        .onChange(of: model.state.apiKeyMutationRevision) { _, revision in
            guard let pendingRevision, revision >= pendingRevision else { return }
            draft = ""
            self.pendingRevision = nil
            dismiss()
        }
    }

    private var trimmedDraft: String {
        draft.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func save() {
        pendingRevision = model.state.apiKeyMutationRevision + 1
        model.saveApiKey(trimmedDraft)
    }

    private func clear() {
        pendingRevision = model.state.apiKeyMutationRevision + 1
        model.clearApiKey()
    }
}
