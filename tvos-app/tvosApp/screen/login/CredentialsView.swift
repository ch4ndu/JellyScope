// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct CredentialsView: View {
    let serverName: String
    let state: TvLoginState
    @Binding var username: String
    @Binding var password: String
    let onSubmit: (String, String) -> Void
    let onChangeServer: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text(serverName)
                .font(.title2.weight(.semibold))

            TextField(String(localized: "Username"), text: $username)
                .textContentType(.username)
            SecureField(String(localized: "Password"), text: $password)
                .textContentType(.password)

            if let error = state.error {
                Label(error.loginMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }

            Button(
                state.phase == .loggingin
                    ? String(localized: "Signing In…")
                    : String(localized: "Sign In")
            ) {
                onSubmit(username, password)
            }
            .disabled(state.phase == .loggingin || username.isEmpty)

            Button(String(localized: "Change Server"), action: onChangeServer)
        }
        .frame(maxWidth: 700)
    }
}
