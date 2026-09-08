// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import UIKit

struct VlcPlayerHost: UIViewRepresentable {
    let surface: UIView
    let identity: Int64
    let interactionsEnabled: Bool

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        container.isUserInteractionEnabled = interactionsEnabled
        attach(surface, to: container)
        context.coordinator.identity = identity
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        if context.coordinator.identity != identity || surface.superview !== container {
            container.subviews.forEach { $0.removeFromSuperview() }
            attach(surface, to: container)
            context.coordinator.identity = identity
        }
        container.isUserInteractionEnabled = interactionsEnabled
    }

    static func dismantleUIView(_ container: UIView, coordinator: Coordinator) {
        container.subviews.forEach { $0.removeFromSuperview() }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var identity: Int64 = 0
    }

    private func attach(_ view: UIView, to container: UIView) {
        view.removeFromSuperview()
        view.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            view.topAnchor.constraint(equalTo: container.topAnchor),
            view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
    }

}
