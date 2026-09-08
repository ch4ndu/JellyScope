// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension TvDownloadSectionKind {
    var title: String {
        switch self {
        case .completed: String(localized: "Completed")
        case .active: String(localized: "Active")
        case .queued: String(localized: "Queued")
        case .paused: String(localized: "Paused")
        case .failed: String(localized: "Failed")
        default: String(localized: "Downloads")
        }
    }
}

extension TvDownloadRowState {
    var title: String {
        switch self {
        case .completed: String(localized: "Downloaded")
        case .downloading: String(localized: "Downloading")
        case .finalizing: String(localized: "Finishing")
        case .queued: String(localized: "Waiting")
        case .paused: String(localized: "Paused")
        case .blockedbyquota: String(localized: "Storage allocation reached")
        case .failed: String(localized: "Download failed")
        default: String(localized: "Download")
        }
    }
}

extension TvDownloadFailureKind {
    var message: String {
        switch self {
        case .permissiondenied: String(localized: "Downloading is not allowed for this account.")
        case .sizeunavailable: String(localized: "The server did not provide a usable download size.")
        case .network: String(localized: "The network connection was interrupted.")
        case .serverunavailable: String(localized: "The Jellyfin server is unavailable.")
        case .sourcechanged: String(localized: "This media source changed. Start a new download from its detail screen.")
        case .unsupportedartifact: String(localized: "This download format is unsupported.")
        case .quotaexceeded: String(localized: "The download storage allocation is full.")
        case .devicestoragelow: String(localized: "Apple TV does not have enough safe free space.")
        case .missingartifact: String(localized: "Apple TV reclaimed or removed the downloaded media.")
        case .artifactinuse: String(localized: "This download is currently playing.")
        default: String(localized: "The download could not continue.")
        }
    }
}

extension TvDownloadsError {
    var message: String {
        switch self {
        case .loadfailed: String(localized: "Downloads could not be refreshed.")
        case .commandrejected: String(localized: "That download action is no longer available.")
        case .quotarejected: String(localized: "That storage allocation cannot be used on this Apple TV.")
        case .artifactinuse: String(localized: "Stop local playback before deleting this download.")
        case .staleconfirmation: String(localized: "The download changed. Review it again before removing it.")
        default: String(localized: "The download action could not be completed.")
        }
    }
}

extension TvDownloadRequestError {
    var message: String {
        switch self {
        case .permissiondenied: String(localized: "Downloading is not allowed for this account.")
        case .quotaunconfigured: String(localized: "Choose a download storage allocation in Settings or Downloads first.")
        case .quotaexceeded: String(localized: "This download does not fit in the current storage allocation.")
        case .devicestoragelow: String(localized: "Apple TV does not have enough safe free space for this download.")
        case .sizeunavailable: String(localized: "The server could not determine the download size.")
        case .sourcechanged: String(localized: "The selected media source changed. Close this sheet and choose Download again.")
        case .networkunavailable: String(localized: "The Jellyfin server could not be reached.")
        case .unsupportedartifact: String(localized: "This source cannot be prepared for offline playback.")
        case .playbackunsupported: String(localized: "This download cannot be played on Apple TV.")
        case .subtitleunavailable: String(localized: "Fixed quality cannot keep the selected subtitle. Choose Original or explicitly turn subtitles Off.")
        case .removalinprogress: String(localized: "This account is currently being removed.")
        default: String(localized: "The download request could not be completed.")
        }
    }
}

extension TvDownloadRequestOutcome {
    var message: String {
        switch self {
        case .queued: String(localized: "The download was added to the queue.")
        case .existing: String(localized: "A download for this source already exists.")
        case .schedulingdelayed: String(localized: "The download was queued, but it could not start yet. Resume or retry it from Downloads.")
        default: String(localized: "The download request was saved.")
        }
    }
}

enum DownloadLabels {
    private static let bytesFormatter: ByteCountFormatter = {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        formatter.includesUnit = true
        return formatter
    }()

    static func bytes(_ value: Int64) -> String {
        bytesFormatter.string(fromByteCount: max(0, value))
    }

    static func duration(milliseconds: Int64) -> String {
        let totalMinutes = max(0, milliseconds) / 60_000
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return hours > 0
            ? String(format: String(localized: "%lld hr %lld min"), hours, minutes)
            : String(format: String(localized: "%lld min"), minutes)
    }

    static func quality(_ choice: TvDownloadQualityChoice) -> String {
        guard choice.kind == .fixed,
              let bitrate = choice.maxBitrateBps?.int64Value,
              let height = choice.height?.int32Value else {
            return String(localized: "Original")
        }
        let mbps = Double(bitrate) / 1_000_000
        let bitrateText = mbps == floor(mbps) ? String(format: "%.0f", mbps) : String(format: "%.1f", mbps)
        let value = String(format: String(localized: "%@ Mbps · %dp"), bitrateText, height)
        return choice.usesUpToLabel
            ? String(format: String(localized: "Up to %@"), value)
            : value
    }
}
