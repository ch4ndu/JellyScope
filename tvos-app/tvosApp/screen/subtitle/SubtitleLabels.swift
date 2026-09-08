// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension TvOpenSubtitleResult {
    var localizedMetadata: String {
        var values = [
            TvLanguageChoice(code: language).localizedDisplayName,
            format?.uppercased() ?? String(localized: "Unknown format")
        ]
        if hearingImpaired {
            values.append(String(localized: "Hearing impaired"))
        }
        if forced {
            values.append(String(localized: "Forced"))
        }
        if trusted {
            values.append(String(localized: "Trusted"))
        }
        if let rating {
            values.append(
                String(
                    format: String(localized: "Rating %@"),
                    subtitleDecimal(rating.doubleValue)
                )
            )
        }
        if let downloadCount {
            values.append(
                String(
                    format: String(localized: "%lld downloads"),
                    downloadCount.int64Value
                )
            )
        }
        if let fps {
            values.append(
                String(
                    format: String(localized: "%@ FPS"),
                    subtitleDecimal(fps.doubleValue)
                )
            )
        }
        if !available {
            values.append(unavailableReason ?? String(localized: "Unavailable"))
        }
        return values.joined(separator: " · ")
    }
}

extension TvLocalSubtitle {
    var localizedMetadata: String {
        var values = [
            TvLanguageChoice(code: language).localizedDisplayName,
            localizedSyncLabel
        ]
        if hearingImpaired {
            values.append(String(localized: "Hearing impaired"))
        }
        if forced {
            values.append(String(localized: "Forced"))
        }
        if trusted {
            values.append(String(localized: "Trusted"))
        }
        return values.joined(separator: " · ")
    }

    private var localizedSyncLabel: String {
        switch sync {
        case .pending:
            String(localized: "Waiting to sync")
        case .uploading:
            String(localized: "Uploading")
        case .reconciling:
            String(localized: "Checking upload")
        case .confirmed:
            String(localized: "Synced")
        case .uploadedunconfirmed:
            String(localized: "Sync confirmation pending")
        case .localonlyalternatesource:
            String(localized: "Available on this version only")
        case .permissiondenied:
            String(localized: "Jellyfin permission required")
        case .failedpermanent:
            String(localized: "Sync unavailable")
        default:
            String(localized: "Sync unavailable")
        }
    }
}

private func subtitleDecimal(_ value: Double) -> String {
    let formatter = NumberFormatter()
    formatter.locale = .current
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 1
    formatter.minimumFractionDigits = 0
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}
