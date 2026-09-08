// SPDX-License-Identifier: MPL-2.0

import SwiftUI

enum MediaStatus: Equatable {
    case loading
    case error
    case empty
}

struct MediaStatusView: View {
    let status: MediaStatus
    let title: String
    var actionTitle: String? = nil
    var action: () -> Void = {}

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        Button(action: action) {
            VStack(spacing: 14) {
                if status == .loading {
                    ProgressView()
                } else {
                    Image(systemName: status == .error ? "exclamationmark.triangle" : "rectangle.stack")
                        .font(.title2)
                }
                Text(title)
                    .font(.callout.weight(.semibold))
                    .multilineTextAlignment(.center)
                if let actionTitle {
                    Text(actionTitle)
                        .font(.caption)
                        .foregroundStyle(appearance.secondaryText)
                }
            }
            .frame(width: appearance.cards.statusWidth, height: appearance.cards.statusHeight)
            .background(appearance.surface.opacity(0.78), in: RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
        }
        .buttonStyle(.card)
    }
}
