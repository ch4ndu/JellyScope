// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsAccountSection: View {
    let settingsState: TvSettingsState
    @ObservedObject var accountModel: AccountModel
    @State private var signOutAccountId: String?

    var body: some View {
        Section(String(localized: "Account")) {
            LabeledContent(String(localized: "Server"), value: settingsState.serverName)
            LabeledContent(String(localized: "Server URL")) {
                Text(settingsState.serverUrl)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(3)
            }
            LabeledContent(String(localized: "User"), value: settingsState.userName)

            NavigationLink(String(localized: "Manage Accounts")) {
                AccountPickerView(model: accountModel)
            }

            Button(String(localized: "Sign Out"), role: .destructive) {
                guard let accountId = accountModel.state.activeAccountId else { return }
                signOutAccountId = accountId
                accountModel.signOutAccount(accountId)
            }
            .disabled(settingsState.isLoading || accountModel.state.activeAccountId == nil || accountModel.state.operationInFlight)

            if accountModel.state.isLoadingRemovalPreview, signOutAccountId != nil {
                ProgressView(String(localized: "Checking retained downloads…"))
            }
        }
        .confirmationDialog(
            signOutConfirmationTitle,
            isPresented: signOutConfirmationBinding,
            titleVisibility: .visible,
            presenting: accountModel.state.removalConfirmation
        ) { confirmation in
            if confirmation.downloadCount == 0 {
                Button(String(localized: "Sign Out"), role: .destructive) {
                    accountModel.confirmPendingRemoval(confirmation)
                }
            } else {
                Button(String(localized: "Sign Out and Delete Downloads"), role: .destructive) {
                    accountModel.confirmPendingRemoval(confirmation)
                }
            }
            Button(String(localized: "Cancel"), role: .cancel) {
                accountModel.dismissPendingRemoval(
                    accountId: confirmation.accountId,
                    confirmation: confirmation
                )
                signOutAccountId = nil
            }
        } message: { confirmation in
            Text(signOutMessage(confirmation))
        }
        .onChange(of: accountModel.state.operationInFlight) { _, inFlight in
            if !inFlight {
                signOutAccountId = nil
            }
        }
        .onDisappear {
            if let signOutAccountId, accountModel.state.operationInFlight {
                accountModel.dismissPendingRemoval(
                    accountId: signOutAccountId,
                    confirmation: accountModel.state.removalConfirmation
                )
            }
        }

        if let error = accountModel.state.error {
            Section {
                Label(error.settingsMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
        }
    }

    private var signOutConfirmationBinding: Binding<Bool> {
        Binding(
            get: {
                guard let signOutAccountId else { return false }
                return accountModel.state.removalConfirmation?.accountId == signOutAccountId
            },
            set: { showing in
                guard !showing,
                      let signOutAccountId,
                      let confirmation = accountModel.state.removalConfirmation else { return }
                accountModel.dismissPendingRemovalAfterPresentation(
                    accountId: signOutAccountId,
                    confirmation: confirmation
                )
            }
        )
    }

    private var signOutConfirmationTitle: String {
        accountModel.state.removalConfirmation?.downloadCount == 0
            ? String(localized: "Sign Out?")
            : String(localized: "Sign out and delete retained downloads?")
    }

    private func signOutMessage(_ confirmation: TvAccountRemovalConfirmation) -> String {
        if confirmation.downloadCount == 0 {
            return String(
                format: String(localized: "Sign out %@ from %@?"),
                confirmation.userName,
                confirmation.serverName
            )
        }
        let details = String(
            format: String(localized: "Signing out %@ from %@ permanently deletes %lld downloads using %@ from this Apple TV."),
            confirmation.userName,
            confirmation.serverName,
            confirmation.downloadCount,
            DownloadLabels.bytes(confirmation.displayedBytes)
        )
        return confirmation.refreshedAfterStale
            ? String(format: String(localized: "The download list changed. Review the updated totals: %@"), details)
            : details
    }
}
