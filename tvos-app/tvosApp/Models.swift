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
