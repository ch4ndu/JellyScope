// SPDX-License-Identifier: MPL-2.0

import SharedTv
import UIKit

enum AvPlayerMenus {
    static func configurationKey(for state: TvPlaybackUiState) -> String {
        let audio = state.audioTracks.map { "\($0.streamIndex):\($0.selected)" }.joined(separator: ",")
        let subtitles = state.subtitleTracks.map { "\($0.streamIndex):\($0.selected)" }.joined(separator: ",")
        let quality = state.qualityChoices.map { "\($0.maxBitrateBps?.int64Value ?? -1):\($0.selected)" }.joined(separator: ",")
        let segment = state.activeSegment.map { "\($0.type.name):\($0.endMs)" } ?? "-"
        return "\(state.playerIdentity)|\(state.planEpoch)|\(audio)|\(subtitles)|\(quality)|\(segment)|\(state.upNextVisible)|\(state.localSubtitleSelected)"
    }

    static func transportMenus(for state: TvPlaybackUiState, model: PlaybackModel) -> [UIMenuElement] {
        let identity = actionIdentity(for: state)
        var menus: [UIMenuElement] = []
        if state.audioTracks.count > 1 {
            let children = state.audioTracks.map { track in
                UIAction(title: track.localizedDisplayName, state: track.selected ? .on : .off) { [weak model] _ in
                    model?.selectAudio(track.streamIndex, identity: identity)
                }
            }
            menus.append(UIMenu(title: String(localized: "Audio"), image: UIImage(systemName: "waveform"), options: .singleSelection, children: children))
        }
        let subtitleSelected = state.subtitleTracks.contains { $0.selected } || state.localSubtitleSelected
        if !state.subtitleTracks.isEmpty || subtitleSelected {
            var children: [UIMenuElement] = [
                UIAction(title: String(localized: "Off"), state: subtitleSelected ? .off : .on) { [weak model] _ in
                    model?.selectSubtitle(nil, identity: identity)
                },
            ]
            children += state.subtitleTracks.map { track in
                UIAction(title: track.localizedDisplayName, state: track.selected ? .on : .off) { [weak model] _ in
                    model?.selectSubtitle(track.streamIndex, identity: identity)
                }
            }
            menus.append(UIMenu(title: String(localized: "Subtitles"), image: UIImage(systemName: "captions.bubble"), options: .singleSelection, children: children))
        }
        if state.qualityChoices.count > 1 {
            let children = state.qualityChoices.map { choice in
                UIAction(
                    title: choice.localizedPlayerLabel,
                    subtitle: choice.localizedPlayerDetail(
                        inheritedPolicy: state.inheritedQualityPolicy,
                        inheritedResolutionHeight: state.inheritedQualityResolutionHeight
                    ),
                    state: choice.selected ? .on : .off
                ) { [weak model] _ in model?.selectQuality(choice, identity: identity) }
            }
            menus.append(UIMenu(title: String(localized: "Quality"), image: UIImage(systemName: "dial.high"), options: .singleSelection, children: children))
        }
        return menus
    }

    static func contextualActions(for state: TvPlaybackUiState, model: PlaybackModel) -> [UIAction] {
        let identity = actionIdentity(for: state)
        var actions: [UIAction] = []
        if let segment = state.activeSegment, segment.askUser {
            actions.append(UIAction(title: segment.type.skipTitle) { [weak model] _ in
                model?.skipActiveSegment(identity: identity)
            })
        }
        if state.upNextVisible {
            actions.append(UIAction(title: String(localized: "Next Episode")) { [weak model] _ in
                model?.playNextEpisode(identity: identity)
            })
        }
        return actions
    }

    private static func actionIdentity(for state: TvPlaybackUiState) -> PlaybackActionIdentity {
        PlaybackActionIdentity(
            playerIdentity: state.playerIdentity,
            planEpoch: state.planEpoch,
            itemId: state.itemId
        )
    }
}
