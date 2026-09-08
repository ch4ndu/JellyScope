// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension MediaSegmentType {
    var skipTitle: String {
        switch self {
        case .intro: String(localized: "Skip Intro")
        case .outro: String(localized: "Skip Outro")
        case .recap: String(localized: "Skip Recap")
        case .preview: String(localized: "Skip Preview")
        case .commercial: String(localized: "Skip Ad")
        default: String(localized: "Skip")
        }
    }
}

extension Optional where Wrapped == PlaybackError {
    var playbackMessage: String {
        guard let error = self else { return String(localized: "An unknown playback error occurred.") }
        if error is PlaybackErrorNetwork { return String(localized: "A network error interrupted playback.") }
        if error is PlaybackErrorOfflineArtifactUnavailable { return String(localized: "This download is missing, incomplete, or no longer available.") }
        if error is PlaybackErrorOfflinePlayerUnavailable { return String(localized: "The player required for this download is unavailable on this Apple TV.") }
        if error is PlaybackErrorUnsupportedMedia { return String(localized: "This title cannot be played on Apple TV.") }
        if error is PlaybackErrorDecoder { return String(localized: "The video could not be decoded.") }
        if error is PlaybackErrorAudioOutput { return String(localized: "Audio output failed.") }
        if error is PlaybackErrorDrm { return String(localized: "This title is protected and cannot be played.") }
        return String(localized: "An unknown playback error occurred.")
    }
}

extension PlayerVideoSizing {
    var localizedTitle: String {
        switch self {
        case .fit: String(localized: "Fit")
        case .fill: String(localized: "Fill")
        case .zoom: String(localized: "Zoom")
        }
    }
}

extension TvPlaybackDiagnosticField {
    var localizedTitle: String {
        switch self {
        case .backend: String(localized: "Backend")
        case .playmethod: String(localized: "Play Method")
        case .status: String(localized: "Status")
        case .runtimeformat: String(localized: "Runtime Format")
        case .droppedframes: String(localized: "Dropped Frames")
        case .bandwidth: String(localized: "Bandwidth")
        case .bufferedahead: String(localized: "Buffered Ahead")
        case .rebuffers: String(localized: "Rebuffers")
        case .audiounderruns: String(localized: "Audio Underruns")
        default: String(localized: "Playback Info")
        }
    }
}

func playerTimeLabel(_ milliseconds: Int64) -> String {
    let seconds = max(0, milliseconds / 1_000)
    let hours = seconds / 3_600
    let minutes = (seconds % 3_600) / 60
    let remaining = seconds % 60
    if hours > 0 { return String(format: "%lld:%02lld:%02lld", hours, minutes, remaining) }
    return String(format: "%lld:%02lld", minutes, remaining)
}
