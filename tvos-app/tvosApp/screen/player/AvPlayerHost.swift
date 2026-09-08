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
        private var observedPrepareEpoch: Int64?
        private var observedVideoOutputEpoch: Int64?
        private weak var observedRatePlayer: AVPlayer?
        private var observedPlaybackIdentity: PlaybackActionIdentity?
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
            readyForDisplayObservation?.invalidate()
            readyForDisplayObservation = nil
            observedVideoLayer = nil
            observedPrepareEpoch = nil
            observedVideoOutputEpoch = nil
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
            guard observedRatePlayer !== player || observedPlaybackIdentity != identity else { return }
            if let rateObservation {
                NotificationCenter.default.removeObserver(rateObservation)
            }
            observedRatePlayer = player
            observedPlaybackIdentity = identity
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
                    model?.recordNativePlaybackIntent(isPlaying: player.rate != 0, identity: identity)
                }
            }
        }

        private func bridgeVideoOutput(state: TvPlaybackUiState, controller: AVPlayerViewController, model: PlaybackModel) {
            let prepareEpoch = state.prepareEpoch
            guard prepareEpoch > 0,
                  let rootLayer = controller.viewIfLoaded?.layer,
                  let playerLayer = findPlayerLayer(in: rootLayer) else { return }
            if observedVideoLayer !== playerLayer || observedPrepareEpoch != prepareEpoch {
                readyForDisplayObservation?.invalidate()
                readyForDisplayObservation = nil
                observedVideoLayer = playerLayer
                observedPrepareEpoch = prepareEpoch
                observedVideoOutputEpoch = nil
            }
            reportReady(layer: playerLayer, generation: prepareEpoch, model: model)
            guard observedVideoOutputEpoch != prepareEpoch, readyForDisplayObservation == nil else { return }
            readyForDisplayObservation = playerLayer.observe(\AVPlayerLayer.isReadyForDisplay, options: [.initial, .new]) { [weak self, weak model] layer, _ in
                guard layer.isReadyForDisplay else { return }
                Task { @MainActor in self?.reportReady(layer: layer, generation: prepareEpoch, model: model) }
            }
        }

        private func reportReady(layer: AVPlayerLayer, generation: Int64, model: PlaybackModel?) {
            guard layer.isReadyForDisplay, observedVideoOutputEpoch != generation else { return }
            observedVideoOutputEpoch = generation
            readyForDisplayObservation?.invalidate()
            readyForDisplayObservation = nil
            model?.recordVideoOutputReady(generation)
        }

        private func findPlayerLayer(in layer: CALayer) -> AVPlayerLayer? {
            if let playerLayer = layer as? AVPlayerLayer { return playerLayer }
            for sublayer in layer.sublayers ?? [] {
                if let playerLayer = findPlayerLayer(in: sublayer) { return playerLayer }
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
