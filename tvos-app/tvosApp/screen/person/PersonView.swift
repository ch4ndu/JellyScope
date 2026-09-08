// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PersonView: View {
    let session: Session
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @StateObject private var model: PersonModel

    init(session: Session, personId: String, onPlay: @escaping (PlaybackRoute) -> Void) {
        self.session = session
        self.onPlay = onPlay
        _model = StateObject(wrappedValue: PersonModel(session: session, personId: personId))
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                    PersonHeaderView(
                        state: model.state,
                        onRetry: model.retryHeader
                    )

                    PersonMediaGrid(
                        state: model.state,
                        scrollProxy: proxy,
                        onRetry: model.retry,
                        onLoadMore: model.loadMoreIfNeeded,
                        onPlay: onPlay
                    )
                }
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
        }
        .background(appearance.background.ignoresSafeArea())
        .onAppear(perform: model.load)
    }
}
