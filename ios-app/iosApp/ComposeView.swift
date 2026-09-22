// SPDX-License-Identifier: MPL-2.0

import SharedUi
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    @Binding var playerFullscreen: Bool
    let downloadBackgroundTaskController: DownloadBackgroundTaskController

    func makeUIViewController(context: Context) -> UIViewController {
        let viewController = MainViewControllerKt.MainViewController(onPlayerFullscreenChanged: { fullscreen in
            playerFullscreen = fullscreen.boolValue
        })
        // MainViewController has initialized Koin before this process-lifetime
        // owner creates its shared-ui bridge.
        downloadBackgroundTaskController.install()
        return viewController
    }

    func updateUIViewController(
        _ uiViewController: UIViewController,
        context: Context
    ) {
        uiViewController.view.setNeedsLayout()
    }
}
