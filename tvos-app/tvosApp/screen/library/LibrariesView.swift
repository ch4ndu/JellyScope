// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibrariesView: View {
    let session: Session

    @Environment(\.tvAppearance) private var appearance
    @StateObject private var model: LibrariesModel

    init(session: Session) {
        self.session = session
        _model = StateObject(wrappedValue: LibrariesModel(session: session))
    }

    var body: some View {
        Group {
            if model.state.libraries.isEmpty {
                emptyContent
            } else {
                libraryGrid
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(appearance.background.ignoresSafeArea())
        .navigationTitle(String(localized: "Libraries"))
        .onAppear(perform: model.reload)
    }

    private var libraryGrid: some View {
        ScrollView(.vertical) {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 400), spacing: TvDimensions.ribbonCardSpacing)],
                spacing: TvDimensions.ribbonSpacing
            ) {
                ForEach(model.state.libraries, id: \.id) { library in
                    LibraryTileView(library: library)
                }
                if model.state.error != nil {
                    MediaStatusView(
                        status: .error,
                        title: String(localized: "Could not refresh libraries."),
                        actionTitle: String(localized: "Retry"),
                        action: model.reload
                    )
                }
            }
            .padding(.horizontal, TvDimensions.screenInset)
            .padding(.vertical, TvDimensions.ribbonFocusReserve)
        }
        .scrollClipDisabled()
    }

    @ViewBuilder
    private var emptyContent: some View {
        if model.state.isLoading {
            MediaStatusView(
                status: .loading,
                title: String(localized: "Loading…")
            )
        } else if model.state.error != nil {
            MediaStatusView(
                status: .error,
                title: String(localized: "Could not load libraries."),
                actionTitle: String(localized: "Retry"),
                action: model.reload
            )
        } else {
            MediaStatusView(
                status: .empty,
                title: String(localized: "No libraries found.")
            )
        }
    }
}
