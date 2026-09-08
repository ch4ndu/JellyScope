// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct AccountPickerView: View {
    @ObservedObject private var model: AccountModel
    @State private var removalAccountId: String?
    @State private var showingAddAccount = false

    init(model: AccountModel) {
        self.model = model
    }

    var body: some View {
        Form {
            Section(String(localized: "Accounts")) {
                ForEach(model.state.accounts, id: \.id) { account in
                    accountRow(account)
                }
            }

            if model.state.isLoadingRemovalPreview, removalAccountId != nil {
                Section {
                    ProgressView(String(localized: "Checking retained downloads…"))
                }
            }

            Section {
                Button {
                    showingAddAccount = true
                } label: {
                    Label(String(localized: "Add Account"), systemImage: "person.badge.plus")
                }
                .disabled(model.state.operationInFlight)
            }

            if let error = model.state.error {
                Section {
                    Label(error.settingsMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle(String(localized: "Accounts"))
        .confirmationDialog(
            removalConfirmationTitle,
            isPresented: removalConfirmationBinding,
            titleVisibility: .visible,
            presenting: model.state.removalConfirmation
        ) { confirmation in
            if confirmation.downloadCount == 0 {
                Button(String(localized: "Remove Account"), role: .destructive) {
                    model.confirmPendingRemoval(confirmation)
                }
            } else {
                Button(String(localized: "Remove Account and Downloads"), role: .destructive) {
                    model.confirmPendingRemoval(confirmation)
                }
            }
            Button(String(localized: "Cancel"), role: .cancel) {
                model.dismissPendingRemoval(
                    accountId: confirmation.accountId,
                    confirmation: confirmation
                )
                removalAccountId = nil
            }
        } message: { confirmation in
            Text(removalMessage(confirmation))
        }
        .sheet(isPresented: $showingAddAccount) {
            LoginFlowView(
                onCancel: { showingAddAccount = false },
                onComplete: { showingAddAccount = false }
            )
        }
        .onChange(of: model.state.operationInFlight) { _, inFlight in
            if !inFlight {
                removalAccountId = nil
            }
        }
        .onDisappear {
            if let removalAccountId, model.state.operationInFlight {
                model.dismissPendingRemoval(
                    accountId: removalAccountId,
                    confirmation: model.state.removalConfirmation
                )
            }
        }
    }

    private func accountRow(_ account: TvAccountSummary) -> some View {
        HStack(spacing: 24) {
            Button {
                model.switchAccount(account.id)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(account.userName)
                    Text(account.serverName)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(account.serverUrl)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .disabled(account.isActive || model.state.operationInFlight)

            if account.isActive {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel(String(localized: "Active account"))
            }

            Button(role: .destructive) {
                removalAccountId = account.id
                model.signOutAccount(account.id)
            } label: {
                Label(String(localized: "Remove Account"), systemImage: "trash")
            }
            .disabled(model.state.operationInFlight)
        }
    }

    private var removalConfirmationBinding: Binding<Bool> {
        Binding(
            get: {
                guard let removalAccountId else { return false }
                return model.state.removalConfirmation?.accountId == removalAccountId
            },
            set: { showing in
                guard !showing,
                      let removalAccountId,
                      let confirmation = model.state.removalConfirmation else { return }
                model.dismissPendingRemovalAfterPresentation(
                    accountId: removalAccountId,
                    confirmation: confirmation
                )
            }
        )
    }

    private var removalConfirmationTitle: String {
        model.state.removalConfirmation?.downloadCount == 0
            ? String(localized: "Remove Account?")
            : String(localized: "Remove this account and its downloads?")
    }

    private func removalMessage(_ confirmation: TvAccountRemovalConfirmation) -> String {
        if confirmation.downloadCount == 0 {
            return String(
                format: String(localized: "Remove %@ on %@ from this Apple TV?"),
                confirmation.userName,
                confirmation.serverName
            )
        }
        let details = String(
            format: String(localized: "%@ on %@ has %lld downloads using %@. Removing the account permanently deletes those local copies."),
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
