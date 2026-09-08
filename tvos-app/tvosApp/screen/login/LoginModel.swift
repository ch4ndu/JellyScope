// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class LoginModel: ObservableObject {
    @Published private(set) var state: TvLoginState
    private let presenter: TvLoginPresenter
    private var handle: WatchHandle?

    init() {
        let presenter = TvosEntry.shared.loginPresenter()
        self.presenter = presenter
        self.state = presenter.state.value as! TvLoginState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func startDiscovery() {
        presenter.startDiscovery()
    }

    func retryDiscovery() {
        presenter.retryDiscovery()
    }

    func submitServer(_ url: String) {
        presenter.submitServer(input: url)
    }

    func submitCredentials(username: String, password: String) {
        presenter.submitCredentials(username: username, password: password)
    }

    func retryQuickConnect() {
        presenter.retryQuickConnect()
    }

    func resetToServerEntry() {
        presenter.resetToServerEntry()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
