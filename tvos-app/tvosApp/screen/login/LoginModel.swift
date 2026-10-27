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
        presenter.accept(intent: TvLoginIntentStartDiscovery.shared)
    }

    func retryDiscovery() {
        presenter.accept(intent: TvLoginIntentRetryDiscovery.shared)
    }

    func submitServer(_ url: String) {
        presenter.accept(intent: TvLoginIntentSubmitServer(input: url))
    }

    func submitCredentials(username: String, password: String) {
        presenter.accept(intent: TvLoginIntentSubmitCredentials(username: username, password: password))
    }

    func retryQuickConnect() {
        presenter.accept(intent: TvLoginIntentRetryQuickConnect.shared)
    }

    func resetToServerEntry() {
        presenter.accept(intent: TvLoginIntentResetToServerEntry.shared)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
