// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension TvLanguageChoice {
    var localizedDisplayName: String {
        let languageCode = Self.twoLetterLanguageCode(for: code)
        return Locale.current.localizedString(forLanguageCode: languageCode) ?? code
    }

    private static func twoLetterLanguageCode(for code: String) -> String {
        switch code.lowercased() {
        case "eng": return "en"
        case "spa": return "es"
        case "fre": return "fr"
        case "ger": return "de"
        case "ita": return "it"
        case "por": return "pt"
        case "jpn": return "ja"
        case "kor": return "ko"
        case "chi": return "zh"
        case "hin": return "hi"
        case "tel": return "te"
        case "tam": return "ta"
        default: return code
        }
    }
}

extension TvTrackChoice {
    var localizedDisplayName: String {
        if let displayLabel = self.displayLabel?.trimmingCharacters(in: .whitespacesAndNewlines),
           !displayLabel.isEmpty {
            return displayLabel
        }
        guard let languageCode, !languageCode.isEmpty else {
            return String(
                format: String(localized: "Track %lld"),
                Int64(ordinal + 1)
            )
        }
        return TvLanguageChoice(code: languageCode).localizedDisplayName
    }
}

extension TvQualityChoice {
    var qualityChoiceIdentity: String {
        "\(mode.name):\(maxBitrateBps?.int64Value ?? -1)"
    }

    var localizedSettingsLabel: String {
        localizedQualityLabel(preferResolution: false)
    }

    var localizedPlayerLabel: String {
        localizedQualityLabel(preferResolution: true)
    }

    func localizedPlayerDetail(
        inheritedPolicy: PlaybackQualityPolicy,
        inheritedResolutionHeight: KotlinInt?
    ) -> String? {
        if inheritsPlaybackDefault {
            let source =
                defaultSource?.name == "VlcPlaybackSettings"
                    ? String(localized: "VLC default from Playback settings")
                    : String(localized: "From Playback settings")
            return String(
                format: String(localized: "%@ · %@"),
                localizedPolicyValue(inheritedPolicy, resolutionHeight: inheritedResolutionHeight),
                source
            )
        }
        guard mode.name == "Fixed" else { return nil }
        return localizedBitrate(maxBitrateBps)
    }

    private func localizedQualityLabel(preferResolution: Bool) -> String {
        if inheritsPlaybackDefault {
            return String(localized: "Use playback default")
        }
        switch mode.name {
        case "Auto": return String(localized: "Auto")
        case "Original": return String(localized: "Original")
        default:
            let bitrate = localizedMegabits(maxBitrateBps)
            if isCustom {
                return String(
                    format: String(localized: "Custom — %@ Mbps (bitrate only)"),
                    bitrate
                )
            }
            guard let resolutionHeight else { return localizedBitrate(maxBitrateBps) }
            if preferResolution {
                return String(format: String(localized: "%lldp"), Int64(resolutionHeight.int32Value))
            }
            return String(
                format: String(localized: "%@ Mbps · %lldp"),
                bitrate,
                Int64(resolutionHeight.int32Value)
            )
        }
    }

    private func localizedPolicyValue(
        _ policy: PlaybackQualityPolicy,
        resolutionHeight: KotlinInt?
    ) -> String {
        switch policy.mode.name {
        case "Auto": return String(localized: "Auto")
        case "Original": return String(localized: "Original")
        default:
            guard let resolutionHeight else { return localizedBitrate(policy.maxBitrateBps) }
            return String(
                format: String(localized: "%@ Mbps · %lldp"),
                localizedMegabits(policy.maxBitrateBps),
                Int64(resolutionHeight.int32Value)
            )
        }
    }

    private func localizedBitrate(_ bitrateBps: KotlinLong?) -> String {
        String(format: String(localized: "%@ Mbps"), localizedMegabits(bitrateBps))
    }

    private func localizedMegabits(_ bitrateBps: KotlinLong?) -> String {
        guard let bitrateBps else { return "0" }
        let formatter = NumberFormatter()
        formatter.locale = .current
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 1
        formatter.minimumFractionDigits = 0
        return formatter.string(from: NSNumber(value: Double(bitrateBps.int64Value) / 1_000_000)) ?? "0"
    }
}

