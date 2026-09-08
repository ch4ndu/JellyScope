// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlayerControlsView: View {
    @ObservedObject var model: PlaybackModel
    @Binding var videoSizing: PlayerVideoSizing
    let onOpenQueue: () -> Void
    let onOpenSubtitleSearch: () -> Void
    let onOpenSubtitleStyle: () -> Void
    let onOpenDiagnostics: () -> Void
    let onClose: () -> Void
    let initialFocus: PlayerControlsFocus?
    @FocusState private var focusedControl: PlayerControlsFocus?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Playback Controls")
                .font(.title2.bold())

            HStack(spacing: 16) {
                trackMenu(title: String(localized: "Audio"), tracks: model.audioTracks) { model.selectAudio($0) }
                subtitleMenu
                chapterMenu
                speedMenu
                if model.avPlayer != nil {
                    sizingMenu
                }
            }

            HStack(spacing: 16) {
                if !model.isOffline && !model.queue.items.isEmpty {
                    Button("Play Queue", action: onOpenQueue)
                        .focused($focusedControl, equals: .queue)
                }
                if model.canOpenLocalSubtitles {
                    Button("Find Subtitles", action: onOpenSubtitleSearch)
                        .focused($focusedControl, equals: .subtitleSearch)
                }
                if model.subtitleStyleSupported {
                    Button("Subtitle Style", action: onOpenSubtitleStyle)
                        .focused($focusedControl, equals: .subtitleStyle)
                }
                Button("Playback Info", action: onOpenDiagnostics)
                    .focused($focusedControl, equals: .diagnostics)
                Spacer()
                Button("Done", action: onClose)
                    .focused($focusedControl, equals: .done)
            }

            Text("Subtitle timing controls are unavailable with this player.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if model.avPlayer == nil {
                Text("Video sizing is unavailable with this player.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(36)
        .frame(maxWidth: 1180)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .onAppear { focusedControl = initialFocus ?? .done }
    }

    private func trackMenu(
        title: String,
        tracks: [TvTrackChoice],
        select: @escaping (Int32) -> Void
    ) -> some View {
        Menu(title) {
            ForEach(tracks, id: \.streamIndex) { track in
                Button {
                    select(track.streamIndex)
                } label: {
                    if track.selected {
                        Label(track.localizedDisplayName, systemImage: "checkmark")
                    } else {
                        Text(track.localizedDisplayName)
                    }
                }
            }
        }
        .disabled(tracks.isEmpty)
    }

    private var subtitleMenu: some View {
        Menu(String(localized: "Subtitles")) {
            Button("Off") { model.selectSubtitle(nil) }
            ForEach(model.subtitleTracks, id: \.streamIndex) { track in
                Button {
                    model.selectSubtitle(track.streamIndex)
                } label: {
                    if track.selected {
                        Label(track.localizedDisplayName, systemImage: "checkmark")
                    } else {
                        Text(track.localizedDisplayName)
                    }
                }
            }
        }
        .disabled(model.subtitleTracks.isEmpty && !model.hasSelectedSubtitle)
    }

    private var chapterMenu: some View {
        Menu(String(localized: "Chapters")) {
            ForEach(Array(model.chapters.enumerated()), id: \.offset) { index, chapter in
                Button(chapter.name.isEmpty ? String(format: String(localized: "Chapter %lld"), Int64(index + 1)) : chapter.name) {
                    model.seekToChapter(index)
                }
            }
        }
        .disabled(model.chapters.isEmpty)
    }

    private var speedMenu: some View {
        Menu(String(localized: "Speed")) {
            ForEach(model.speedChoices, id: \.speed) { choice in
                Button {
                    model.setPlaybackSpeed(choice.speed)
                } label: {
                    if choice.selected {
                        Label(String(format: String(localized: "%@×"), speedLabel(choice.speed)), systemImage: "checkmark")
                    } else {
                        Text(String(format: String(localized: "%@×"), speedLabel(choice.speed)))
                    }
                }
            }
        }
    }

    private var sizingMenu: some View {
        Menu(String(localized: "Video Size")) {
            ForEach(PlayerVideoSizing.allCases) { sizing in
                Button {
                    videoSizing = sizing
                } label: {
                    if sizing == videoSizing {
                        Label(sizing.localizedTitle, systemImage: "checkmark")
                    } else {
                        Text(sizing.localizedTitle)
                    }
                }
            }
        }
    }

    private func speedLabel(_ speed: Float) -> String {
        speed == Float(Int(speed)) ? String(Int(speed)) : String(format: "%.2g", speed)
    }
}

enum PlayerControlsFocus: Hashable {
    case queue
    case subtitleSearch
    case subtitleStyle
    case diagnostics
    case done
}
