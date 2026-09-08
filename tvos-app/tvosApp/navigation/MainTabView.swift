// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct MainTabView: View {
    let session: Session
    private enum Tab: Hashable {
        case home, favorites, libraries, search, downloads, settings
    }

    @State private var selectedTab: Tab = .home
    @State private var homePath = NavigationPath()
    @State private var favoritesPath = NavigationPath()
    @State private var librariesPath = NavigationPath()
    @State private var searchPath = NavigationPath()
    @State private var downloadsPath = NavigationPath()

    var body: some View {
        if #available(tvOS 18.0, *) {
            tabs.tabViewStyle(.sidebarAdaptable)
        } else {
            tabs
        }
    }

    private var tabs: some View {
        TabView(selection: $selectedTab) {
            NavigationStack(path: $homePath) {
                HomeView(session: session, path: $homePath)
                    .navigationDestination(for: HomeViewAllRoute.self) { route in
                        HomeViewAllView(session: session, row: route.row) { card in
                            play(card, in: $homePath)
                        }
                    }
                    .mediaDestinations(session: session, path: $homePath, onOpenPlaybackSettings: openPlaybackSettings)
            }
            .tabItem { Label("Home", systemImage: "house") }
            .tag(Tab.home)

            NavigationStack(path: $favoritesPath) {
                HomeViewAllView(session: session, row: .favorites) { card in
                    play(card, in: $favoritesPath)
                }
                .mediaDestinations(session: session, path: $favoritesPath, onOpenPlaybackSettings: openPlaybackSettings)
            }
            .tabItem { Label("Favorites", systemImage: "heart") }
            .tag(Tab.favorites)

            NavigationStack(path: $librariesPath) {
                LibrariesView(session: session)
                    .navigationDestination(for: LibraryRoute.self) { route in
                        LibraryTabView(session: session, library: route.library, path: $librariesPath)
                    }
                    .mediaDestinations(session: session, path: $librariesPath, onOpenPlaybackSettings: openPlaybackSettings)
            }
            .tabItem { Label("Libraries", systemImage: "rectangle.stack") }
            .tag(Tab.libraries)

            NavigationStack(path: $searchPath) {
                SearchView(session: session, path: $searchPath)
                    .mediaDestinations(session: session, path: $searchPath, onOpenPlaybackSettings: openPlaybackSettings)
            }
            .tabItem { Label("Search", systemImage: "magnifyingglass") }
            .tag(Tab.search)

            NavigationStack(path: $downloadsPath) {
                DownloadsView(session: session) { downloadId, restart in
                    downloadsPath.append(OfflinePlaybackRoute(downloadId: downloadId, restart: restart))
                }
                .mediaDestinations(session: session, path: $downloadsPath, onOpenPlaybackSettings: openPlaybackSettings)
            }
            .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }
            .tag(Tab.downloads)

            NavigationStack {
                SettingsFormView(session: session)
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(Tab.settings)
        }
    }

    private func play(_ card: TvMediaCard, in path: Binding<NavigationPath>) {
        guard card.isDirectlyPlayable else {
            path.wrappedValue.append(MediaRoute(itemId: card.id))
            return
        }
        path.wrappedValue.append(
            PlaybackRoute(
                itemId: card.id,
                mediaSourceId: nil,
                startPositionTicks: card.playbackPositionTicks
            )
        )
    }

    private func openPlaybackSettings() {
        selectedTab = .settings
    }
}
