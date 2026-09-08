// SPDX-License-Identifier: MPL-2.0

import AVFoundation
import AVKit
import SharedTv
import UIKit

@MainActor
final class AvPlayerMetadata {
    private var artworkTask: Task<Void, Never>?
    private var appliedEpoch: Int64 = -1

    func apply(state: TvPlaybackUiState, to item: AVPlayerItem) {
        appliedEpoch = state.planEpoch
        item.navigationMarkerGroups = chapterMarkers(for: state)
        item.externalMetadata = titleMetadata(for: state)
        loadArtwork(for: state, into: item)
    }

    private func chapterMarkers(for state: TvPlaybackUiState) -> [AVNavigationMarkersGroup] {
        guard !state.chapters.isEmpty else { return [] }
        let duration = state.durationMs?.int64Value ?? Int64.max
        let groups = state.chapters.enumerated().map { index, chapter -> AVTimedMetadataGroup in
            let end = index + 1 < state.chapters.count ? state.chapters[index + 1].startMs : duration
            let titleItem = AVMutableMetadataItem()
            titleItem.identifier = .commonIdentifierTitle
            titleItem.value = (chapter.name.isEmpty ? String(format: String(localized: "Chapter %lld", comment: "Fallback title for an unnamed chapter"), Int64(index + 1)) : chapter.name) as NSString
            titleItem.extendedLanguageTag = "und"
            let range = CMTimeRange(
                start: CMTime(value: chapter.startMs, timescale: 1_000),
                end: CMTime(value: max(end, chapter.startMs), timescale: 1_000)
            )
            return AVTimedMetadataGroup(items: [titleItem], timeRange: range)
        }
        return [AVNavigationMarkersGroup(title: nil, timedNavigationMarkers: groups)]
    }

    private func titleMetadata(for state: TvPlaybackUiState) -> [AVMetadataItem] {
        var items: [AVMetadataItem] = []
        if let title = state.title { items.append(metadataItem(.commonIdentifierTitle, value: title)) }
        if let series = state.seriesName, let episode = state.episodeLabel {
            items.append(metadataItem(.iTunesMetadataTrackSubTitle, value: String(format: String(localized: "%@ • %@"), series, episode)))
        } else if let subtitle = state.seriesName ?? state.episodeLabel {
            items.append(metadataItem(.iTunesMetadataTrackSubTitle, value: subtitle))
        }
        return items
    }

    private func metadataItem(_ identifier: AVMetadataIdentifier, value: String) -> AVMetadataItem {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value as NSString
        item.extendedLanguageTag = "und"
        return item
    }

    private func loadArtwork(for state: TvPlaybackUiState, into item: AVPlayerItem) {
        artworkTask?.cancel()
        guard let artworkUrl = state.artworkUrl else { return }
        let epoch = state.planEpoch
        artworkTask = Task { [weak self, weak item] in
            guard let image = await ImageFetcher.shared.image(for: artworkUrl),
                  let data = image.jpegData(compressionQuality: 0.85),
                  let self, let item, !Task.isCancelled, self.appliedEpoch == epoch else { return }
            let artwork = AVMutableMetadataItem()
            artwork.identifier = .commonIdentifierArtwork
            artwork.value = data as NSData
            artwork.dataType = kCMMetadataBaseDataType_JPEG as String
            artwork.extendedLanguageTag = "und"
            item.externalMetadata = item.externalMetadata + [artwork]
        }
    }
}
