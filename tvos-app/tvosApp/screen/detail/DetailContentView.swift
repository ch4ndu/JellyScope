// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailContentView: View {
    let session: Session
    @ObservedObject var model: ItemDetailModel
    let content: TvItemDetailContent
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                DetailHeroView(content: content)
                DetailActionsView(session: session, model: model, content: content, onPlay: onPlay)
                DetailMetadataView(content: content)

                if !model.state.cast.isEmpty || !model.state.crew.isEmpty {
                    DetailPeopleView(cast: model.state.cast, crew: model.state.crew)
                }

                DetailRelatedView(groups: model.state.relatedGroups, onPlay: onPlay)

                if model.state.relatedLoading {
                    ProgressView()
                        .padding(.horizontal, TvDimensions.screenInset)
                }
            }
            .padding(.bottom, TvDimensions.screenBottomInset)
        }
        .background(alignment: .top) {
            if let backdropUrl = content.backdropUrl {
                ZStack {
                    RemoteImage(url: backdropUrl, contentMode: .fill, fallbackTitle: content.title)
                    appearance.backdropScrim
                    appearance.backdropSideScrim
                }
                .frame(height: TvDimensions.backdropHeight)
                .clipped()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .ignoresSafeArea(edges: .top)
            }
        }
        .overlay(alignment: .topTrailing) {
            if model.state.isRefreshing {
                ProgressView()
                    .padding(TvDimensions.screenInset)
            }
        }
    }
}
