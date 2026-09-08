// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PersonHeaderView: View {
    let state: TvPersonState
    let onRetry: () -> Void

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        Group {
            if let header = state.header {
                HStack(alignment: .top, spacing: 42) {
                    RemoteImage(url: header.imageUrl, contentMode: .fill, fallbackTitle: header.name)
                        .frame(
                            width: appearance.cards.detailPosterWidth,
                            height: appearance.cards.detailPosterWidth * 1.5
                        )
                        .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))

                    VStack(alignment: .leading, spacing: 18) {
                        Text(header.name)
                            .font(.largeTitle.bold())
                        if let overview = header.overview, !overview.isEmpty {
                            Text(overview)
                                .font(.body)
                                .foregroundStyle(appearance.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: 880, alignment: .leading)
                }
            } else if state.headerLoading {
                MediaStatusView(status: .loading, title: String(localized: "Loading…"))
            } else {
                MediaStatusView(
                    status: .error,
                    title: String(localized: "Could not load this person."),
                    actionTitle: String(localized: "Retry"),
                    action: onRetry
                )
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.top, 50)
    }
}
