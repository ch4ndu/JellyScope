// SPDX-License-Identifier: MPL-2.0

import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            PlayerFullscreenWindow()
        }
    }
}

private struct PlayerFullscreenWindow: View {
    @State private var playerFullscreen = false

    var body: some View {
        ComposeView(playerFullscreen: $playerFullscreen)
            .statusBarHidden(playerFullscreen)
            .persistentSystemOverlays(playerFullscreen ? .hidden : .automatic)
            .ignoresSafeArea()
    }
}
