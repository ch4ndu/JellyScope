// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct MediaRoute: Hashable {
    let itemId: String
}

struct PlaybackRoute: Hashable {
    let itemId: String
    let mediaSourceId: String?
    let startPositionTicks: Int64
    let audioStreamIndex: Int32?
    let subtitleMode: TvPlaybackSubtitleMode
    let subtitleStreamIndex: Int32?
    let subtitleAssetId: String?

    init(
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Int64,
        audioStreamIndex: Int32? = nil,
        subtitleMode: TvPlaybackSubtitleMode = .unspecified,
        subtitleStreamIndex: Int32? = nil,
        subtitleAssetId: String? = nil
    ) {
        self.itemId = itemId
        self.mediaSourceId = mediaSourceId
        self.startPositionTicks = startPositionTicks
        self.audioStreamIndex = audioStreamIndex
        self.subtitleMode = subtitleMode
        self.subtitleStreamIndex = subtitleStreamIndex
        self.subtitleAssetId = subtitleAssetId
    }
}

struct OfflinePlaybackRoute: Hashable {
    let downloadId: String
    let restart: Bool
}

struct SeasonRoute: Hashable {
    let seriesId: String
    let seasonId: String
}

struct PersonRoute: Hashable {
    let personId: String
}

struct LibraryRoute: Hashable {
    let library: TvLibraryTile

    static func == (lhs: LibraryRoute, rhs: LibraryRoute) -> Bool {
        lhs.library.id == rhs.library.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(library.id)
    }
}

struct HomeViewAllRoute: Hashable {
    let row: TvHomeRowKind
}

extension View {
    func mediaDestinations(
        session: Session,
        path: Binding<NavigationPath>,
        onOpenPlaybackSettings: @escaping () -> Void
    ) -> some View {
        self
            .navigationDestination(for: MediaRoute.self) { route in
                ItemDetailView(
                    session: session,
                    itemId: route.itemId,
                    onPlay: { path.wrappedValue.append($0) }
                )
            }
            .navigationDestination(for: SeasonRoute.self) { route in
                ItemDetailView(
                    session: session,
                    itemId: route.seriesId,
                    initialSeasonId: route.seasonId,
                    onPlay: { path.wrappedValue.append($0) }
                )
            }
            .navigationDestination(for: PersonRoute.self) { route in
                PersonView(
                    session: session,
                    personId: route.personId,
                    onPlay: { path.wrappedValue.append($0) }
                )
            }
            .navigationDestination(for: OfflinePlaybackRoute.self) { route in
                PlayerScreen(
                    session: session,
                    offlineRoute: route,
                    onOpenPlaybackSettings: onOpenPlaybackSettings
                )
            }
            .navigationDestination(for: PlaybackRoute.self) { route in
                PlayerScreen(
                    session: session,
                    route: route,
                    onOpenPlaybackSettings: onOpenPlaybackSettings
                )
            }
    }
}
