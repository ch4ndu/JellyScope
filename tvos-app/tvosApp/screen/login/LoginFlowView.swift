// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private enum LoginRoute: Hashable {
    case signIn
}

struct LoginFlowView: View {
    private let onCancel: (() -> Void)?
    private let onComplete: (() -> Void)?

    @StateObject private var model: LoginModel
    @State private var path: [LoginRoute] = []
    @State private var serverUrl: String
    @State private var username: String
    @State private var password: String
    @State private var startedDiscovery = false
    @State private var deliveredCompletion = false

    init(
        onCancel: (() -> Void)? = nil,
        onComplete: (() -> Void)? = nil
    ) {
        self.onCancel = onCancel
        self.onComplete = onComplete
        _model = StateObject(wrappedValue: LoginModel())
        let devServerUrl = TvosEntry.shared.devServerUrl()
        _serverUrl = State(initialValue: devServerUrl.isEmpty ? "https://" : devServerUrl)
        _username = State(initialValue: TvosEntry.shared.devUsername())
        _password = State(initialValue: TvosEntry.shared.devPassword())
    }

    var body: some View {
        NavigationStack(path: $path) {
            ServerEntryView(
                state: model.state,
                serverUrl: $serverUrl,
                onSubmit: model.submitServer,
                onSelectDiscovered: selectDiscoveredServer,
                onRetryDiscovery: model.retryDiscovery,
                onCancel: onCancel.map { callback in
                    {
                        password = ""
                        model.resetToServerEntry()
                        callback()
                    }
                }
            )
            .navigationDestination(for: LoginRoute.self) { route in
                switch route {
                case .signIn:
                    signInView
                }
            }
        }
        .task {
            guard !startedDiscovery else {
                return
            }
            startedDiscovery = true
            model.startDiscovery()
        }
        .onChange(of: model.state.phase) { _, phase in
            synchronizePath(for: phase)
            if phase == .done, !deliveredCompletion {
                deliveredCompletion = true
                password = ""
                onComplete?()
            }
        }
        .onChange(of: path) { _, updatedPath in
            if updatedPath.isEmpty,
               model.state.phase != .enterserver,
               model.state.phase != .validatingserver,
               model.state.phase != .done {
                password = ""
                model.resetToServerEntry()
            }
        }
    }

    private var signInView: some View {
        VStack(spacing: 36) {
            LoginBrandView()
            HStack(alignment: .top, spacing: 80) {
                QuickConnectView(
                    code: model.state.quickConnectCode,
                    error: model.state.quickConnectError,
                    onRetry: model.retryQuickConnect
                )

                Divider()

                CredentialsView(
                    serverName: model.state.serverName ?? String(localized: "Sign In"),
                    state: model.state,
                    username: $username,
                    password: $password,
                    onSubmit: model.submitCredentials,
                    onChangeServer: changeServer
                )
            }
        }
        .padding(60)
        .navigationTitle(model.state.serverName ?? String(localized: "Sign In"))
    }

    private func selectDiscoveredServer(_ server: TvDiscoveredServer) {
        serverUrl = server.address
        model.submitServer(server.address)
    }

    private func changeServer() {
        password = ""
        model.resetToServerEntry()
    }

    private func synchronizePath(for phase: TvLoginPhase) {
        switch phase {
        case .signin, .loggingin:
            if path.isEmpty {
                path.append(.signIn)
            }
        case .enterserver, .validatingserver:
            if !path.isEmpty {
                path.removeAll()
            }
        case .done:
            break
        default:
            break
        }
    }
}
