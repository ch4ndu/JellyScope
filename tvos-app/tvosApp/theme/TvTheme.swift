// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

enum TvTheme {
    static let fallbackAppearance = makeAppearance(
        palette: ember,
        cards: TvDimensions.mediumCards
    )

    static func appearance(
        appTheme: AppColorThemeId,
        tileSize: TileSizeId
    ) -> TvAppearance {
        makeAppearance(
            palette: palette(for: appTheme),
            cards: TvDimensions.cards(for: tileSize)
        )
    }

    static func swatches(for appTheme: AppColorThemeId) -> [Color] {
        palette(for: appTheme).swatches
    }

    private static func palette(for appTheme: AppColorThemeId) -> TvPalette {
        switch appTheme.name {
        case "Ocean": ocean
        case "Midnight": midnight
        default: ember
        }
    }

    private static func makeAppearance(
        palette: TvPalette,
        cards: TvCardDimensions
    ) -> TvAppearance {
        let background = LinearGradient(
            colors: [palette.gradientTop, palette.gradientBottom],
            startPoint: .top,
            endPoint: .bottom
        )
        return TvAppearance(
            background: background,
            surface: palette.surface,
            accent: palette.accent,
            primaryText: palette.primaryText,
            secondaryText: palette.secondaryText,
            error: palette.error,
            backdropScrim: LinearGradient(
                stops: [
                    .init(color: palette.background.opacity(0.18), location: 0),
                    .init(color: palette.background.opacity(0.56), location: 0.55),
                    .init(color: palette.background, location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            ),
            backdropSideScrim: LinearGradient(
                colors: [palette.background.opacity(0.94), palette.background.opacity(0.34), .clear],
                startPoint: .leading,
                endPoint: .trailing
            ),
            cards: cards
        )
    }

    private static let ocean = TvPalette(
        gradientTop: color(0x12, 0x29, 0x3D),
        gradientBottom: color(0x08, 0x14, 0x20),
        background: color(0x0D, 0x1F, 0x30),
        surface: color(0x14, 0x2C, 0x41),
        accent: color(0x22, 0xB8, 0xD4),
        primaryText: color(0xF4, 0xF7, 0xFA),
        secondaryText: color(0x9F, 0xB3, 0xC2),
        error: color(0xFF, 0x6B, 0x6B),
        swatches: [
            color(0x0D, 0x1F, 0x30),
            color(0x22, 0xB8, 0xD4),
            color(0xEF, 0x7A, 0x5A),
        ]
    )

    private static let midnight = TvPalette(
        gradientTop: color(0x18, 0x18, 0x26),
        gradientBottom: color(0x0A, 0x0A, 0x11),
        background: color(0x0B, 0x0B, 0x10),
        surface: color(0x16, 0x16, 0x1F),
        accent: color(0x9B, 0x8C, 0xFF),
        primaryText: color(0xEC, 0xEC, 0xF3),
        secondaryText: color(0xAB, 0xAB, 0xBC),
        error: color(0xF2, 0x55, 0x5A),
        swatches: [
            color(0x0B, 0x0B, 0x10),
            color(0x9B, 0x8C, 0xFF),
            color(0xEF, 0x7A, 0x5A),
        ]
    )

    private static let ember = TvPalette(
        gradientTop: color(0x17, 0x13, 0x0E),
        gradientBottom: color(0x00, 0x00, 0x00),
        background: color(0x0A, 0x0A, 0x0A),
        surface: color(0x16, 0x16, 0x16),
        accent: color(0xE9, 0xA4, 0x4B),
        primaryText: .white,
        secondaryText: color(0xB5, 0xB5, 0xB5),
        error: color(0xFF, 0x5A, 0x5A),
        swatches: [
            color(0x0A, 0x0A, 0x0A),
            color(0xE9, 0xA4, 0x4B),
            .white,
        ]
    )

    private static func color(_ red: Int, _ green: Int, _ blue: Int) -> Color {
        Color(
            red: Double(red) / 255,
            green: Double(green) / 255,
            blue: Double(blue) / 255
        )
    }
}

private struct TvPalette {
    let gradientTop: Color
    let gradientBottom: Color
    let background: Color
    let surface: Color
    let accent: Color
    let primaryText: Color
    let secondaryText: Color
    let error: Color
    let swatches: [Color]
}
