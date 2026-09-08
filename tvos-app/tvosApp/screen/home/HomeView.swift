// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct HomeView: View {
    @StateObject private var model: HomeModel
    @Binding private var path: NavigationPath

    init(session: Session, path: Binding<NavigationPath>) {
        _model = StateObject(wrappedValue: HomeModel(session: session))
        _path = path
    }

    var body: some View {
        HomeContent(
            state: model.state,
            onRetry: model.retry,
            onViewAll: { row in
                path.append(HomeViewAllRoute(row: row))
            },
            onPlay: { card in
                if card.isDirectlyPlayable {
                    path.append(
                        PlaybackRoute(
                            itemId: card.id,
                            mediaSourceId: nil,
                            startPositionTicks: card.playbackPositionTicks
                        )
                    )
                } else {
                    path.append(MediaRoute(itemId: card.id))
                }
            }
        )
        .onAppear {
            model.reload()
        }
    }
}
