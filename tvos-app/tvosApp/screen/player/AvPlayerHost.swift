// SPDX-License-Identifier: MPL-2.0

import AVKit
import SharedTv
import SwiftUI

struct AvPlayerHost: UIViewControllerRepresentable {
    let state: TvPlaybackUiState
    let player: AVPlayer
    let identity: Int64
    let interactionsEnabled: Bool
    let model: PlaybackModel

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        context.coordinator.resetForPlayer(identity: identity, controller: controller)
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player || context.coordinator.boundPlayerIdentity != identity {
            controller.player = player
            context.coordinator.resetForPlayer(identity: identity, controller: controller)
        }
        controller.view.isUserInteractionEnabled = interactionsEnabled
        controller.view.accessibilityElementsHidden = !interactionsEnabled
        controller.requiresLinearPlayback = state.isTranscode
        controller.videoGravity = model.videoSizing.avVideoGravity
        context.coordinator.apply(state: state, to: controller, model: model)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.invalidate()
        controller.player = nil
    }

    @MainActor
    final class Coordinator {
        private(set) var boundPlayerIdentity: Int64?
        private var appliedMenuKey = ""
        private var appliedMetadataEpoch: Int64 = -1
        private weak var observedVideoLayer: AVPlayerLayer?
        private var readyForDisplayObservation: NSKeyValueObservation?
        private var observedOutputIdentity: VideoOutputHostIdentity?
        private var reportedOutputIdentity: VideoOutputHostIdentity?
        private weak var outputModel: PlaybackModel?
        private weak var observedRatePlayer: AVPlayer?
        private var observedPlaybackIdentity: PlaybackActionIdentity?
        private var observedHostTransportEpoch: Int64?
        private var rateObservation: NSObjectProtocol?
        private let metadata = AvPlayerMetadata()

        func resetForPlayer(identity: Int64, controller: AVPlayerViewController) {
            invalidate()
            boundPlayerIdentity = identity
            appliedMenuKey = ""
            appliedMetadataEpoch = -1
            controller.transportBarCustomMenuItems = []
            controller.contextualActions = []
        }

        func apply(state: TvPlaybackUiState, to controller: AVPlayerViewController, model: PlaybackModel) {
            bridgeVideoOutput(state: state, controller: controller, model: model)
            bridgePlaybackIntent(controller: controller, model: model)
            let menuKey = AvPlayerMenus.configurationKey(for: state)
            if menuKey != appliedMenuKey {
                appliedMenuKey = menuKey
                controller.transportBarCustomMenuItems = AvPlayerMenus.transportMenus(for: state, model: model)
                controller.contextualActions = AvPlayerMenus.contextualActions(for: state, model: model)
            }
            if state.planEpoch != appliedMetadataEpoch, let item = controller.player?.currentItem {
                appliedMetadataEpoch = state.planEpoch
                metadata.apply(state: state, to: item)
            }
        }

        func invalidate() {
            if let observedOutputIdentity {
                outputModel?.recordVideoOutputAvailability(observedOutputIdentity, available: false)
            }
            readyForDisplayObservation?.invalidate()
            readyForDisplayObservation = nil
            observedVideoLayer = nil
            observedOutputIdentity = nil
            reportedOutputIdentity = nil
            outputModel = nil
            if let rateObservation {
                NotificationCenter.default.removeObserver(rateObservation)
            }
            rateObservation = nil
            observedRatePlayer = nil
            observedPlaybackIdentity = nil
        }

        private func bridgePlaybackIntent(controller: AVPlayerViewController, model: PlaybackModel) {
            guard let player = controller.player, let identity = model.playbackActionIdentity else {
                if let rateObservation {
                    NotificationCenter.default.removeObserver(rateObservation)
                }
                rateObservation = nil
                observedRatePlayer = nil
                observedPlaybackIdentity = nil
                return
            }
            let transportEpoch = model.hostTransportEpoch
            guard observedRatePlayer !== player || observedPlaybackIdentity != identity ||
                observedHostTransportEpoch != transportEpoch else { return }
            if let rateObservation {
                NotificationCenter.default.removeObserver(rateObservation)
            }
            observedRatePlayer = player
            observedPlaybackIdentity = identity
            observedHostTransportEpoch = transportEpoch
            rateObservation = NotificationCenter.default.addObserver(
                forName: AVPlayer.rateDidChangeNotification,
                object: player,
                queue: .main
            ) { [weak player, weak model] notification in
                guard notification.userInfo?[AVPlayer.rateDidChangeReasonKey] as? AVPlayer.RateDidChangeReason == .setRateCalled else {
                    return
                }
                MainActor.assumeIsolated {
                    guard let player else { return }
                    model?.recordNativePlaybackIntent(isPlaying: player.rate != 0, identity: identity, hostTransportEpoch: transportEpoch)
                }
            }
        }

        private func bridgeVideoOutput(state: TvPlaybackUiState, controller: AVPlayerViewController, model: PlaybackModel) {
            let identity = VideoOutputHostIdentity(
                playback: PlaybackActionIdentity(playerIdentity: state.playerIdentity, planEpoch: state.planEpoch, itemId: state.itemId),
                prepareEpoch: state.prepareEpoch
            )
            guard identity.prepareEpoch > 0, let player = controller.player, let item = player.currentItem,
                  boundPlayerIdentity == identity.playback.playerIdentity,
                  model.acceptsOutputHost(identity, player: player, item: item) else { return }
            if observedOutputIdentity != identity {
                readyForDisplayObservation?.invalidate()
                readyForDisplayObservation = nil
                observedVideoLayer = nil
                observedOutputIdentity = identity
                reportedOutputIdentity = nil
                outputModel = model
            }
            guard
                  let rootLayer = controller.viewIfLoaded?.layer,
                  let playerLayer = findPlayerLayer(in: rootLayer, player: player) else {
                readyForDisplayObservation?.invalidate()
                readyForDisplayObservation = nil
                observedVideoLayer = nil
                reportedOutputIdentity = nil
                model.recordVideoOutputAvailability(identity, available: false)
                return
            }
            if observedVideoLayer !== playerLayer {
                readyForDisplayObservation?.invalidate()
                readyForDisplayObservation = nil
                observedVideoLayer = playerLayer
                reportedOutputIdentity = nil
            }
            if readyForDisplayObservation == nil {
                readyForDisplayObservation = playerLayer.observe(\AVPlayerLayer.isReadyForDisplay, options: [.initial, .new]) { [weak self, weak model, weak player, weak item] layer, _ in
                    Task { @MainActor in
                        guard let player, let item, let model,
                              model.acceptsOutputHost(identity, player: player, item: item) else { return }
                        self?.reportReady(layer: layer, identity: identity, player: player, item: item, model: model)
                    }
                }
            }
            model.recordVideoOutputAvailability(identity, available: true)
            reportReady(layer: playerLayer, identity: identity, player: player, item: item, model: model)
        }

        private func reportReady(layer: AVPlayerLayer, identity: VideoOutputHostIdentity, player: AVPlayer, item: AVPlayerItem, model: PlaybackModel) {
            // Validate the captured native and shell ownership before touching current observation state.
            guard model.acceptsOutputHost(identity, player: player, item: item),
                  boundPlayerIdentity == identity.playback.playerIdentity, observedOutputIdentity == identity,
                  observedVideoLayer === layer, layer.player === player,
                  layer.isReadyForDisplay, reportedOutputIdentity != identity else { return }
            reportedOutputIdentity = identity
            model.recordVideoOutputReady(identity)
        }

        private func findPlayerLayer(in layer: CALayer, player: AVPlayer) -> AVPlayerLayer? {
            if let playerLayer = layer as? AVPlayerLayer, playerLayer.player === player { return playerLayer }
            for sublayer in layer.sublayers ?? [] {
                if let playerLayer = findPlayerLayer(in: sublayer, player: player) { return playerLayer }
            }
            return nil
        }
    }
}

private extension PlayerVideoSizing {
    var avVideoGravity: AVLayerVideoGravity {
        switch self {
        case .fit: .resizeAspect
        case .fill: .resize
        case .zoom: .resizeAspectFill
        }
    }
}
