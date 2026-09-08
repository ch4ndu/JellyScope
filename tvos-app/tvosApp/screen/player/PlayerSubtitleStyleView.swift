// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct PlayerSubtitleStyleView: View {
    @ObservedObject var model: PlaybackModel
    let onClose: () -> Void

    private let scales: [Float] = [0.85, 1, 1.2]
    private let colors: [(String, String?)] = [
        (String(localized: "Default"), nil),
        (String(localized: "White"), "#FFFFFF"),
        (String(localized: "Yellow"), "#FFFF00"),
    ]
    private let backgrounds: [(String, String?)] = [
        (String(localized: "Default"), nil),
        (String(localized: "Black"), "#000000"),
        (String(localized: "Transparent"), "#00000000"),
    ]
    private let edges = ["None", "Outline", "DropShadow"]

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Subtitle Style")
                .font(.title2.bold())
            Menu("Text Size") {
                ForEach(scales, id: \.self) { scale in
                    Button {
                        update(fontScale: scale)
                    } label: {
                        styleLabel(scaleLabel(scale), selected: model.subtitleStyle?.fontScale == scale)
                    }
                }
            }
            Menu("Text Color") {
                ForEach(colors.indices, id: \.self) { index in
                    Button {
                        setForeground(colors[index].1)
                    } label: {
                        styleLabel(
                            colors[index].0,
                            selected: model.subtitleStyle?.foregroundColor == colors[index].1
                        )
                    }
                }
            }
            Menu("Background") {
                ForEach(backgrounds.indices, id: \.self) { index in
                    Button {
                        setBackground(backgrounds[index].1)
                    } label: {
                        styleLabel(
                            backgrounds[index].0,
                            selected: model.subtitleStyle?.backgroundColor == backgrounds[index].1
                        )
                    }
                }
            }
            Menu("Edge") {
                ForEach(edges, id: \.self) { edge in
                    Button {
                        update(edgeStyleName: edge)
                    } label: {
                        styleLabel(edgeLabel(edge), selected: model.subtitleStyle?.edgeStyle.name == edge)
                    }
                }
            }
            Button("Done", action: onClose)
        }
        .padding(36)
        .frame(width: 560)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .onExitCommand(perform: onClose)
    }

    private func update(fontScale: Float) {
        model.setSubtitleStyle(
            fontScale: fontScale,
            foreground: model.subtitleStyle?.foregroundColor,
            background: model.subtitleStyle?.backgroundColor,
            edgeStyleName: model.subtitleStyle?.edgeStyle.name ?? "None"
        )
    }

    private func setForeground(_ foreground: String?) {
        model.setSubtitleStyle(
            fontScale: model.subtitleStyle?.fontScale ?? 1,
            foreground: foreground,
            background: model.subtitleStyle?.backgroundColor,
            edgeStyleName: model.subtitleStyle?.edgeStyle.name ?? "None"
        )
    }

    private func setBackground(_ background: String?) {
        model.setSubtitleStyle(
            fontScale: model.subtitleStyle?.fontScale ?? 1,
            foreground: model.subtitleStyle?.foregroundColor,
            background: background,
            edgeStyleName: model.subtitleStyle?.edgeStyle.name ?? "None"
        )
    }

    private func update(edgeStyleName: String) {
        model.setSubtitleStyle(
            fontScale: model.subtitleStyle?.fontScale ?? 1,
            foreground: model.subtitleStyle?.foregroundColor,
            background: model.subtitleStyle?.backgroundColor,
            edgeStyleName: edgeStyleName
        )
    }

    private func scaleLabel(_ scale: Float) -> String {
        switch scale {
        case 0.85: String(localized: "Small")
        case 1.2: String(localized: "Large")
        default: String(localized: "Medium")
        }
    }

    private func edgeLabel(_ edge: String) -> String {
        switch edge {
        case "Outline": String(localized: "Outline")
        case "DropShadow": String(localized: "Drop Shadow")
        default: String(localized: "None")
        }
    }

    @ViewBuilder
    private func styleLabel(_ title: String, selected: Bool) -> some View {
        if selected {
            Label(title, systemImage: "checkmark")
        } else {
            Text(title)
        }
    }
}
