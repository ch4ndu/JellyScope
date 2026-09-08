// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct ItemDetailView: View {
    let session: Session
    let initialSeasonId: String?
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @StateObject private var model: ItemDetailModel

    init(
        session: Session,
        itemId: String,
        initialSeasonId: String? = nil,
        onPlay: @escaping (PlaybackRoute) -> Void
    ) {
        self.session = session
        self.initialSeasonId = initialSeasonId
        self.onPlay = onPlay
        _model = StateObject(
            wrappedValue: ItemDetailModel(
                session: session,
                itemId: itemId,
                initialSeasonId: initialSeasonId
            )
        )
    }

    var body: some View {
        Group {
            if model.state.isLoading && model.state.content == nil {
                MediaStatusView(status: .loading, title: String(localized: "Loading…"))
            } else if let content = model.state.content {
                if initialSeasonId != nil {
                    SeasonDetailView(session: session, model: model, onPlay: onPlay)
                } else if content.kind == .series {
                    SeriesDetailView(session: session, model: model, onPlay: onPlay)
                } else {
                    DetailContentView(session: session, model: model, content: content, onPlay: onPlay)
                }
            } else {
                MediaStatusView(
                    status: .error,
                    title: String(localized: "Could not load this title."),
                    actionTitle: String(localized: "Retry"),
                    action: model.load
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(appearance.background.ignoresSafeArea())
        .onAppear(perform: model.load)
    }
}
