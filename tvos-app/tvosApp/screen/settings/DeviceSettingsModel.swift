// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class DeviceSettingsModel: ObservableObject {
    @Published private(set) var state: TvDeviceSettingsState

    private let presenter: TvDeviceSettingsPresenter
    private var handle: WatchHandle?

    init() {
        let presenter = TvosEntry.shared.deviceSettingsPresenter()
        self.presenter = presenter
        self.state = presenter.state.value as! TvDeviceSettingsState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func setAudioChoice(_ choice: TvDeviceAudioChoice) {
        presenter.accept(intent: TvDeviceSettingsIntentSetAudioChoice(choice: choice))
    }

    func setHdrChoice(_ choice: TvDeviceHdrChoice) {
        presenter.accept(intent: TvDeviceSettingsIntentSetHdrChoice(choice: choice))
    }

    func retryPolicy() {
        presenter.accept(intent: TvDeviceSettingsIntentRetryPolicy.shared)
    }

    func retrySave() {
        presenter.accept(intent: TvDeviceSettingsIntentRetrySave.shared)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
