// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

extension TvDetailPlaybackSelection {
    var playbackRoute: PlaybackRoute {
        PlaybackRoute(
            itemId: itemId,
            mediaSourceId: mediaSourceId,
            startPositionTicks: startPositionTicks,
            audioStreamIndex: audioStreamIndex?.int32Value,
            subtitleMode: subtitleMode,
            subtitleStreamIndex: subtitleStreamIndex?.int32Value,
            subtitleAssetId: subtitleAssetId
        )
    }
}

extension TvDetailPlayMode {
    var actionTitle: String {
        switch self {
        case .resume: return String(localized: "Resume")
        case .restart: return String(localized: "Play")
        default: return String(localized: "Play")
        }
    }
}

extension TvRelatedGroup {
    var groupId: String {
        "\(kind.name):\(label ?? "")"
    }

    var displayTitle: String {
        switch kind {
        case .cast: return String(localized: "Cast & Crew")
        case .similar: return String(localized: "More Like This")
        case .genre:
            guard let label else { return String(localized: "Similar Genre") }
            return String(format: String(localized: "Because it's %@"), label)
        case .studio: return label ?? String(localized: "From the Same Studio")
        default: return label ?? String(localized: "Related")
        }
    }
}

extension TvDetailMediaInfoField {
    var displayTitle: String {
        switch self {
        case .name: return String(localized: "Source")
        case .container: return String(localized: "Container")
        case .runtime: return String(localized: "Runtime")
        case .size: return String(localized: "Size")
        case .resolution: return String(localized: "Resolution")
        case .codec: return String(localized: "Codec")
        case .framerate: return String(localized: "Frame Rate")
        case .language: return String(localized: "Language")
        case .channels: return String(localized: "Channels")
        case .bitrate: return String(localized: "Bitrate")
        default: return String(localized: "Details")
        }
    }
}

extension TvDetailMediaInfoRow {
    var displayValue: String {
        switch field {
        case .runtime:
            return String(format: String(localized: "%@ min"), value)
        case .size:
            return String(format: String(localized: "%@ GB"), value)
        case .framerate:
            return String(format: String(localized: "%@ fps"), value)
        case .bitrate:
            return String(format: String(localized: "%@ Mbps"), value)
        default:
            return value
        }
    }
}

extension MediaPersonType {
    var displayTitle: String {
        switch self {
        case .actor: return String(localized: "Actor")
        case .director: return String(localized: "Director")
        case .writer: return String(localized: "Writer")
        case .producer: return String(localized: "Producer")
        default: return String(localized: "Crew")
        }
    }
}
