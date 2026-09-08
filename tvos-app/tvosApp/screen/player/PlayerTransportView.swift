// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import UIKit

struct PlayerTransportView: View {
    @ObservedObject var model: PlaybackModel
    @State private var pendingPositionMs: Int64?

    private var displayedPositionMs: Int64 {
        pendingPositionMs ?? model.positionMs
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 18) {
                Text(playerTimeLabel(displayedPositionMs))
                    .monospacedDigit()
                PlayerTimelineControl(
                    positionMs: displayedPositionMs,
                    durationMs: model.durationMs ?? 0,
                    isScrubbing: pendingPositionMs != nil,
                    onAdjust: adjustPendingPosition,
                    onCommit: commitPendingPosition,
                    onCancel: cancelPendingPosition
                )
                .frame(height: 54)
                Text(playerTimeLabel(model.durationMs ?? 0))
                    .monospacedDigit()
            }
            HStack(spacing: 22) {
                Button { model.playPreviousEpisode() } label: {
                    Label("Previous", systemImage: "backward.end.fill")
                }
                .disabled(model.isOffline || !model.queue.hasPrevious)

                Button { model.seek(to: max(0, model.positionMs - 15_000)) } label: {
                    Label("Back 15 Seconds", systemImage: "gobackward.15")
                }

                Button { model.togglePlayPause() } label: {
                    Label(
                        model.isPlaying ? String(localized: "Pause") : String(localized: "Play"),
                        systemImage: model.isPlaying ? "pause.fill" : "play.fill"
                    )
                }

                Button { model.seek(to: min(model.durationMs ?? Int64.max, model.positionMs + 15_000)) } label: {
                    Label("Forward 15 Seconds", systemImage: "goforward.15")
                }

                Button { model.playNextEpisode() } label: {
                    Label("Next", systemImage: "forward.end.fill")
                }
                .disabled(model.isOffline || !model.queue.hasNext)
            }
            .labelStyle(.iconOnly)
        }
        .padding(22)
        .background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 16))
    }

    private func adjustPendingPosition(by deltaMs: Int64) {
        let durationMs = max(model.durationMs ?? 0, 0)
        let base = pendingPositionMs ?? model.positionMs
        pendingPositionMs = min(max(base + deltaMs, 0), durationMs)
    }

    private func commitPendingPosition() {
        guard let pendingPositionMs else { return }
        self.pendingPositionMs = nil
        model.seek(to: pendingPositionMs)
    }

    private func cancelPendingPosition() {
        pendingPositionMs = nil
    }
}

private struct PlayerTimelineControl: UIViewRepresentable {
    let positionMs: Int64
    let durationMs: Int64
    let isScrubbing: Bool
    let onAdjust: (Int64) -> Void
    let onCommit: () -> Void
    let onCancel: () -> Void

    func makeUIView(context: Context) -> PlayerTimelineControlView {
        let view = PlayerTimelineControlView()
        update(view)
        return view
    }

    func updateUIView(_ view: PlayerTimelineControlView, context: Context) {
        update(view)
    }

    private func update(_ view: PlayerTimelineControlView) {
        view.positionMs = positionMs
        view.durationMs = durationMs
        view.isScrubbing = isScrubbing
        view.onAdjust = onAdjust
        view.onCommit = onCommit
        view.onCancel = onCancel
        view.accessibilityValue = "\(playerTimeLabel(positionMs)) / \(playerTimeLabel(durationMs))"
        view.setNeedsLayout()
    }
}

private final class PlayerTimelineControlView: UIControl {
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var isScrubbing = false
    var onAdjust: ((Int64) -> Void)?
    var onCommit: (() -> Void)?
    var onCancel: (() -> Void)?

    private let trackLayer = CALayer()
    private let progressLayer = CALayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityLabel = String(localized: "Playback position")
        accessibilityTraits = [.adjustable]
        trackLayer.backgroundColor = UIColor.white.withAlphaComponent(0.32).cgColor
        progressLayer.backgroundColor = UIColor.white.cgColor
        layer.addSublayer(trackLayer)
        layer.addSublayer(progressLayer)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var canBecomeFocused: Bool { true }

    override func layoutSubviews() {
        super.layoutSubviews()
        let trackHeight: CGFloat = isFocused ? 12 : 8
        let trackFrame = CGRect(
            x: 0,
            y: (bounds.height - trackHeight) / 2,
            width: bounds.width,
            height: trackHeight
        )
        trackLayer.frame = trackFrame
        trackLayer.cornerRadius = trackHeight / 2
        let fraction = durationMs > 0 ? min(max(CGFloat(positionMs) / CGFloat(durationMs), 0), 1) : 0
        progressLayer.frame = CGRect(
            x: trackFrame.minX,
            y: trackFrame.minY,
            width: trackFrame.width * fraction,
            height: trackFrame.height
        )
        progressLayer.cornerRadius = trackHeight / 2
    }

    override func didUpdateFocus(
        in context: UIFocusUpdateContext,
        with coordinator: UIFocusAnimationCoordinator
    ) {
        super.didUpdateFocus(in: context, with: coordinator)
        if context.previouslyFocusedView === self,
           context.nextFocusedView !== self,
           isScrubbing {
            onCancel?()
        }
        progressLayer.backgroundColor = (isFocused ? UIColor.systemBlue : UIColor.white).cgColor
        setNeedsLayout()
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard let type = presses.first?.type else {
            super.pressesBegan(presses, with: event)
            return
        }
        switch type {
        case .leftArrow:
            onAdjust?(-10_000)
        case .rightArrow:
            onAdjust?(10_000)
        case .select:
            onCommit?()
        case .menu where isScrubbing:
            onCancel?()
        default:
            super.pressesBegan(presses, with: event)
        }
    }

    override func accessibilityIncrement() {
        onAdjust?(10_000)
    }

    override func accessibilityDecrement() {
        onAdjust?(-10_000)
    }
}
