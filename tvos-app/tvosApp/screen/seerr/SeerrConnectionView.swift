// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SeerrConnectionView: View {
    let state: TvSeerrConnectionState
    let onUrlChanged: (String) -> Void
    let onValidate: (String) -> Void
    let onLogin: (SeerrLoginMethod, String, String, String) -> Bool
    let onQuickConnect: (String) -> Void
    let onCancel: () -> Void
    let onRestore: () -> Void
    let onDisconnect: () -> Void
    @Environment(\.tvAppearance) private var appearance
    @State private var identifier = ""
    @State private var password = ""
    @State private var localLogin = false
    @FocusState private var focusedField: Field?
    private enum Field: Hashable { case url, identifier, password }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                Text(SeerrCopy.text("seerr.manage")).font(.title.bold())
                SeerrMessage(key: "seerr.setup.explanation")
                Text(SeerrCopy.text(state.phaseKey)).font(.headline)
                if !state.endpoint.isEmpty {
                    Text(String(format: SeerrCopy.text("seerr.connection.endpoint"), state.endpoint))
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !state.userName.isEmpty {
                    Text(String(format: SeerrCopy.text("seerr.connection.user"), state.userName))
                }
                if state.plainHttp { SeerrMessage(key: "seerr.connection.http") }
                if let error = state.errorKey { SeerrMessage(key: error, isError: true) }
                if state.busy { ProgressView(SeerrCopy.text("seerr.connection.checking")) }

                TextField(SeerrCopy.text("seerr.connection.url"), text: Binding(
                    get: { url },
                    set: { value in
                        identifier = ""
                        password = ""
                        onUrlChanged(value)
                    }
                ))
                    .textContentType(.URL)
                    .accessibilityLabel(SeerrCopy.text("seerr.connection.url"))
                    .focused($focusedField, equals: .url)
                    .disabled(state.busy)
                    .onSubmit { validate() }
                Button(SeerrCopy.text("seerr.connection.validate"), action: validate)
                    .disabled(state.busy || url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                if canAuthenticate {
                    authentication
                }

                if let code = state.quickConnectCode {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(SeerrCopy.text("seerr.quick_connect.instructions")).font(.body)
                        Text(code).font(.largeTitle.monospaced().bold()).accessibilityLabel(
                            String(format: SeerrCopy.text("seerr.quick_connect.code"), code)
                        )
                        HStack {
                            Text(SeerrCopy.text("seerr.quick_connect.expires"))
                            Text(Date(timeIntervalSince1970: Double(state.quickConnectExpiry) / 1000), style: .time)
                        }
                        SeerrMessage(key: "seerr.quick_connect.waiting")
                    }
                }
                if state.authenticating {
                    Button(SeerrCopy.text("seerr.connection.cancel_auth")) {
                        clearCredentials()
                        onCancel()
                    }
                }
                HStack(spacing: 30) {
                    Button(SeerrCopy.text("seerr.connection.restore")) {
                        clearCredentials()
                        onRestore()
                    }
                    .disabled(state.busy)
                    Button(SeerrCopy.text("seerr.connection.disconnect"), role: .destructive) {
                        clearCredentials()
                        onDisconnect()
                    }
                    .disabled(state.busy && !state.authenticating)
                }
            }
            .frame(maxWidth: 1200, alignment: .leading)
            .padding(TvDimensions.ribbonFocusReserve)
            .padding(.bottom, TvDimensions.screenBottomInset)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear {
            chooseAvailableMethod()
        }
        .onChange(of: state.canLogin) { _, allowed in
            clearCredentials(preservingUrlFocus: true)
            if allowed { chooseAvailableMethod() }
        }
        .onChange(of: state.jellyfinLogin) { _, _ in chooseAvailableMethod() }
        .onChange(of: state.localLogin) { _, _ in chooseAvailableMethod() }
        .onChange(of: localLogin) { _, _ in clearCredentials(preservingUrlFocus: true) }
        .onDisappear {
            clearCredentials()
            if state.authenticating { onCancel() }
        }
    }

    private var url: String {
        state.url ?? state.endpoint
    }

    private var canAuthenticate: Bool {
        state.canLogin && url == state.endpoint
    }

    private var authentication: some View {
        VStack(alignment: .leading, spacing: 26) {
            if state.jellyfinLogin && state.localLogin {
                HStack(spacing: 30) {
                    Button(SeerrCopy.text("seerr.login.jellyfin")) { localLogin = false }
                        .accessibilityAddTraits(!localLogin ? .isSelected : [])
                    Button(SeerrCopy.text("seerr.login.local")) { localLogin = true }
                        .accessibilityAddTraits(localLogin ? .isSelected : [])
                }
                .disabled(state.busy)
            }
            if state.jellyfinLogin || state.localLogin {
                Text(SeerrCopy.text(localLogin ? "seerr.login.local" : "seerr.login.jellyfin")).font(.headline)
                if localLogin { SeerrMessage(key: "seerr.login.local_link") }
                TextField(SeerrCopy.text(localLogin ? "seerr.login.email" : "seerr.login.username"), text: $identifier)
                    .textContentType(localLogin ? .emailAddress : .username)
                    .focused($focusedField, equals: .identifier)
                    .accessibilityLabel(SeerrCopy.text(localLogin ? "seerr.login.email" : "seerr.login.username"))
                    .disabled(state.busy)
                SecureField(SeerrCopy.text("seerr.login.password"), text: $password)
                    .textContentType(.password)
                    .focused($focusedField, equals: .password)
                    .accessibilityLabel(SeerrCopy.text("seerr.login.password"))
                    .disabled(state.busy)
                    .onSubmit { submit() }
                Button(SeerrCopy.text("seerr.login.submit"), action: submit)
                    .disabled(
                        !canAuthenticate || state.busy ||
                        identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty
                    )
            }
            if state.quickConnect {
                Button(SeerrCopy.text("seerr.quick_connect.begin")) {
                    guard canAuthenticate, !state.busy else { return }
                    clearCredentials()
                    onQuickConnect(url)
                }
                .disabled(!canAuthenticate || state.busy)
            }
            if !state.jellyfinLogin && !state.localLogin && !state.quickConnect {
                SeerrMessage(key: "seerr.login.unavailable")
            }
        }
        .focusSection()
    }

    private func chooseAvailableMethod() {
        localLogin = !state.jellyfinLogin && state.localLogin
    }

    private func validate() {
        guard !state.busy else { return }
        clearCredentials()
        onValidate(url)
    }

    private func submit() {
        guard canAuthenticate, !state.busy,
              !identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty else { return }
        focusedField = nil
        if onLogin(localLogin ? .local : .jellyfin, url, identifier, password) {
            password = ""
        }
    }

    private func clearCredentials(preservingUrlFocus: Bool = false) {
        password = ""
        identifier = ""
        if !preservingUrlFocus || focusedField != .url { focusedField = nil }
    }
}
