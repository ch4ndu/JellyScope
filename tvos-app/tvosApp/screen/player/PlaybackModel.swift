// SPDX-License-Identifier: MPL-2.0

import AVFoundation
import Foundation
import SharedTv
import SwiftUI
import UIKit

@MainActor
final class PlaybackModel: ObservableObject {
    @Published private(set) var onlineState: TvPlaybackUiState?
    @Published private(set) var offlineState: TvOfflinePlaybackUiState?
    @Published private(set) var avPlayer: AVPlayer?
    @Published private(set) var vlcSurface: UIView?
    @Published var videoSizing: PlayerVideoSizing = .fit

    let session: Session
    let isOffline: Bool
    let startWithPlaybackInfoOverlay: Bool

    private let onlinePresenter: TvPlaybackSessionPresenter?
    private let offlinePresenter: TvOfflinePlaybackPresenter?
    private var handle: WatchHandle?
    private var installedPlayerIdentity: Int64?
    private var closed = false

    init(session: Session, route: PlaybackRoute) {
        self.session = session
        self.isOffline = false
        let presenter = TvosEntry.shared.playbackPresenter(
            session: session,
            itemId: route.itemId,
            mediaSourceId: route.mediaSourceId,
            startPositionTicks: route.startPositionTicks,
            audioStreamIndex: route.audioStreamIndex.map { KotlinInt(int: $0) },
            subtitleMode: route.subtitleMode,
            subtitleStreamIndex: route.subtitleStreamIndex.map { KotlinInt(int: $0) },
            subtitleAssetId: route.subtitleAssetId
        )
        self.onlinePresenter = presenter
        self.offlinePresenter = nil
        self.startWithPlaybackInfoOverlay = presenter.startWithPlaybackInfoOverlay
        self.onlineState = presenter.state.value as? TvPlaybackUiState
        self.offlineState = nil
        self.handle = presenter.watchState { [weak self] state in
            guard let self else { return }
            self.onlineState = state
            self.refreshNativeHost()
        }
        refreshNativeHost()
    }

    init(session: Session, offlineRoute: OfflinePlaybackRoute) {
        self.session = session
        self.isOffline = true
        let presenter = TvosEntry.shared.offlinePlaybackPresenter(
            session: session,
            downloadId: offlineRoute.downloadId,
            restart: offlineRoute.restart
        )
        self.onlinePresenter = nil
        self.offlinePresenter = presenter
        self.startWithPlaybackInfoOverlay = presenter.startWithPlaybackInfoOverlay
        self.onlineState = nil
        self.offlineState = presenter.state.value as? TvOfflinePlaybackUiState
        self.handle = presenter.watchState { [weak self] state in
            guard let self else { return }
            self.offlineState = state
            self.refreshNativeHost()
        }
        refreshNativeHost()
    }

