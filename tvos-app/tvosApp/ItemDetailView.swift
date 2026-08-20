// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct ItemDetailView: View {
    let session: Session

    @StateObject private var model: ItemDetailModel
    @FocusState private var focusedEpisodeId: String?

    init(session: Session, itemId: String) {
        self.session = session
        _model = StateObject(wrappedValue: ItemDetailModel(session: session, itemId: itemId))
    }

    var body: some View {
        Group {
            if model.state.isLoading {
                ProgressView()
            } else if let content = model.state.content {
                detail(content)
            } else {
                VStack(spacing: 20) {
                    Text("Could not load this title.")
                    Button("Retry") { model.reload() }
                }
            }
        }
    }

    private func detail(_ content: TvItemDetailContent) -> some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .top, spacing: 48) {
                    RemoteImage(url: content.posterUrl)
                        .frame(width: 320, height: 480)
                        .clipShape(RoundedRectangle(cornerRadius: 12))

                    VStack(alignment: .leading, spacing: 16) {
                        headline(content)
                        metadataLine(content)
                        badgesRow(content.badges)
                        if let overview = content.overview {
                            Text(overview)
                                .font(.body)
                                .lineLimit(6)
                        }
                        actionRow(content)
                    }
                }

                if content.kind == .series {
                    seasonRail
                }

                relatedShelves
            }
            .padding(60)
        }
        .background(alignment: .top) {
            if let backdrop = content.backdropUrl {
                ZStack {
                    RemoteImage(url: backdrop)
                    LinearGradient(
                        colors: [.black.opacity(0.35), .black.opacity(0.85)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
                .ignoresSafeArea()
            }
        }
    }

    private func headline(_ content: TvItemDetailContent) -> some View {
        Group {
            if let logoUrl = content.logoUrl {
                RemoteImage(url: logoUrl)
                    .frame(width: 400, height: 120)
            } else {
                Text(content.title)
                    .font(.largeTitle)
            }
        }
    }

    private func metadataLine(_ content: TvItemDetailContent) -> some View {
        HStack(spacing: 16) {
            if let year = content.productionYear {
                Text(year.stringValue)
            }
            if let runtime = content.runtimeMinutes {
                Text(String(format: String(localized: "%@ min"), String(runtime.int64Value)))
            }
            if let seriesName = content.seriesName, let label = content.episodeLabel {
                Text(String(format: String(localized: "%@ • %@"), seriesName, label))
            }
        }
        .font(.callout)
        .foregroundStyle(.secondary)
    }

    private func badgesRow(_ badges: TvItemBadges) -> some View {
        HStack(spacing: 12) {
            if let rating = badges.officialRating {
                badge(rating)
            }
            if let resolution = badges.resolution {
                badge(resolution)
            }
            if let audio = badges.audioLayout {
                badge(audio)
            }
            if badges.hasSubtitles {
                badge(String(localized: "CC"))
            }
            if let community = badges.communityRating?.doubleValue {
                HStack(spacing: 4) {
                    Image(systemName: "star.fill")
                        .font(.caption2)
                    Text(String(format: "%.1f", community))
                }
                .font(.caption.bold())
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(.white.opacity(0.15), in: Capsule())
            }
        }
    }

    private func badge(_ text: String) -> some View {
        Text(text)
            .font(.caption.bold())
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(.white.opacity(0.15), in: Capsule())
    }

    private func actionRow(_ content: TvItemDetailContent) -> some View {
        HStack(spacing: 24) {
            if content.kind != .series {
                playButton(content)
            }
            Button {
                model.togglePlayed()
            } label: {
                Image(systemName: content.played ? "checkmark.circle.fill" : "checkmark.circle")
            }
            .foregroundStyle(content.played ? Color.accentColor : Color.primary)
            Button {
                model.toggleFavorite()
            } label: {
                Image(systemName: content.isFavorite ? "heart.fill" : "heart")
            }
            .foregroundStyle(content.isFavorite ? Color.pink : Color.primary)
        }
    }

    // Single Play/Resume pill; "Play from Beginning" lives in its long-press
    // context menu when a resume position exists.
    private func playButton(_ content: TvItemDetailContent) -> some View {
        NavigationLink(
            content.resumePositionTicks > 0
                ? String(localized: "Resume")
                : String(localized: "Play"),
            value: PlaybackRoute(
                itemId: content.id,
                mediaSourceId: nil,
                startPositionTicks: content.resumePositionTicks
            )
        )
        .contextMenu {
            if content.resumePositionTicks > 0 {
                NavigationLink(
                    String(localized: "Play from Beginning"),
                    value: PlaybackRoute(itemId: content.id, mediaSourceId: nil, startPositionTicks: 0)
                )
            }
        }
    }

    private var seasonRail: some View {
        VStack(alignment: .leading, spacing: 20) {
            if model.state.seasonsError != nil {
                HStack(spacing: 16) {
                    Text("Could not load seasons.")
                        .foregroundStyle(.secondary)
                    Button("Retry") { model.retrySeasons() }
                }
            } else {
                if model.state.seasons.count > 1 {
                    ScrollView(.horizontal) {
                        LazyHStack(spacing: 20) {
                            ForEach(model.state.seasons, id: \.id) { season in
                                Button(season.name) {
                                    model.selectSeason(season.id)
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                }
                if model.state.episodesLoading {
                    ProgressView()
                } else if model.state.episodesError != nil {
                    HStack(spacing: 16) {
                        Text("Could not load episodes.")
                            .foregroundStyle(.secondary)
                        Button("Retry") { model.retryEpisodes() }
                    }
                } else {
                    episodeStrip
                }
            }
        }
    }

    private var episodeStrip: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 32) {
                ForEach(model.state.episodes, id: \.id) { episode in
                    episodeCard(episode)
                        .focused($focusedEpisodeId, equals: episode.id)
                }
            }
            .padding(.vertical, 24)
        }
        .scrollClipDisabled()
        .focusSection()
        .defaultFocus($focusedEpisodeId, model.state.nextUpEpisodeId)
    }

    private var relatedShelves: some View {
        VStack(alignment: .leading, spacing: 32) {
            ForEach(model.state.relatedGroups, id: \.groupId) { group in
                MediaShelf(title: group.displayTitle, cards: group.items)
            }
        }
    }

    private func episodeCard(_ episode: TvMediaCard) -> some View {
        NavigationLink(
            value: PlaybackRoute(
                itemId: episode.id,
                mediaSourceId: nil,
                startPositionTicks: episode.playbackPositionTicks
            )
        ) {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .bottom) {
                    RemoteImage(url: episode.imageUrl)
                        .frame(width: 360, height: 200)
                    if let progress = episode.progressPercent?.doubleValue, progress > 0 {
                        GeometryReader { proxy in
                            Rectangle()
                                .fill(Color.accentColor)
                                .frame(width: proxy.size.width * progress / 100.0, height: 5)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                    if episode.played {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.white, Color.accentColor)
                            .padding(8)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                }
                .frame(width: 360, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                Text(
                    episode.episodeLabel.map {
                        String(format: String(localized: "%@ — %@"), $0, episode.title)
                    } ?? episode.title
                )
                    .font(.caption)
                    .lineLimit(1)
            }
            .frame(width: 360)
        }
        .buttonStyle(.card)
    }
}

extension TvRelatedGroup {
    var groupId: String {
        "\(kind.name):\(label ?? "")"
    }

    var displayTitle: String {
        switch kind {
        case .cast: return String(localized: "Cast & Crew")
        case .similar: return String(localized: "More Like This")
        case .genre:
            guard let label else { return String(localized: "Similar Genre") }
            return String(format: String(localized: "Because it's %@"), label)
        case .studio: return label ?? String(localized: "From the Same Studio")
        default: return label ?? String(localized: "Related")
        }
    }
}
