// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class SeerrModel: ObservableObject {
    @Published private(set) var state: TvSeerrState

    private let presenter: TvSeerrPresenter
    private var stateHandle: WatchHandle?
    private var effectHandle: WatchHandle?
    private var pendingEffect: Int64?
    private var navigate: ((String) -> Void)?

    init(session: Session, boundaryEpoch: Int64) {
        let presenter = TvosEntry.shared.seerrPresenter(session: session, boundaryEpoch: boundaryEpoch)
        self.presenter = presenter
        self.state = presenter.currentState()
        stateHandle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        effectHandle = presenter.watchEffects { [weak self] effect in
            self?.pendingEffect = effect.id
            self?.consumeNavigation()
        }
    }

    func send(_ intent: TvSeerrIntent) {
        presenter.accept(intent: intent)
        // Keep controlled input and auth admission current before the next SwiftUI event.
        state = presenter.currentState()
    }

    func appeared(navigate: @escaping (String) -> Void) {
        self.navigate = navigate
        consumeNavigation()
    }

    func disappeared() {
        navigate = nil
        pendingEffect = nil
        send(TvSeerrIntentHidden.shared)
    }

    func login(method: SeerrLoginMethod, url: String, identifier: String, password: String) -> Bool {
        let current = presenter.currentState()
        guard current.ownerValid, current.connection.canLogin, !current.connection.busy,
              url == current.connection.url, url == current.connection.endpoint else { return false }
        send(TvSeerrIntentLogin(method: method, url: url, identifier: identifier, password: password))
        return true
    }

    private func consumeNavigation() {
        guard let id = pendingEffect, navigate != nil else { return }
        pendingEffect = nil
        presenter.consumeLibraryEffect(id: id) { [weak self] itemId in
            // This callback runs inside the final domain publication gate on Main.
            // Append synchronously; another task/queue would reopen the ownership race.
            self?.navigate?(itemId)
        }
    }

    deinit {
        stateHandle?.close()
        effectHandle?.close()
        presenter.close()
    }
}

enum SeerrCopy {
    static func text(_ key: String) -> String {
        String(localized: String.LocalizationValue(key))
    }

    static func season(_ season: TvSeerrSeason) -> String {
        let number = season.specials
            ? text("seerr.specials")
            : String(format: text("seerr.season.number"), season.number)
        guard let title = season.title, !title.isEmpty else { return number }
        return String(format: text("seerr.season.title"), number, title)
    }
}
