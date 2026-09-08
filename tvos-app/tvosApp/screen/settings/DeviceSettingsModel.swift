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
        presenter.setAudioChoice(choice: choice)
    }

    func setHdrChoice(_ choice: TvDeviceHdrChoice) {
        presenter.setHdrChoice(choice: choice)
    }

    func retryPolicy() {
        presenter.retryPolicy()
    }

    func retrySave() {
        presenter.retrySave()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
