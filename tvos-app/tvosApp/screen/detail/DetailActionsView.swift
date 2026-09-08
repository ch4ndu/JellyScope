// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailActionsView: View {
    let session: Session
    @ObservedObject var model: ItemDetailModel
    let content: TvItemDetailContent
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusState private var primaryFocused: Bool
    @State private var showingVersions = false
    @State private var showingAudio = false
    @State private var showingSubtitles = false
    @State private var showingMediaInfo = false
    @State private var downloadRequest: DetailDownloadRequest?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 22) {
                if content.isPlayable {
                    Button {
                        if let route = model.playbackRoute() {
                            onPlay(route)
                        }
                    } label: {
                        Label(content.playMode.actionTitle, systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(appearance.accent)
                    .focused($primaryFocused)
                    .defaultFocus($primaryFocused, true)
                    .disabled(playbackSelectionBlocked)
                }

                if content.canRestart {
                    Button {
                        if let route = model.playbackRoute(restart: true) {
                            onPlay(route)
                        }
                    } label: {
                        Label(String(localized: "Play from Beginning"), systemImage: "backward.end.fill")
                    }
                    .disabled(playbackSelectionBlocked || !content.isPlayable)
                }

                if content.kind == .movie || content.kind == .episode {
                    Button(action: model.togglePlayed) {
                        Label(
                            content.played ? String(localized: "Watched") : String(localized: "Mark Watched"),
                            systemImage: content.played ? "checkmark.circle.fill" : "checkmark.circle"
                        )
                    }
                }

                if session.enableContentDownloading && content.isPlayable && (content.kind == .movie || content.kind == .episode) {
                    Button {
                        if let selection = model.downloadSelection() {
                            downloadRequest = DetailDownloadRequest(selection: selection)
                        }
                    } label: {
                        Label("Download", systemImage: "arrow.down.circle")
                    }
                    .disabled(playbackSelectionBlocked)
                }

                Button(action: model.toggleFavorite) {
                    Label(
                        content.isFavorite ? String(localized: "Favorite") : String(localized: "Add Favorite"),
                        systemImage: content.isFavorite ? "heart.fill" : "heart"
                    )
                }
            }

            HStack(spacing: 18) {
                if model.state.versions.count > 1 {
                    Button(String(localized: "Version")) { showingVersions = true }
                }
                if !model.state.audioTracks.isEmpty {
                    Button(String(localized: "Audio")) { showingAudio = true }
                }
                if !model.state.subtitleTracks.isEmpty || subtitleContext != nil {
                    Button(String(localized: "Subtitles")) { showingSubtitles = true }
                }
                if !model.state.mediaInfo.isEmpty {
                    Button(String(localized: "Media Info")) { showingMediaInfo = true }
                }
            }

            if model.state.actionError != nil || model.state.subtitleSelectionFailed {
                Text(String(localized: "The change could not be saved. Please try again."))
                    .font(.callout)
                    .foregroundStyle(.red)
            }

            if model.state.isSubtitleSelectionPending {
                ProgressView(String(localized: "Applying subtitle selection…"))
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .sheet(isPresented: $showingVersions) {
            DetailVersionPicker(versions: model.state.versions, onSelect: model.selectVersion)
        }
        .sheet(isPresented: $showingAudio) {
            DetailTrackPicker(
                kind: .audio,
                tracks: model.state.audioTracks,
                subtitleMode: model.state.subtitleMode,
                onSelect: model.selectAudio,
                onSelectOff: {}
            )
        }
        .sheet(isPresented: $showingSubtitles) {
            DetailTrackPicker(
                kind: .subtitles,
                tracks: model.state.subtitleTracks,
                subtitleMode: model.state.subtitleMode,
                onSelect: model.selectSubtitleTrack,
                onSelectOff: { model.selectSubtitle(nil) },
                subtitleContext: subtitleContext,
                onSelectLocalSubtitle: model.selectLocalSubtitle
            )
        }
        .sheet(item: $downloadRequest) { request in
            DownloadRequestView(session: session, selection: request.selection)
        }
        .onChange(of: model.state.selectedMediaSourceId) { _, _ in
            showingSubtitles = false
            downloadRequest = nil
        }
        .onChange(of: content.id) { _, _ in
            showingSubtitles = false
            downloadRequest = nil
        }
        .sheet(isPresented: $showingMediaInfo) {
            DetailMediaInfoView(rows: model.state.mediaInfo)
        }
    }

    private var subtitleContext: DetailSubtitleContext? {
        guard content.kind == .movie || content.kind == .episode,
              let sourceId = model.state.selectedMediaSourceId else { return nil }
        return DetailSubtitleContext(
            session: session,
            itemId: content.id,
            mediaSourceId: sourceId,
            selectedAssetId: model.state.selectedSubtitleAssetId
        )
    }

    private var playbackSelectionBlocked: Bool {
        model.state.isSubtitleSelectionPending || model.state.subtitleSelectionFailed
    }
}

private struct DetailDownloadRequest: Identifiable {
    let id = UUID()
    let selection: TvDetailPlaybackSelection
}
