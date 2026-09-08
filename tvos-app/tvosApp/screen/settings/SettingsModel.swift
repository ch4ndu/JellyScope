// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class SettingsModel: ObservableObject {
    @Published private(set) var state: TvSettingsState
    private let presenter: TvSettingsPresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.settingsPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvSettingsState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func setDefaultQualityChoice(_ choice: TvQualityChoice) {
        presenter.setDefaultQualityChoice(
            modeName: choice.mode.name,
            maxBitrateBps: choice.maxBitrateBps
        )
    }

    func setPreferredAudioLanguage(_ code: String?) {
        presenter.setPreferredAudioLanguage(code: code)
    }

    func setPreferredSubtitleLanguage(_ code: String?) {
        presenter.setPreferredSubtitleLanguage(code: code)
    }

    func setPlaybackWarningsEnabled(_ enabled: Bool) {
        presenter.setPlaybackWarningsEnabled(enabled: enabled)
    }

    func setAutoPlayNext(_ enabled: Bool) {
        presenter.setAutoPlayNext(enabled: enabled)
    }

    func setStillWatchingPrompt(_ enabled: Bool) {
        presenter.setStillWatchingPrompt(enabled: enabled)
    }

    func setAutoPlayNextDelaySeconds(_ seconds: Int) {
        presenter.setAutoPlayNextDelaySeconds(seconds: Int32(seconds))
    }

    func setRememberLastLibraryView(_ enabled: Bool) {
        presenter.setRememberLastLibraryViewEnabled(enabled: enabled)
    }

    func setDiagnosticCollectionEnabled(_ enabled: Bool) {
        presenter.setDiagnosticCollectionEnabled(enabled: enabled)
    }

    func setPlaybackInfoAtStartEnabled(_ enabled: Bool) {
        presenter.setPlaybackInfoAtStartEnabled(enabled: enabled)
    }

    func retryPlaybackInfoAtStart() {
        presenter.retryPlaybackInfoAtStart()
    }

    func retryLoad() {
        presenter.retryLoad()
    }

    func sendClientLogs() {
        presenter.sendClientLogs()
    }

    func setSegmentPolicy(type: MediaSegmentType, policyName: String) {
        let policy: SegmentSkipPolicy =
            switch policyName {
            case "AutoSkip": .autoskip
            case "Ignore": .ignore
            default: .ask
            }
        presenter.setSegmentPolicy(type: type, policy: policy)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
