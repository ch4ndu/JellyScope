// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private enum SeriesDetailFocusTarget: Hashable {
    case nextUp(String)
    case season(String)
}

struct SeriesDetailView: View {
    let session: Session
    @ObservedObject var model: ItemDetailModel
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusState private var focusedTarget: SeriesDetailFocusTarget?

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                if let content = model.state.content {
                    DetailHeroView(content: content)
                    DetailActionsView(session: session, model: model, content: content, onPlay: onPlay)
                    DetailMetadataView(content: content)
                }

                if let nextUp = model.state.nextUpEpisode {
                    nextUpSection(nextUp)
                }
                if model.state.nextUpLoading && model.state.nextUpEpisode == nil {
                    nextUpStatus(
                        MediaStatusView(status: .loading, title: String(localized: "Loading…"))
                    )
                }
                if model.state.nextUpError != nil {
                    nextUpStatus(
                        MediaStatusView(
                            status: .error,
                            title: String(localized: "Could not load Next Up."),
                            actionTitle: String(localized: "Retry"),
                            action: model.retrySeasons
                        ),
                        showsTitle: model.state.nextUpEpisode == nil
                    )
                }

                seasonsSection

                if !model.state.cast.isEmpty || !model.state.crew.isEmpty {
                    DetailPeopleView(cast: model.state.cast, crew: model.state.crew)
                }

                DetailRelatedView(groups: model.state.relatedGroups, onPlay: onPlay)
            }
            .padding(.bottom, TvDimensions.screenBottomInset)
        }
        .background(alignment: .top) {
            if let content = model.state.content, let backdropUrl = content.backdropUrl {
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
    }

    private func nextUpSection(_ episode: TvMediaCard) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Next Up"))
                .font(.title3.weight(.semibold))
            MediaCardView(
                card: episode,
                width: appearance.cards.landscapeWidth,
                style: .landscape,
                onPlay: { _ in
                    guard let route = model.nextUpPlaybackRoute() else { return }
                    onPlay(route)
                }
            )
            .focused($focusedTarget, equals: .nextUp(episode.id))
            .defaultFocus($focusedTarget, initialFocusTarget)
        }
        .padding(.horizontal, TvDimensions.screenInset)
    }

    private func nextUpStatus<Content: View>(
        _ content: Content,
        showsTitle: Bool = true
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if showsTitle {
                Text(String(localized: "Next Up"))
                    .font(.title3.weight(.semibold))
            }
            content
        }
        .padding(.horizontal, TvDimensions.screenInset)
    }

    @ViewBuilder
    private var seasonsSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(String(localized: "Seasons"))
                .font(.title3.weight(.semibold))

            if model.state.seasonsLoading && model.state.seasons.isEmpty {
                MediaStatusView(status: .loading, title: String(localized: "Loading…"))
            } else {
                if model.state.seasonsError != nil {
                    MediaStatusView(
                        status: .error,
                        title: String(localized: "Could not load seasons."),
                        actionTitle: String(localized: "Retry"),
                        action: model.retrySeasons
                    )
                }

                if model.state.seasons.isEmpty && model.state.seasonsError == nil {
                    MediaStatusView(status: .empty, title: String(localized: "No seasons found."))
                } else if let seriesId = model.state.content?.id, !model.state.seasons.isEmpty {
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                            ForEach(model.state.seasons, id: \.id) { season in
                                SeriesSeasonCard(seriesId: seriesId, season: season)
                                    .focused($focusedTarget, equals: .season(season.id))
                                    .defaultFocus($focusedTarget, initialFocusTarget)
                            }
                        }
                        .padding(.vertical, TvDimensions.ribbonFocusReserve)
                    }
                    .scrollClipDisabled()
                    .focusSection()
                }
            }
        }
        .padding(.horizontal, TvDimensions.screenInset)
    }

    private var initialFocusTarget: SeriesDetailFocusTarget? {
        if let nextUp = model.state.nextUpEpisode {
            return .nextUp(nextUp.id)
        }
        return model.state.seasons.first.map { .season($0.id) }
    }
}
