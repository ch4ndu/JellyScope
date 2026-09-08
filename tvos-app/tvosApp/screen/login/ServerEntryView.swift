// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv
import SwiftUI

struct ServerEntryView: View {
    let state: TvLoginState
    @Binding var serverUrl: String
    let onSubmit: (String) -> Void
    let onSelectDiscovered: (TvDiscoveredServer) -> Void
    let onRetryDiscovery: () -> Void
    let onCancel: (() -> Void)?

    var body: some View {
        VStack(spacing: 28) {
            LoginBrandView()

            Form {
                Section(String(localized: "Server")) {
                    TextField(String(localized: "Server URL"), text: $serverUrl)
                        .textContentType(.URL)

                    if let error = state.error {
                        Label(error.loginMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }

                    Button(
                        state.phase == .validatingserver
                            ? String(localized: "Connecting…")
                            : String(localized: "Connect")
                    ) {
                        onSubmit(serverUrl)
                    }
                    .disabled(
                        state.phase == .validatingserver ||
                            serverUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )
                }

                if state.discoveryAvailable {
                    DiscoveredServersView(
                        state: state,
                        onSelect: onSelectDiscovered,
                        onRetry: onRetryDiscovery
                    )
                }

                if let onCancel {
                    Section {
                        Button(String(localized: "Cancel"), role: .cancel, action: onCancel)
                    }
                }
            }
            .frame(maxWidth: 1000)
        }
        .padding(.vertical, 40)
    }
}
