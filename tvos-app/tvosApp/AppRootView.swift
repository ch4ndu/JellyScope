// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct AppRootView: View {
    @StateObject private var sessionModel = SessionModel()

    var body: some View {
        Group {
            if sessionModel.state.phase == .restoring {
                ProgressView()
            } else if let session = sessionModel.state.session, sessionModel.state.phase == .loggedin {
                LoggedInView(session: session, onSignOut: { sessionModel.signOut() })
                    .id(session.serverId + "/" + session.userId)
            } else {
                LoginFlowView()
            }
        }
        .onChange(of: sessionModel.state) { _, state in
            if let session = state.session, state.phase == .loggedin {
                ImageFetcher.shared.configure(
                    scopeKey: session.serverId + "/" + session.userId,
                    authHeader: TvosEntry.shared.imageAuthHeader(session: session)
                )
            } else if state.phase == .loggedout {
                ImageFetcher.shared.reset()
            }
        }
    }
}

struct LoggedInView: View {
    let session: Session
    let onSignOut: () -> Void
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                HomeView(session: session)
                    .mediaDestinations(session: session, onOpenPlaybackSettings: { selectedTab = 3 })
            }
            .tabItem { Text("Home") }
            .tag(0)

            NavigationStack {
                LibrariesView(session: session)
                    .mediaDestinations(session: session, onOpenPlaybackSettings: { selectedTab = 3 })
            }
            .tabItem { Text("Libraries") }
            .tag(1)

            NavigationStack {
                SearchView(session: session)
                    .mediaDestinations(session: session, onOpenPlaybackSettings: { selectedTab = 3 })
            }
            .tabItem { Text("Search") }
            .tag(2)

            NavigationStack {
                SettingsFormView(session: session, onSignOut: onSignOut)
            }
            .tabItem { Text("Settings") }
            .tag(3)
        }
    }
}

/// Navigation routes shared by the Home and Libraries stacks: cards push
/// detail, detail pushes playback.
extension View {
    func mediaDestinations(session: Session, onOpenPlaybackSettings: @escaping () -> Void) -> some View {
        self
            .navigationDestination(for: MediaRoute.self) { route in
                ItemDetailView(session: session, itemId: route.itemId)
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

struct MediaRoute: Hashable {
    let itemId: String
}

struct PlaybackRoute: Hashable {
    let itemId: String
    let mediaSourceId: String?
    let startPositionTicks: Int64
}
