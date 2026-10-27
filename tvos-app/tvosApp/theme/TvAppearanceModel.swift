// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
final class TvAppearanceModel: ObservableObject {
    @Published private(set) var state: TvAppearanceState
    @Published private(set) var appearance: TvAppearance

    private let presenter: TvAppearancePresenter
    private var handle: WatchHandle?

    init() {
        let presenter = TvosEntry.shared.appearancePresenter()
        let state = presenter.state.value as! TvAppearanceState
        self.presenter = presenter
        self.state = state
        self.appearance = TvTheme.appearance(
            appTheme: state.appTheme,
            tileSize: state.tileSize
        )
        self.handle = presenter.watchState { [weak self] state in
            guard let self else { return }
            let appearanceChanged =
                self.state.appTheme.name != state.appTheme.name ||
                self.state.tileSize.name != state.tileSize.name
            self.state = state
            if appearanceChanged {
                self.appearance = TvTheme.appearance(
                    appTheme: state.appTheme,
                    tileSize: state.tileSize
                )
            }
        }
    }

    func setAppTheme(_ appTheme: AppColorThemeId) {
        presenter.accept(intent: TvAppearanceIntentSetAppTheme(appTheme: appTheme))
    }

    func setTileSize(_ tileSize: TileSizeId) {
        presenter.accept(intent: TvAppearanceIntentSetTileSize(tileSize: tileSize))
    }

    func setImageQuality(_ imageQuality: ImageQuality) {
        presenter.accept(intent: TvAppearanceIntentSetImageQuality(imageQuality: imageQuality))
    }

    func retryImageQuality() {
        presenter.accept(intent: TvAppearanceIntentRetryImageQuality.shared)
    }

    func retryAppTheme() {
        presenter.accept(intent: TvAppearanceIntentRetryAppTheme.shared)
    }

    func retryTileSize() {
        presenter.accept(intent: TvAppearanceIntentRetryTileSize.shared)
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
