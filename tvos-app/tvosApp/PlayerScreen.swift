// SPDX-License-Identifier: MPL-2.0

import AVKit
import Foundation
import SwiftUI
import SharedTv

/// System-player playback screen. The Kotlin playback presenter owns the
/// AVPlayer (plan, prepare, tracks, segments, queue, recovery, reporting);
/// this screen hosts it in an AVPlayerViewController and projects presenter
/// state onto the AVKit integration points: transport-bar menus (audio/
/// subtitles/quality), contextual actions (segment skip, next episode),
/// chapter navigation markers, and info-panel metadata. Item-scoped
/// decorations re-apply whenever `planEpoch` changes (replan/queue advance
/// replaces the AVPlayerItem).
struct PlayerScreen: View {
    @StateObject private var model: PlaybackModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    private let onOpenPlaybackSettings: () -> Void

    init(session: Session, route: PlaybackRoute, onOpenPlaybackSettings: @escaping () -> Void) {
        _model = StateObject(wrappedValue: PlaybackModel(session: session, route: route))
        self.onOpenPlaybackSettings = onOpenPlaybackSettings
    }

    var body: some View {
        Group {
            if let player = model.player {
                PlayerHost(state: model.state, player: player, actions: model)
                    .ignoresSafeArea()
                    .overlay(alignment: .bottom) {
                        if let notice = model.state.playbackActionNotice {
                            PlaybackActionSurface(
                                notice: notice,
                                availableActions: model.state.playbackActions,
                                qualityChoices: model.state.qualityChoices,
                                actions: model,
                                onOpenPlaybackSettings: openPlaybackSettings,
                                onClose: closePlayer
                            )
                                .padding(.horizontal, 48)
                                .padding(.bottom, 54)
                        } else if let guidance = model.state.playbackGuidance {
                            PlaybackHealthAdvisory(
                                guidance: guidance,
                                actions: model,
                                onOpenPlaybackSettings: openPlaybackSettings
                            )
                                .padding(.horizontal, 48)
                                .padding(.bottom, 54)
                        }
                    }
            } else {
                errorView(message: String(localized: "The player could not be created."))
            }
        }
        .overlay {
            if model.state.phase == .loading {
                ProgressView()
            } else if model.state.phase == .failed && model.state.playbackActionNotice == nil {
                errorView(message: model.state.error.playbackMessage)
            }
        }
        .onAppear { model.start() }
        .onDisappear { model.close() }
        .onChange(of: model.state.phase) { _, phase in
            // Completed is terminal-only by presenter contract: mid-queue
            // auto-advance never publishes it, so dismissing here is safe.
            if phase == .completed {
                dismiss()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // Backgrounding keeps the view mounted, so onDisappear never fires;
            // close here so the final Stop report and player release happen.
            if phase == .background {
                model.close()
                dismiss()
            }
        }
    }

    private func closePlayer() {
        model.close()
        dismiss()
    }

    private func openPlaybackSettings() {
        model.close()
        dismiss()
        DispatchQueue.main.async {
            onOpenPlaybackSettings()
        }
    }

    private func errorView(message: String) -> some View {
        VStack(spacing: 20) {
            Text("Playback failed")
                .font(.title2)
            Text(message)
                .foregroundStyle(.secondary)
            Button("Close") { dismiss() }
        }
        .padding(60)
        .background(.black.opacity(0.8))
    }
}

/// A passive native-tvOS projection of the shared semantic guidance. It has no
/// action, focus target, or hit-testing surface, so AVKit retains its normal
/// transport, remote, menu, and focus behavior.
private struct PlaybackHealthAdvisory: View {
    let guidance: PlaybackHealthGuidance
    let actions: PlaybackModel
    let onOpenPlaybackSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(message)
                .font(.body)
                .foregroundStyle(.white)
                .multilineTextAlignment(.leading)
            HStack {
                if guidance.canOpenPlaybackSettings {
                    Button("Playback settings") { onOpenPlaybackSettings() }
                }
                if guidance.canDismiss {
                    Button("Dismiss") { actions.handlePlaybackAction(action: PlaybackAction.dismiss) }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }

    private var message: String {
        let reason: String
        switch guidance.reason.name {
        case "SlowStartup":
            reason = String(localized: "Playback is taking longer than expected to start.")
        case "LongBuffering":
            reason = String(localized: "Playback has been buffering for longer than expected.")
        case "CumulativeBuffering":
            reason = String(localized: "Playback has spent a lot of time buffering.")
        case "RepeatedStalls":
            reason = String(localized: "Playback is buffering repeatedly.")
        case "DroppedFrames":
            reason = String(localized: "Playback is dropping frames.")
        case "NoVideoOutput":
            reason = String(localized: "Video playback has not produced a displayed picture.")
        default:
            reason = String(localized: "Playback recovered after a problem.")
        }
        if guidance.reason.name == "SlowStartup" {
            return reason
        }
        if guidance.streamMode?.name == "DirectPlay" {
            return String(
                format: String(localized: "%@ This may be a device or connection capacity issue. Consider changing Maximum Quality in Settings after leaving playback."),
                reason
            )
        }
        return String(
            format: String(localized: "%@ This may be streaming pressure. Consider changing Maximum Quality in Settings after leaving playback."),
            reason
        )
    }
}

private struct PlaybackActionSurface: View {
    let notice: PlaybackActionNotice
    let availableActions: [PlaybackAction]
    let qualityChoices: [TvQualityChoice]
    let actions: PlaybackModel
    let onOpenPlaybackSettings: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(message)
                .font(.body)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                ForEach(availableActions, id: \.name) { action in
                    playbackActionButton(action)
                }
            }
        }
        .padding(20)
        .background(.black.opacity(0.86), in: RoundedRectangle(cornerRadius: 12))
        .focusable(true)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func playbackActionButton(_ action: PlaybackAction) -> some View {
        if action === PlaybackAction.acceptauto || action === PlaybackAction.clearqualityoverride {
            Button("Use Auto") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.keepcurrentquality {
            Button("Keep this quality") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.chooselowerquality {
            Menu("Choose lower quality") {
                ForEach(qualityChoices.filter { $0.mode.name == "Fixed" }, id: \.recoveryChoiceIdentity) { choice in
                    Button(choice.recoveryChoiceTitle) { actions.selectQuality(choice: choice) }
                }
            }
        } else if action === PlaybackAction.tryhigherquality {
            Button("Try higher") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.tryoriginal {
            Button("Try Original") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.retry {
            Button("Retry") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.openplaybacksettings {
            Button("Playback settings") { onOpenPlaybackSettings() }
        } else if action === PlaybackAction.dismiss {
            Button("Dismiss") { actions.handlePlaybackAction(action: action) }
        } else if action === PlaybackAction.close {
            Button("Close") { onClose() }
        } else {
            EmptyView()
        }
    }

    private var message: String {
        switch notice.reason.name {
        case "OriginalPlaybackFailed": return String(localized: "Original playback could not start.")
        case "FixedQualityFailed": return String(localized: "The selected quality could not play.")
        case "QualityRecoveryApplied": return String(localized: "Playback recovered at a lower quality for this session.")
        default: return String(localized: "Playback needs your choice before trying another stream.")
        }
    }
}

private extension TvQualityChoice {
    var recoveryChoiceIdentity: String {
        "\(mode.name):\(maxBitrateBps?.int64Value ?? -1)"
    }

    var recoveryChoiceTitle: String {
        localizedPlayerLabel
    }
}

@MainActor
final class PlaybackModel: ObservableObject {
    @Published private(set) var state: TvPlaybackUiState
    let player: AVPlayer?

    private let presenter: TvPlaybackSessionPresenter
    private var handle: WatchHandle?
    private var closed = false

    init(session: Session, route: PlaybackRoute) {
        let presenter = TvosEntry.shared.playbackPresenter(
            session: session,
            itemId: route.itemId,
            mediaSourceId: route.mediaSourceId,
            startPositionTicks: route.startPositionTicks
        )
        self.presenter = presenter
        // Checked cast, never a trap: a nil player renders the error state.
        self.player = presenter.platformPlayer as? AVPlayer
        self.state = presenter.state.value as! TvPlaybackUiState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func start() {
        presenter.start()
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        presenter.close()
    }

    func selectAudio(streamIndex: Int32) {
        presenter.selectAudio(streamIndex: streamIndex)
    }

    func selectSubtitle(streamIndex: Int32?) {
        presenter.selectSubtitle(streamIndex: streamIndex.map { KotlinInt(int: $0) })
    }

    func selectQuality(maxBitrateBps: Int64?) {
        presenter.selectQuality(maxBitrateBps: maxBitrateBps.map { KotlinLong(longLong: $0) })
    }

    func selectQuality(choice: TvQualityChoice) {
        if choice.inheritsPlaybackDefault {
            presenter.handlePlaybackAction(action: PlaybackAction.clearqualityoverride)
            return
        }
        presenter.selectQualityChoice(
            modeName: choice.mode.name,
            maxBitrateBps: choice.maxBitrateBps,
        )
    }

    func handlePlaybackAction(action: PlaybackAction) {
        presenter.handlePlaybackAction(action: action)
    }

    func recordVideoOutputReady(generation: Int64) {
        presenter.recordVideoOutputReady(generation: generation)
    }

    func skipActiveSegment() {
        presenter.skipActiveSegment()
    }

    func playNextEpisode() {
        presenter.playNextEpisode()
    }

    deinit {
        if !closed {
            handle?.close()
            presenter.close()
        }
    }
}

/// AVPlayerViewController host. The coordinator caches a menu-configuration
/// key (planEpoch + track lists + confirmed selections + bitrate + segment/
/// up-next windows) so menus and contextual actions rebuild only when that
/// key changes — never on position ticks.
private struct PlayerHost: UIViewControllerRepresentable {
    let state: TvPlaybackUiState
    let player: AVPlayer
    let actions: PlaybackModel

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.requiresLinearPlayback = state.isTranscode
        context.coordinator.apply(state: state, to: controller, actions: actions)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    final class Coordinator {
        private var appliedMenuKey = ""
        private var appliedMarkersEpoch: Int64 = -1
        private var artworkTask: Task<Void, Never>?
        private weak var observedVideoLayer: AVPlayerLayer?
        private var readyForDisplayObservation: NSKeyValueObservation?
        private var observedVideoOutputEpoch: Int64?

        func apply(state: TvPlaybackUiState, to controller: AVPlayerViewController, actions: PlaybackModel) {
            bridgeVideoOutput(state: state, controller: controller, actions: actions)
            let menuKey = Self.menuConfigurationKey(for: state)
            if menuKey != appliedMenuKey {
                appliedMenuKey = menuKey
                controller.transportBarCustomMenuItems = Self.transportMenus(for: state, actions: actions)
                controller.contextualActions = Self.contextualActions(for: state, actions: actions)
            }
            // Item decorations attach to the AVPlayerItem, which every prepare
            // replaces — re-apply per plan epoch once the new item exists.
            if state.planEpoch != appliedMarkersEpoch, let item = controller.player?.currentItem {
                appliedMarkersEpoch = state.planEpoch
                item.navigationMarkerGroups = Self.chapterMarkers(for: state)
                item.externalMetadata = Self.titleMetadata(for: state)
                loadArtwork(for: state, into: item)
            }
        }

        /// `isReadyForDisplay` can change after the one SwiftUI update that
        /// installs a new AVPlayerItem. Observe that positive layer fact rather
        /// than inferring output from AVPlayer's Playing state, and retain the
        /// presenter epoch in the closure. Kotlin rejects it if a newer prepare
        /// supersedes this layer before the callback arrives.
        private func bridgeVideoOutput(state: TvPlaybackUiState, controller: AVPlayerViewController, actions: PlaybackModel) {
            let prepareEpoch = state.prepareEpoch
            guard prepareEpoch > 0,
                  let rootLayer = controller.viewIfLoaded?.layer,
                  let playerLayer = Self.findPlayerLayer(in: rootLayer) else {
                return
            }
            let bindingChanged = observedVideoLayer !== playerLayer || observedVideoOutputEpoch != prepareEpoch
            if bindingChanged {
                readyForDisplayObservation?.invalidate()
                readyForDisplayObservation = nil
                observedVideoLayer = playerLayer
                observedVideoOutputEpoch = nil
            }
            reportVideoOutputIfReady(layer: playerLayer, generation: prepareEpoch, actions: actions)
            guard observedVideoOutputEpoch != prepareEpoch, readyForDisplayObservation == nil else { return }
            readyForDisplayObservation = playerLayer.observe(\AVPlayerLayer.isReadyForDisplay, options: [.initial, .new]) { [weak self, weak actions] layer, _ in
                guard layer.isReadyForDisplay else { return }
                Task { @MainActor in
                    self?.reportVideoOutputIfReady(layer: layer, generation: prepareEpoch, actions: actions)
                }
            }
        }

        private func reportVideoOutputIfReady(layer: AVPlayerLayer, generation: Int64, actions: PlaybackModel?) {
            guard layer.isReadyForDisplay, observedVideoOutputEpoch != generation else { return }
            observedVideoOutputEpoch = generation
            readyForDisplayObservation?.invalidate()
            readyForDisplayObservation = nil
            actions?.recordVideoOutputReady(generation: generation)
        }

        private static func findPlayerLayer(in layer: CALayer) -> AVPlayerLayer? {
            if let playerLayer = layer as? AVPlayerLayer {
                return playerLayer
            }
            for sublayer in layer.sublayers ?? [] {
                if let playerLayer = findPlayerLayer(in: sublayer) {
                    return playerLayer
                }
            }
            return nil
        }

        private static func menuConfigurationKey(for state: TvPlaybackUiState) -> String {
            let audio = state.audioTracks.map { "\($0.streamIndex):\($0.selected)" }.joined(separator: ",")
            let subs = state.subtitleTracks.map { "\($0.streamIndex):\($0.selected)" }.joined(separator: ",")
            let quality = state.qualityChoices.map { "\($0.maxBitrateBps?.int64Value ?? -1):\($0.selected)" }.joined(separator: ",")
            let segment = state.activeSegment.map { "\($0.type.name):\($0.endMs)" } ?? "-"
            return "\(state.planEpoch)|\(audio)|\(subs)|\(quality)|\(segment)|\(state.upNextVisible)"
        }

        private static func transportMenus(for state: TvPlaybackUiState, actions: PlaybackModel) -> [UIMenuElement] {
            var menus: [UIMenuElement] = []
            if state.audioTracks.count > 1 {
                let children = state.audioTracks.map { track in
                    UIAction(title: track.localizedDisplayName, state: track.selected ? .on : .off) { [weak actions] _ in
                        actions?.selectAudio(streamIndex: track.streamIndex)
                    }
                }
                menus.append(
                    UIMenu(title: String(localized: "Audio"), image: UIImage(systemName: "waveform"), options: .singleSelection, children: children)
                )
            }
            if !state.subtitleTracks.isEmpty {
                let anySelected = state.subtitleTracks.contains { $0.selected }
                var children: [UIMenuElement] = [
                    UIAction(title: String(localized: "Off"), state: anySelected ? .off : .on) { [weak actions] _ in
                        actions?.selectSubtitle(streamIndex: nil)
                    },
                ]
                children += state.subtitleTracks.map { track in
                    UIAction(title: track.localizedDisplayName, state: track.selected ? .on : .off) { [weak actions] _ in
                        actions?.selectSubtitle(streamIndex: track.streamIndex)
                    }
                }
                menus.append(
                    UIMenu(title: String(localized: "Subtitles"), image: UIImage(systemName: "captions.bubble"), options: .singleSelection, children: children)
                )
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
                    ) { [weak actions] _ in
                        actions?.selectQuality(choice: choice)
                    }
                }
                menus.append(
                    UIMenu(title: String(localized: "Quality"), image: UIImage(systemName: "dial.high"), options: .singleSelection, children: children)
                )
            }
            return menus
        }

        private static func contextualActions(for state: TvPlaybackUiState, actions: PlaybackModel) -> [UIAction] {
            var contextual: [UIAction] = []
            if let segment = state.activeSegment, segment.askUser {
                contextual.append(
                    UIAction(title: segment.type.skipTitle) { [weak actions] _ in
                        actions?.skipActiveSegment()
                    }
                )
            }
            if state.upNextVisible {
                contextual.append(
                    UIAction(title: String(localized: "Next Episode")) { [weak actions] _ in
                        actions?.playNextEpisode()
                    }
                )
            }
            return contextual
        }

        private static func chapterMarkers(for state: TvPlaybackUiState) -> [AVNavigationMarkersGroup] {
            let chapters = state.chapters
            guard !chapters.isEmpty else { return [] }
            let duration = state.durationMs?.int64Value ?? Int64.max
            let groups = chapters.enumerated().map { index, chapter -> AVTimedMetadataGroup in
                let startMs = chapter.startMs
                let endMs = index + 1 < chapters.count ? chapters[index + 1].startMs : duration
                let titleItem = AVMutableMetadataItem()
                titleItem.identifier = .commonIdentifierTitle
                let title =
                    chapter.name.isEmpty
                        ? String(
                            format: String(localized: "Chapter %lld", comment: "Fallback title for an unnamed chapter"),
                            Int64(index + 1)
                        )
                        : chapter.name
                titleItem.value = title as NSString
                titleItem.extendedLanguageTag = "und"
                let range = CMTimeRange(
                    start: CMTime(value: startMs, timescale: 1000),
                    end: CMTime(value: max(endMs, startMs), timescale: 1000)
                )
                return AVTimedMetadataGroup(items: [titleItem], timeRange: range)
            }
            return [AVNavigationMarkersGroup(title: nil, timedNavigationMarkers: groups)]
        }

        private static func titleMetadata(for state: TvPlaybackUiState) -> [AVMetadataItem] {
            var items: [AVMetadataItem] = []
            if let title = state.title {
                items.append(metadataItem(.commonIdentifierTitle, value: title))
            }
            if let seriesName = state.seriesName, let episodeLabel = state.episodeLabel {
                items.append(
                    metadataItem(
                        .iTunesMetadataTrackSubTitle,
                        value: String(format: String(localized: "%@ • %@"), seriesName, episodeLabel)
                    )
                )
            } else if let subtitle = state.seriesName ?? state.episodeLabel {
                items.append(metadataItem(.iTunesMetadataTrackSubTitle, value: subtitle))
            }
            return items
        }

        private static func metadataItem(_ identifier: AVMetadataIdentifier, value: String) -> AVMetadataItem {
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
                      let self, let item, !Task.isCancelled,
                      self.appliedMarkersEpoch == epoch
                else { return }
                let artwork = AVMutableMetadataItem()
                artwork.identifier = .commonIdentifierArtwork
                artwork.value = data as NSData
                artwork.dataType = kCMMetadataBaseDataType_JPEG as String
                artwork.extendedLanguageTag = "und"
                item.externalMetadata = item.externalMetadata + [artwork]
            }
        }
    }
}

extension MediaSegmentType {
    var skipTitle: String {
        switch self {
        case .intro: return String(localized: "Skip Intro")
        case .outro: return String(localized: "Skip Outro")
        case .recap: return String(localized: "Skip Recap")
        case .preview: return String(localized: "Skip Preview")
        case .commercial: return String(localized: "Skip Ad")
        default: return String(localized: "Skip")
        }
    }
}

extension Optional where Wrapped == PlaybackError {
    var playbackMessage: String {
        switch self {
        case .some(let error):
            if error is PlaybackErrorNetwork {
                return String(localized: "A network error interrupted playback.")
            }
            if error is PlaybackErrorOfflineArtifactUnavailable {
                return String(localized: "This download is missing, incomplete, or no longer available.")
            }
            if error is PlaybackErrorOfflinePlayerUnavailable {
                return String(localized: "The player required for this download is unavailable on this Apple TV.")
            }
            if error is PlaybackErrorUnsupportedMedia {
                return String(localized: "This title cannot be played on Apple TV.")
            }
            if error is PlaybackErrorDecoder {
                return String(localized: "The video could not be decoded.")
            }
            if error is PlaybackErrorAudioOutput {
                return String(localized: "Audio output failed.")
            }
            if error is PlaybackErrorDrm {
                return String(localized: "This title is protected and cannot be played.")
            }
            return String(localized: "An unknown playback error occurred.")
        case .none:
            return String(localized: "An unknown playback error occurred.")
        }
    }
}
