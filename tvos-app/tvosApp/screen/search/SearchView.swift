// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchView: View {
    @StateObject private var model: SearchModel
    @Binding private var path: NavigationPath
    init(session: Session, path: Binding<NavigationPath>) {
        _model = StateObject(wrappedValue: SearchModel(session: session))
        _path = path
    }

    var body: some View {
        SearchContentView(
            state: model.state,
            query: Binding(
                get: { model.displayedQuery },
                set: model.userQueryChanged
            ),
            onSubmit: model.submit,
            onSelectRecent: model.selectRecent,
            onRetry: model.retry,
            onClearRecents: model.clearRecents,
            onToggleGenre: model.toggleGenre,
            onSetRuntimeBucket: model.setRuntimeBucket,
            onSetWatchedFilter: model.setWatchedFilter,
            onSelectPerson: model.selectPerson,
            onClearPerson: model.clearPerson,
            onSelectResultCategory: model.selectResultCategory,
            onPlay: play
        )
        .onAppear(perform: model.viewAppeared)
    }

    private func play(_ card: TvMediaCard) {
        guard card.isDirectlyPlayable else {
            path.append(MediaRoute(itemId: card.id))
            return
        }
        path.append(
            PlaybackRoute(
                itemId: card.id,
                mediaSourceId: nil,
                startPositionTicks: card.playbackPositionTicks
            )
        )
    }
}
