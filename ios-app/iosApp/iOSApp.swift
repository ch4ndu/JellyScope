// SPDX-License-Identifier: MPL-2.0

import SwiftUI

@main
struct iOSApp: App {
    private static let downloadBackgroundTaskController = DownloadBackgroundTaskController.shared

    var body: some Scene {
        WindowGroup {
            PlayerFullscreenWindow(
                downloadBackgroundTaskController: Self.downloadBackgroundTaskController
            )
        }
    }
}

private struct PlayerFullscreenWindow: View {
    @State private var playerFullscreen = false
    let downloadBackgroundTaskController: DownloadBackgroundTaskController

    var body: some View {
        ComposeView(
            playerFullscreen: $playerFullscreen,
            downloadBackgroundTaskController: downloadBackgroundTaskController
        )
            .statusBarHidden(playerFullscreen)
            .persistentSystemOverlays(playerFullscreen ? .hidden : .automatic)
            .ignoresSafeArea()
    }
}