    var playerInstalled: Bool { onlineState?.playerInstalled ?? offlineState?.playerInstalled ?? false }
    var playerIdentity: Int64 { onlineState?.playerIdentity ?? offlineState?.playerIdentity ?? 0 }
    var phase: TvPlaybackPhase { onlineState?.phase ?? offlineState?.phase ?? .loading }
    var status: PlaybackStatus { onlineState?.status ?? offlineState?.status ?? .idle }
    var itemId: String { onlineState?.itemId ?? offlineState?.itemId ?? "" }
    var mediaSourceId: String? { onlineState?.mediaSourceId ?? offlineState?.mediaSourceId }
    var title: String? { onlineState?.title ?? offlineState?.title }
    var seriesName: String? { onlineState?.seriesName ?? offlineState?.seriesName }
    var episodeLabel: String? { onlineState?.episodeLabel ?? offlineState?.episodeLabel }
    var positionMs: Int64 { onlineState?.positionMs ?? offlineState?.positionMs ?? 0 }
    var durationMs: Int64? { (onlineState?.durationMs ?? offlineState?.durationMs)?.int64Value }
    var bufferedPositionMs: Int64 { onlineState?.bufferedPositionMs ?? offlineState?.bufferedPositionMs ?? 0 }
    var chapters: [TvChapter] { onlineState?.chapters ?? offlineState?.chapters ?? [] }
    var audioTracks: [TvTrackChoice] { onlineState?.audioTracks ?? offlineState?.audioTracks ?? [] }
    var subtitleTracks: [TvTrackChoice] { onlineState?.subtitleTracks ?? offlineState?.subtitleTracks ?? [] }
    var speedChoices: [TvPlaybackSpeedChoice] { onlineState?.playbackSpeedChoices ?? offlineState?.playbackSpeedChoices ?? [] }
    var playbackSpeed: Float { onlineState?.playbackSpeed ?? offlineState?.playbackSpeed ?? 1 }
    var subtitleStyle: SubtitleStyle? { onlineState?.subtitleStyle ?? offlineState?.subtitleStyle }
    var subtitleStyleSupported: Bool { onlineState?.subtitleStyleSupported ?? offlineState?.subtitleStyleSupported ?? false }
    var diagnostics: [TvPlaybackDiagnosticRow] { onlineState?.diagnostics ?? offlineState?.diagnostics ?? [] }
    var error: PlaybackError? { onlineState?.error ?? offlineState?.error }
    var queue: TvPlaybackQueueState {
        onlineState?.queue ?? TvPlaybackQueueState(items: [], currentIndex: -1, shuffled: false, isPending: false)
    }
    var nextEpisode: TvMediaCard? { onlineState?.nextEpisode }
    var upNextVisible: Bool { onlineState?.upNextVisible ?? false }
    var countdownSeconds: Int? { onlineState?.nextUpCountdownSeconds.map { Int($0.int32Value) } }
    var stillWatchingVisible: Bool { onlineState?.stillWatchingVisible ?? false }
    var keepsPlayerMounted: Bool { onlineState?.keepsPlayerMounted ?? false }
    var playbackActionNotice: PlaybackActionNotice? { onlineState?.playbackActionNotice }
    var playbackActions: [PlaybackAction] { onlineState?.playbackActions ?? [] }
    var qualityChoices: [TvQualityChoice] { onlineState?.qualityChoices ?? [] }
    var playbackGuidance: PlaybackHealthGuidance? { onlineState?.playbackGuidance }
    var subtitleNotice: TvPlaybackNotice? { onlineState?.subtitleNotice }
    var localSubtitleSelected: Bool { onlineState?.localSubtitleSelected ?? false }
    var canOpenLocalSubtitles: Bool { !isOffline && !itemId.isEmpty && mediaSourceId != nil }
    var hasSelectedSubtitle: Bool {
        subtitleTracks.contains(where: { $0.selected }) || localSubtitleSelected
    }
    var isPlaying: Bool { status == .playing || status == .buffering }
    var playbackActionIdentity: PlaybackActionIdentity? {
        guard let state = onlineState else { return nil }
        return PlaybackActionIdentity(
            playerIdentity: state.playerIdentity,
            planEpoch: state.planEpoch,
            itemId: state.itemId
        )
    }
    var subtitleContext: PlaybackSubtitleContext? {
        guard let state = onlineState, let mediaSourceId = state.mediaSourceId else { return nil }
        return PlaybackSubtitleContext(
            itemId: state.itemId,
            mediaSourceId: mediaSourceId,
            launchIdentity: PlaybackActionIdentity(
                playerIdentity: state.playerIdentity,
                planEpoch: state.planEpoch,
                itemId: state.itemId
            )
        )
    }

    func start() {
        onlinePresenter?.start()
        offlinePresenter?.start()
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        handle = nil
        onlinePresenter?.close()
        offlinePresenter?.close()
        avPlayer = nil
        vlcSurface = nil
    }

