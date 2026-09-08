// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsAppearanceSection: View {
    @EnvironmentObject private var model: TvAppearanceModel

    private let themes: [AppColorThemeId] = [.ocean, .midnight, .ember]
    private let tileSizes: [TileSizeId] = [.small, .medium, .large]

    var body: some View {
        Section(String(localized: "Appearance")) {
            Picker(String(localized: "Theme"), selection: appThemeBinding) {
                ForEach(themes, id: \.name) { theme in
                    HStack(spacing: 12) {
                        ThemeSwatches(colors: TvTheme.swatches(for: theme))
                        Text(themeLabel(theme))
                    }
                    .tag(theme.name)
                }
            }
            .pickerStyle(.navigationLink)

            Picker(String(localized: "Tile Size"), selection: tileSizeBinding) {
                ForEach(tileSizes, id: \.name) { tileSize in
                    Text(tileSizeLabel(tileSize)).tag(tileSize.name)
                }
            }
            .pickerStyle(.navigationLink)

            if model.state.isSavingAppTheme || model.state.isSavingTileSize {
                ProgressView(String(localized: "Saving…"))
            }

            if model.state.appThemeSaveError {
                saveError(
                    String(localized: "The theme could not be saved."),
                    retry: model.retryAppTheme
                )
            }

            if model.state.tileSizeSaveError {
                saveError(
                    String(localized: "The tile size could not be saved."),
                    retry: model.retryTileSize
                )
            }
        }
    }

    private var appThemeBinding: Binding<String> {
        Binding(
            get: { model.state.appTheme.name },
            set: { name in
                guard let theme = themes.first(where: { $0.name == name }) else { return }
                model.setAppTheme(theme)
            }
        )
    }

    private var tileSizeBinding: Binding<String> {
        Binding(
            get: { model.state.tileSize.name },
            set: { name in
                guard let tileSize = tileSizes.first(where: { $0.name == name }) else { return }
                model.setTileSize(tileSize)
            }
        )
    }

    private func themeLabel(_ appTheme: AppColorThemeId) -> String {
        switch appTheme.name {
        case "Ocean": String(localized: "Ocean")
        case "Midnight": String(localized: "Midnight")
        default: String(localized: "Ember")
        }
    }

    private func tileSizeLabel(_ tileSize: TileSizeId) -> String {
        switch tileSize.name {
        case "Small": String(localized: "Small")
        case "Large": String(localized: "Large")
        default: String(localized: "Medium")
        }
    }

    private func saveError(_ message: String, retry: @escaping () -> Void) -> some View {
        LabeledContent {
            Button(String(localized: "Retry"), action: retry)
        } label: {
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(model.appearance.error)
        }
    }
}

private struct ThemeSwatches: View {
    let colors: [Color]

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(colors.enumerated()), id: \.offset) { _, color in
                Circle()
                    .fill(color)
                    .frame(width: 14, height: 14)
                    .overlay(Circle().stroke(.white.opacity(0.24), lineWidth: 1))
            }
        }
        .accessibilityHidden(true)
    }
}
