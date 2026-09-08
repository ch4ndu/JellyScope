// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlayerScreen: View {
    @StateObject private var model: PlaybackModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var panel: PlayerPanel?
    @State private var returnPanel: PlayerPanel?
    @State private var showsSubtitleSearch = false
    @State private var subtitleSearchContext: PlaybackSubtitleContext?
    @State private var returnsToControlsAfterSubtitleSearch = false
    @State private var controlsReturnFocus: PlayerControlsFocus?
    @State private var launcherReturn: PlayerLauncher = .controls
    @State private var panelCloseInFlight = false
    @State private var playbackInfoOpportunityConsumed = false
    @FocusState private var launcherFocus: PlayerLauncher?
    private let onOpenPlaybackSettings: () -> Void

    init(session: Session, route: PlaybackRoute, onOpenPlaybackSettings: @escaping () -> Void) {
        _model = StateObject(wrappedValue: PlaybackModel(session: session, route: route))
        self.onOpenPlaybackSettings = onOpenPlaybackSettings
    }

    init(session: Session, offlineRoute: OfflinePlaybackRoute, onOpenPlaybackSettings: @escaping () -> Void) {
        _model = StateObject(wrappedValue: PlaybackModel(session: session, offlineRoute: offlineRoute))
        self.onOpenPlaybackSettings = onOpenPlaybackSettings
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerHost(model: model, interactionsEnabled: !blocksPlayerInteraction)
                .ignoresSafeArea()
            contentOverlays
        }
        .overlay(alignment: .topTrailing) {
            if model.playerInstalled && model.phase != .failed && panel == nil && model.playbackActionNotice == nil && !model.stillWatchingVisible {
                launcher
                    .padding(40)
            }
        }
        .onAppear { model.start() }
        .onDisappear { model.close() }
        .onChange(of: model.phase) { _, phase in
            if phase == .completed && !model.keepsPlayerMounted { closePlayer() }
        }
        .onChange(of: playbackInfoReady, initial: true) { _, ready in
            consumePlaybackInfoOpportunity(when: ready)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { closePlayer() }
        }
        .onPlayPauseCommand {
            if model.playerInstalled && (model.vlcSurface != nil || blocksPlayerInteraction) {
                model.togglePlayPause()
            }
        }
        .onExitCommand(perform: handleBack)
        .sheet(isPresented: $showsSubtitleSearch) {
            if let context = subtitleSearchContext {
                SubtitlesView(
                    session: model.session,
                    itemId: context.itemId,
                    mediaSourceId: context.mediaSourceId,
                    onSelection: { assetId in
                        guard context == model.subtitleContext else {
                            invalidateSubtitleSearch()
                            return
                        }
                        model.selectLocalSubtitle(assetId, context: context)
                        subtitleSearchContext = nil
                        showsSubtitleSearch = false
                    }
                )
            }
        }
        .onChange(of: showsSubtitleSearch) { _, visible in
            if !visible && returnsToControlsAfterSubtitleSearch {
                returnsToControlsAfterSubtitleSearch = false
                controlsReturnFocus = .subtitleSearch
                panel = .controls
            }
            if !visible {
                subtitleSearchContext = nil
            }
        }
        .onChange(of: model.subtitleContext) { _, context in
            if showsSubtitleSearch && context != subtitleSearchContext {
                invalidateSubtitleSearch()
            }
        }
    }

    @ViewBuilder
    private var contentOverlays: some View {
        if let notice = model.playbackActionNotice {
            modalContainer {
                PlaybackActionSurface(
                    notice: notice,
                    availableActions: model.playbackActions,
                    qualityChoices: model.qualityChoices,
                    model: model,
                    onOpenPlaybackSettings: openPlaybackSettings,
                    onClose: closePlayer
                )
            }
        } else if model.phase == .failed {
            modalContainer { errorView }
        } else if model.stillWatchingVisible {
            modalContainer {
                PlayerStillWatchingView(
                    onContinue: model.continueStillWatching,
                    onStop: model.dismissStillWatching
                )
            }
        } else if let panel {
            modalContainer { panelView(panel) }
        } else if model.upNextVisible {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    PlayerNextUpView(
                        episode: model.nextEpisode,
                        countdownSeconds: model.countdownSeconds,
                        onPlayNow: { model.playNextEpisode() },
                        onDismiss: model.dismissNextUp
                    )
                }
            }
            .padding(48)
        } else {
            passiveOverlays
        }

        if model.phase == .loading {
            ProgressView()
                .controlSize(.large)
        }
    }

    @ViewBuilder
    private var passiveOverlays: some View {
        VStack {
            Spacer()
            if model.vlcSurface != nil {
                PlayerTransportView(model: model)
                    .padding(.horizontal, 56)
                    .padding(.bottom, 42)
            } else if let guidance = model.playbackGuidance {
                PlaybackHealthAdvisory(
                    guidance: guidance,
                    model: model,
                    onOpenPlaybackSettings: openPlaybackSettings
                )
                .padding(48)
            } else if model.subtitleNotice != nil {
                HStack {
                    Text("The selected local subtitle is unavailable. Subtitles were turned off.")
                    Button("Dismiss", action: model.dismissSubtitleNotice)
                }
                .padding(18)
                .background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 12))
                .padding(48)
            }
        }
    }

    private var launcher: some View {
        HStack(spacing: 14) {
            if !model.isOffline && model.queue.hasNext {
                Button {
                    model.playNextEpisode()
                } label: {
                    Label("Next", systemImage: "forward.end.fill")
                }
                .focused($launcherFocus, equals: .next)
            }
            if !model.isOffline && !model.queue.items.isEmpty {
                Button {
                    launcherReturn = .queue
                    panel = .queue
                } label: {
                    Label("Play Queue", systemImage: "list.bullet")
                }
                .focused($launcherFocus, equals: .queue)
            }
            Button {
                launcherReturn = .controls
                controlsReturnFocus = nil
                panel = .controls
            } label: {
                Label("Controls", systemImage: "slider.horizontal.3")
            }
            .focused($launcherFocus, equals: .controls)
        }
    }

    @ViewBuilder
    private func panelView(_ panel: PlayerPanel) -> some View {
        switch panel {
        case .queue:
            PlayerQueueView(model: model, onClose: closePanel)
        case .controls:
            PlayerControlsView(
                model: model,
                videoSizing: $model.videoSizing,
                onOpenQueue: { openNestedPanel(.queue, returnFocus: .queue) },
                onOpenSubtitleSearch: openSubtitleSearch,
                onOpenSubtitleStyle: { openNestedPanel(.subtitleStyle, returnFocus: .subtitleStyle) },
                onOpenDiagnostics: { openNestedPanel(.diagnostics, returnFocus: .diagnostics) },
                onClose: closePanel,
                initialFocus: controlsReturnFocus
            )
        case .subtitleStyle:
            PlayerSubtitleStyleView(model: model, onClose: closePanel)
        case .diagnostics:
            PlayerDiagnosticsView(model: model, onClose: closePanel)
        }
    }

    private func modalContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ZStack {
            Color.black.opacity(0.58).ignoresSafeArea()
            content()
        }
    }

    private var errorView: some View {
        VStack(spacing: 20) {
            Text("Playback failed")
                .font(.title2)
            Text(model.error.playbackMessage)
                .foregroundStyle(.secondary)
            HStack {
                Button("Retry", action: model.retry)
                Button("Close", action: closePlayer)
            }
        }
        .padding(60)
        .background(.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 20))
    }

    private func closePanel() {
        guard !panelCloseInFlight else { return }
        panelCloseInFlight = true
        DispatchQueue.main.async { panelCloseInFlight = false }
        if let returnPanel {
            panel = returnPanel
            self.returnPanel = nil
        } else {
            panel = nil
            controlsReturnFocus = nil
            launcherFocus = launcherReturn
        }
    }

    private func openNestedPanel(_ panel: PlayerPanel, returnFocus: PlayerControlsFocus) {
        controlsReturnFocus = returnFocus
        returnPanel = self.panel
        self.panel = panel
    }

    private var blocksPlayerInteraction: Bool {
        model.playbackActionNotice != nil ||
            model.phase == .failed ||
            model.stillWatchingVisible ||
            panel != nil ||
            (model.upNextVisible && model.status == .completed) ||
            showsSubtitleSearch
    }

    private var playbackInfoReady: Bool {
        model.playerInstalled && model.phase == .active
    }

    private func consumePlaybackInfoOpportunity(when ready: Bool) {
        guard ready, !playbackInfoOpportunityConsumed else { return }
        playbackInfoOpportunityConsumed = true
        guard model.startWithPlaybackInfoOverlay,
              panel == nil,
              returnPanel == nil,
              model.playbackActionNotice == nil,
              model.phase != .failed,
              !model.stillWatchingVisible,
              !showsSubtitleSearch,
              !blocksPlayerInteraction else { return }
        returnPanel = nil
        launcherReturn = .controls
        controlsReturnFocus = nil
        panel = .diagnostics
    }

    private func openSubtitleSearch() {
        guard let context = model.subtitleContext else { return }
        subtitleSearchContext = context
        panel = nil
        returnsToControlsAfterSubtitleSearch = true
        showsSubtitleSearch = true
    }

    private func invalidateSubtitleSearch() {
        returnsToControlsAfterSubtitleSearch = false
        subtitleSearchContext = nil
        showsSubtitleSearch = false
    }

    private func handleBack() {
        if showsSubtitleSearch {
            return
        }
        if model.playbackActionNotice != nil {
            if model.playbackActions.contains(where: { $0 === PlaybackAction.dismiss }) {
                model.handlePlaybackAction(PlaybackAction.dismiss)
            } else {
                closePlayer()
            }
            return
        }
        if model.phase == .failed {
            closePlayer()
            return
        }
        if model.stillWatchingVisible {
            model.dismissStillWatching()
            return
        }
        if panel != nil {
            closePanel()
            return
        }
        if model.upNextVisible {
            model.dismissNextUp()
            return
        }
        closePlayer()
    }

    private func closePlayer() {
        model.close()
        dismiss()
    }

    private func openPlaybackSettings() {
        model.close()
        dismiss()
        DispatchQueue.main.async(execute: onOpenPlaybackSettings)
    }
}

private enum PlayerPanel {
    case queue
    case controls
    case subtitleStyle
    case diagnostics
}

private enum PlayerLauncher: Hashable {
    case next
    case queue
    case controls
}
