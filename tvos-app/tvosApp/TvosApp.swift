// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

@main
struct TvosApp: App {
    init() {
        TvosEntry.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