    func play() { onlinePresenter?.play(); offlinePresenter?.play() }
    func pause() { onlinePresenter?.pause(); offlinePresenter?.pause() }
    func togglePlayPause() { onlinePresenter?.togglePlayPause(); offlinePresenter?.togglePlayPause() }
    func seek(to positionMs: Int64) { onlinePresenter?.seekTo(positionMs: positionMs); offlinePresenter?.seekTo(positionMs: positionMs) }
    func selectAudio(_ streamIndex: Int32, identity: PlaybackActionIdentity? = nil) {
        guard accepts(identity) else { return }
        onlinePresenter?.selectAudio(streamIndex: streamIndex)
        offlinePresenter?.selectAudio(streamIndex: streamIndex)
    }
    func selectSubtitle(_ streamIndex: Int32?, identity: PlaybackActionIdentity? = nil) {
        guard accepts(identity) else { return }
        let value = streamIndex.map { KotlinInt(int: $0) }
        onlinePresenter?.selectSubtitle(streamIndex: value)
        offlinePresenter?.selectSubtitle(streamIndex: value)
    }
    func selectLocalSubtitle(_ assetId: String?, context: PlaybackSubtitleContext) {
        guard context == subtitleContext else { return }
        onlinePresenter?.selectLocalSubtitle(assetId: assetId)
    }
    func selectQuality(_ choice: TvQualityChoice, identity: PlaybackActionIdentity? = nil) {
        guard accepts(identity) else { return }
        guard let presenter = onlinePresenter else { return }
        if choice.inheritsPlaybackDefault {
            presenter.handlePlaybackAction(action: PlaybackAction.clearqualityoverride)
        } else {
            presenter.selectQualityChoice(modeName: choice.mode.name, maxBitrateBps: choice.maxBitrateBps)
        }
    }
    func setPlaybackSpeed(_ speed: Float) { onlinePresenter?.setPlaybackSpeed(speed: speed); offlinePresenter?.setPlaybackSpeed(speed: speed) }
    func setSubtitleStyle(fontScale: Float, foreground: String?, background: String?, edgeStyleName: String) {
        onlinePresenter?.setSubtitleStyle(
            fontScale: fontScale,
            foregroundColor: foreground,
            backgroundColor: background,
            edgeStyleName: edgeStyleName
        )
    }
    func setDiagnosticsVisible(_ visible: Bool) { onlinePresenter?.setDiagnosticsVisible(visible: visible); offlinePresenter?.setDiagnosticsVisible(visible: visible) }
    func seekToChapter(_ index: Int) {
        if let offlinePresenter {
            offlinePresenter.seekToChapter(index: Int32(index))
        } else if chapters.indices.contains(index) {
            seek(to: chapters[index].startMs)
        }
    }
    func selectQueueItem(_ index: Int) { onlinePresenter?.selectQueueItem(index: Int32(index)) }
    func playPreviousEpisode() { onlinePresenter?.playPreviousEpisode() }
    func playNextEpisode(identity: PlaybackActionIdentity? = nil) {
        guard accepts(identity) else { return }
        onlinePresenter?.playNextEpisode()
    }
    func shuffleQueue() { onlinePresenter?.shuffleQueue() }
    func dismissNextUp() { onlinePresenter?.dismissNextUp() }
    func continueStillWatching() { onlinePresenter?.continueStillWatching() }
    func dismissStillWatching() { onlinePresenter?.dismissStillWatching() }
    func skipActiveSegment(identity: PlaybackActionIdentity? = nil) {
        guard accepts(identity) else { return }
        onlinePresenter?.skipActiveSegment()
    }
    func handlePlaybackAction(_ action: PlaybackAction) { onlinePresenter?.handlePlaybackAction(action: action) }
    func dismissSubtitleNotice() { onlinePresenter?.dismissSubtitleNotice() }
    func retry() { onlinePresenter?.handlePlaybackAction(action: PlaybackAction.retry); offlinePresenter?.retry() }
    func recordVideoOutputReady(_ generation: Int64) { onlinePresenter?.recordVideoOutputReady(generation: generation) }
    func recordNativePlaybackIntent(isPlaying: Bool, identity: PlaybackActionIdentity) {
        guard accepts(identity) else { return }
        onlinePresenter?.recordNativePlaybackIntent(
            playerIdentity: identity.playerIdentity,
            planEpoch: identity.planEpoch,
            itemId: identity.itemId,
            isPlaying: isPlaying
        )
    }

    private func accepts(_ identity: PlaybackActionIdentity?) -> Bool {
        identity == nil || identity == playbackActionIdentity
    }

    private func refreshNativeHost() {
        guard playerInstalled else {
            installedPlayerIdentity = nil
            avPlayer = nil
            vlcSurface = nil
            return
        }
        guard playerIdentity > 0 else { return }
        if let onlinePresenter {
            let player = onlinePresenter.platformPlayer as? AVPlayer
            guard installedPlayerIdentity != playerIdentity || avPlayer !== player else { return }
            installedPlayerIdentity = playerIdentity
            avPlayer = player
            vlcSurface = nil
        } else if let offlinePresenter {
            guard installedPlayerIdentity != playerIdentity else { return }
            installedPlayerIdentity = playerIdentity
            vlcSurface = TvosEntry.shared.offlinePlaybackSurface(presenter: offlinePresenter)
            avPlayer = nil
        }
    }

    deinit {
        if !closed {
            handle?.close()
            onlinePresenter?.close()
            offlinePresenter?.close()
        }
    }
}

enum PlayerVideoSizing: String, CaseIterable, Identifiable {
    case fit
    case fill
    case zoom

    var id: String { rawValue }
}

struct PlaybackActionIdentity: Equatable {
    let playerIdentity: Int64
    let planEpoch: Int64
    let itemId: String
}

struct PlaybackSubtitleContext: Equatable {
    let itemId: String
    let mediaSourceId: String
    let launchIdentity: PlaybackActionIdentity
}
