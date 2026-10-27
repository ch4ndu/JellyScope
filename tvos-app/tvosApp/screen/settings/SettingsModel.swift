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
        presenter.accept(intent: TvSettingsIntentSetDefaultQualityChoice(
            modeName: choice.mode.name,
            maxBitrateBps: choice.maxBitrateBps
        ))
    }

    func setPreferredAudioLanguage(_ code: String?) {
        presenter.accept(intent: TvSettingsIntentSetPreferredAudioLanguage(code: code))
    }

    func setPreferredSubtitleLanguage(_ code: String?) {
        presenter.accept(intent: TvSettingsIntentSetPreferredSubtitleLanguage(code: code))
    }

    func setPlaybackWarningsEnabled(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetPlaybackWarningsEnabled(enabled: enabled))
    }

    func setAutoPlayNext(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetAutoPlayNext(enabled: enabled))
    }

    func setStillWatchingPrompt(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetStillWatchingPrompt(enabled: enabled))
    }

    func setAutoPlayNextDelaySeconds(_ seconds: Int) {
        presenter.accept(intent: TvSettingsIntentSetAutoPlayNextDelaySeconds(seconds: Int32(seconds)))
    }

    func setPlayerClockEnabled(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetPlayerClockEnabled(enabled: enabled))
    }

    func setRememberLastLibraryView(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetRememberLastLibraryViewEnabled(enabled: enabled))
    }

    func setDiagnosticCollectionEnabled(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetDiagnosticCollectionEnabled(enabled: enabled))
    }

    func setPlaybackInfoAtStartEnabled(_ enabled: Bool) {
        presenter.accept(intent: TvSettingsIntentSetPlaybackInfoAtStartEnabled(enabled: enabled))
    }

    func retryPlaybackInfoAtStart() {
        presenter.accept(intent: TvSettingsIntentRetryPlaybackInfoAtStart.shared)
    }

    func retryLoad() {
        presenter.accept(intent: TvSettingsIntentRetryLoad.shared)
    }

    func sendClientLogs() {
        presenter.accept(intent: TvSettingsIntentSendClientLogs.shared)
    }

    func sendPlaybackTimeline() {
        presenter.accept(intent: TvSettingsIntentSendPlaybackTimeline.shared)
    }

    func setSegmentPolicy(type: MediaSegmentType, policyName: String) {
        let policy: SegmentSkipPolicy =
            switch policyName {
            case "AutoSkip": .autoskip
            case "Ignore": .ignore
            default: .ask
            }
        presenter.accept(intent: TvSettingsIntentSetSegmentPolicy(type: type, policy: policy))
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
