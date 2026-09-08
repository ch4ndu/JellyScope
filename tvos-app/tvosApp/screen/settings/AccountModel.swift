// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class AccountModel: ObservableObject {
    @Published private(set) var state: TvAccountsState
    private let presenter: TvAccountsPresenter
    private var handle: WatchHandle?
    private var closed = false
    private var consumedRemovalPreview: RemovalPreviewIdentity?
    private var consumedPreviewLeftPresentation = false

    init() {
        let presenter = TvosEntry.shared.accountsPresenter()
        self.presenter = presenter
        self.state = presenter.state.value as! TvAccountsState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
            if !state.operationInFlight {
                self?.consumedRemovalPreview = nil
                self?.consumedPreviewLeftPresentation = false
            } else if self?.consumedRemovalPreview != nil, state.removalConfirmation == nil {
                self?.consumedPreviewLeftPresentation = true
            } else if self?.consumedPreviewLeftPresentation == true, state.removalConfirmation != nil {
                self?.consumedRemovalPreview = nil
                self?.consumedPreviewLeftPresentation = false
            }
        }
    }

    func switchAccount(_ id: String) {
        presenter.switchAccount(accountId: id)
    }

    func signOutAccount(_ id: String) {
        consumedRemovalPreview = nil
        consumedPreviewLeftPresentation = false
        presenter.signOutAccount(accountId: id)
    }

    func confirmPendingRemoval(_ confirmation: TvAccountRemovalConfirmation) {
        let identity = RemovalPreviewIdentity(confirmation)
        guard state.operationAccountId == identity.accountId,
              state.removalConfirmation.map(RemovalPreviewIdentity.init) == identity else { return }
        consumedRemovalPreview = identity
        consumedPreviewLeftPresentation = false
        presenter.confirmPendingRemoval()
    }

    func dismissPendingRemoval(
        accountId: String,
        confirmation: TvAccountRemovalConfirmation?
    ) {
        dismissPendingRemoval(
            accountId: accountId,
            expectedPreview: confirmation.map(RemovalPreviewIdentity.init)
        )
    }

    func dismissPendingRemovalAfterPresentation(
        accountId: String,
        confirmation: TvAccountRemovalConfirmation
    ) {
        let identity = RemovalPreviewIdentity(confirmation)
        Task { @MainActor [weak self] in
            await Task.yield()
            self?.dismissPendingRemoval(accountId: accountId, expectedPreview: identity)
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        handle = nil
        presenter.close()
    }

    deinit {
        if !closed {
            handle?.close()
            presenter.close()
        }
    }

    private func dismissPendingRemoval(
        accountId: String,
        expectedPreview: RemovalPreviewIdentity?
    ) {
        guard state.operationInFlight, state.operationAccountId == accountId else { return }
        if let confirmation = state.removalConfirmation {
            let current = RemovalPreviewIdentity(confirmation)
            guard current == expectedPreview, consumedRemovalPreview != current else { return }
        } else {
            guard expectedPreview == nil, consumedRemovalPreview?.accountId != accountId else { return }
        }
        presenter.dismissPendingRemoval()
    }
}

private struct RemovalPreviewIdentity: Equatable, Sendable {
    let accountId: String
    let serverName: String
    let userName: String
    let downloadCount: Int64
    let displayedBytes: Int64
    let refreshedAfterStale: Bool

    init(_ confirmation: TvAccountRemovalConfirmation) {
        accountId = confirmation.accountId
        serverName = confirmation.serverName
        userName = confirmation.userName
        downloadCount = confirmation.downloadCount
        displayedBytes = confirmation.displayedBytes
        refreshedAfterStale = confirmation.refreshedAfterStale
    }
}
