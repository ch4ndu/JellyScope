// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct TvAppearance {
    let background: LinearGradient
    let surface: Color
    let accent: Color
    let primaryText: Color
    let secondaryText: Color
    let error: Color
    let backdropScrim: LinearGradient
    let backdropSideScrim: LinearGradient
    let cards: TvCardDimensions
}

private struct TvAppearanceEnvironmentKey: EnvironmentKey {
    static let defaultValue = TvTheme.fallbackAppearance
}

extension EnvironmentValues {
    var tvAppearance: TvAppearance {
        get { self[TvAppearanceEnvironmentKey.self] }
        set { self[TvAppearanceEnvironmentKey.self] = newValue }
    }
}
