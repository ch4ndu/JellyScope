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

extension TvPlaybackNotice {
    var localizedMessage: String {
        guard kind == .changerejected, let operation = operation else {
            return String(localized: "The selected local subtitle is unavailable. Subtitles were turned off.")
        }
        let reason = changeRejectionReason(operation)
        switch operation {
        case .quality: return String(localized: "Could not change quality. \(reason) Current playback was kept.")
        case .audio: return String(localized: "Could not change audio. \(reason) Current playback was kept.")
        case .subtitle: return String(localized: "Could not change subtitles. \(reason) Current playback was kept.")
        case .localsubtitle: return String(localized: "Could not change the local subtitle. \(reason) Current playback was kept.")
        default: return String(localized: "Could not seek. \(reason) Current playback was kept.")
        }
    }

    private func changeRejectionReason(_ operation: TvPlaybackChangeOperation) -> String {
        if error is PlaybackErrorNetwork { return String(localized: "The server could not be reached.") }
        if error is PlaybackErrorUnsupportedMedia { return String(localized: "The requested option is not supported for this item.") }
        if error == nil && operation == .localsubtitle { return String(localized: "The selected local subtitle is unavailable.") }
        return String(localized: "The requested option could not be prepared.")
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