// Every screen model follows the same shape: own the Kotlin presenter, seed
// state from its StateFlow, watch for changes on the main dispatcher, and
// close both the watch handle and the presenter on deinit. Swift never talks
// to Koin or repositories directly.

@MainActor
final class SessionModel: ObservableObject {
    @Published private(set) var state: TvSessionState
    private let presenter: TvSessionPresenter
    private var handle: WatchHandle?

    init() {
        let presenter = TvosEntry.shared.sessionPresenter()
        self.presenter = presenter
        self.state = presenter.state.value as! TvSessionState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func signOut() {
        presenter.signOut()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

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

@MainActor
final class HomeModel: ObservableObject {
    @Published private(set) var state: TvHomeState
    private let presenter: TvHomePresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.homePresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvHomeState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        // No init-time load: HomeView reloads on every appearance (including
        // pop-back from detail/playback) so Continue Watching stays fresh; the
        // presenter cancels any in-flight load, so appearances never race.
    }

    func reload() {
        presenter.load()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

@MainActor
final class LibrariesModel: ObservableObject {
    @Published private(set) var state: TvLibrariesState
    private let presenter: TvLibrariesPresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.librariesPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvLibrariesState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        presenter.load()
    }

    func reload() {
        presenter.load()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

@MainActor
final class LibraryBrowseModel: ObservableObject {
    @Published private(set) var state: TvLibraryBrowseState
    private let presenter: TvLibraryBrowsePresenter
    private var handle: WatchHandle?

    init(session: Session, libraryId: String) {
        let presenter = TvosEntry.shared.libraryBrowsePresenter(session: session, libraryId: libraryId)
        self.presenter = presenter
        self.state = presenter.state.value as! TvLibraryBrowseState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        presenter.load()
    }

    func loadMoreIfNeeded(focusedIndex: Int) {
        presenter.loadMoreIfNeeded(focusedIndex: Int32(focusedIndex))
    }

    func reload() {
        presenter.load()
    }

    func retryLoadMore() {
        presenter.loadMoreIfNeeded(focusedIndex: Int32(max(0, (state.items as [TvMediaCard]).count - 1)))
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

@MainActor
final class SearchModel: ObservableObject {
    @Published private(set) var state: TvSearchState
    private let presenter: TvSearchPresenter
    private var handle: WatchHandle?

    init(session: Session) {
        let presenter = TvosEntry.shared.searchPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvSearchState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func setQuery(_ query: String) {
        presenter.setQuery(query: query)
    }

    func submit(_ query: String) {
        presenter.submit(query: query)
    }

    func clearRecents() {
        presenter.clearRecents()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}

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

    func setDefaultMaxBitrate(_ maxBitrateBps: Int64?) {
        presenter.setDefaultMaxBitrate(maxBitrateBps: maxBitrateBps.map { KotlinLong(longLong: $0) })
    }

    func setDefaultQualityChoice(_ choice: TvQualityChoice) {
        presenter.setDefaultQualityChoice(
            modeName: choice.mode.name,
            maxBitrateBps: choice.maxBitrateBps,
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

    func setDiagnosticCollectionEnabled(_ enabled: Bool) {
        presenter.setDiagnosticCollectionEnabled(enabled: enabled)
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

@MainActor
final class ItemDetailModel: ObservableObject {
    @Published private(set) var state: TvItemDetailState
    private let presenter: TvItemDetailPresenter
    private var handle: WatchHandle?

    init(session: Session, itemId: String) {
        let presenter = TvosEntry.shared.itemDetailPresenter(session: session, itemId: itemId)
        self.presenter = presenter
        self.state = presenter.state.value as! TvItemDetailState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
        presenter.load()
    }

    func selectSeason(_ seasonId: String) {
        presenter.selectSeason(seasonId: seasonId)
    }

    func reload() {
        presenter.load()
    }

    func retrySeasons() {
        presenter.retrySeasons()
    }

    func retryEpisodes() {
        presenter.retryEpisodes()
    }

    func togglePlayed() {
        presenter.togglePlayed()
    }

    func toggleFavorite() {
        presenter.toggleFavorite()
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
