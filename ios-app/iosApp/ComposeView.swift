// SPDX-License-Identifier: MPL-2.0

import SharedUi
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    @Binding var playerFullscreen: Bool

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(onPlayerFullscreenChanged: { fullscreen in
            playerFullscreen = fullscreen.boolValue
        })
    }

    func updateUIViewController(
        _ uiViewController: UIViewController,
        context: Context
    ) {
        uiViewController.view.setNeedsLayout()
    }
}
