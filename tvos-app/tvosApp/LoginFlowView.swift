// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

/// Server entry + login. Quick Connect is the primary 10-foot path: the code
/// shows as soon as the server validates; the password form stays alongside.
struct LoginFlowView: View {
    @StateObject private var model = LoginModel()
    @State private var serverUrl = TvosEntry.shared.devServerUrl().isEmpty
        ? "https://" : TvosEntry.shared.devServerUrl()
    @State private var username = TvosEntry.shared.devUsername()
    @State private var password = TvosEntry.shared.devPassword()

    var body: some View {
        Group {
            switch model.state.phase {
            case .enterserver, .validatingserver:
                serverEntry
            default:
                signIn
            }
        }
        .animation(.default, value: model.state)
    }

    private var serverEntry: some View {
        VStack(spacing: 24) {
            Text("JellyScope")
                .font(.largeTitle)
            TextField("Server URL", text: $serverUrl)
                .textContentType(.URL)
                .frame(maxWidth: 800)
            if let error = model.state.error {
                Text(error.loginMessage)
                    .foregroundStyle(.red)
            }
            Button(
                model.state.phase == .validatingserver
                    ? String(localized: "Connecting…")
                    : String(localized: "Connect")
            ) {
                model.submitServer(serverUrl)
            }
            .disabled(model.state.phase == .validatingserver)
        }
        .padding(60)
    }

    private var signIn: some View {
        HStack(alignment: .top, spacing: 80) {
            VStack(spacing: 20) {
                Text("Quick Connect")
                    .font(.title2)
                if let code = model.state.quickConnectCode {
                    Text(code)
                        .font(.system(size: 64, weight: .bold, design: .monospaced))
                    Text(String(localized: "Enter this code in the Jellyfin app or web client under Settings → Quick Connect."))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else if let quickConnectError = model.state.quickConnectError {
                    Text(quickConnectError.loginMessage)
                        .foregroundStyle(.red)
                    Button("Retry Quick Connect") {
                        model.retryQuickConnect()
                    }
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: 700)

            VStack(spacing: 20) {
                Text(model.state.serverName ?? String(localized: "Sign In"))
                    .font(.title2)
                TextField("Username", text: $username)
                    .textContentType(.username)
                SecureField("Password", text: $password)
                    .textContentType(.password)
                if let error = model.state.error {
                    Text(error.loginMessage)
                        .foregroundStyle(.red)
                }
                Button(
                    model.state.phase == .loggingin
                        ? String(localized: "Signing In…")
                        : String(localized: "Sign In")
                ) {
                    model.submitCredentials(username: username, password: password)
                }
                .disabled(model.state.phase == .loggingin)
                Button("Change Server") {
                    model.resetToServerEntry()
                }
            }
            .frame(maxWidth: 700)
        }
        .padding(60)
    }
}

extension TvErrorKind {
    var loginMessage: String {
        switch self {
        case .invalidurl: return String(localized: "That server address is not valid.")
        case .notreachable: return String(localized: "The Jellyfin server is not reachable.")
        case .invalidcredentials: return String(localized: "Wrong username or password.")
        case .quickconnectexpired: return String(localized: "The Quick Connect code expired.")
        case .quickconnectunavailable: return String(localized: "Quick Connect is disabled on this server.")
        case .server: return String(localized: "The server returned an error.")
        case .network: return String(localized: "A network error occurred.")
        default: return String(localized: "Something went wrong.")
        }
    }
}
