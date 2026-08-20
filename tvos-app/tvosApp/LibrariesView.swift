// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibrariesView: View {
    let session: Session

    @StateObject private var model: LibrariesModel
    @State private var browsing: TvLibraryTile?

    init(session: Session) {
        self.session = session
        _model = StateObject(wrappedValue: LibrariesModel(session: session))
    }

    var body: some View {
        Group {
            if model.state.isLoading {
                ProgressView()
            } else if model.state.error != nil {
                VStack(spacing: 20) {
                    Text(String(localized: "Could not load libraries."))
                    Button("Retry") { model.reload() }
                }
            } else if let library = browsing {
                LibraryBrowseView(session: session, library: library, onBack: { browsing = nil })
            } else {
                ScrollView(.vertical) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 400), spacing: 40)], spacing: 40) {
                        ForEach(model.state.libraries, id: \.id) { library in
                            Button {
                                browsing = library
                            } label: {
                                ZStack(alignment: .bottomLeading) {
                                    RemoteImage(url: library.imageUrl)
                                        .frame(height: 240)
                                    Text(library.name)
                                        .font(.title3)
                                        .padding(16)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .background(.black.opacity(0.5))
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.card)
                        }
                    }
                    .padding(60)
                }
            }
        }
    }
}

struct LibraryBrowseView: View {
    let session: Session
    let library: TvLibraryTile
    let onBack: () -> Void

    @StateObject private var model: LibraryBrowseModel

    init(session: Session, library: TvLibraryTile, onBack: @escaping () -> Void) {
        self.session = session
        self.library = library
        self.onBack = onBack
        _model = StateObject(wrappedValue: LibraryBrowseModel(session: session, libraryId: library.id))
    }

    var body: some View {
        Group {
            if model.state.isLoading {
                ProgressView()
            } else if model.state.error != nil && model.state.items.isEmpty {
                VStack(spacing: 20) {
                    Text(String(format: String(localized: "Could not load %@."), library.name))
                    Button("Retry") { model.reload() }
                }
            } else {
                ScrollView(.vertical) {
                    Text(library.name)
                        .font(.title2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 16)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 40)], spacing: 48) {
                        ForEach(Array(model.state.items.enumerated()), id: \.element.id) { index, card in
                            MediaCardView(card: card)
                                .onAppear {
                                    model.loadMoreIfNeeded(focusedIndex: index)
                                }
                        }
                    }
                    if model.state.isLoadingMore {
                        ProgressView()
                            .padding(24)
                    } else if model.state.error != nil {
                        VStack(spacing: 12) {
                            Text("Could not load more titles.")
                                .foregroundStyle(.secondary)
                            Button("Retry") { model.retryLoadMore() }
                        }
                        .padding(24)
                    }
                }
                .padding(60)
            }
        }
        .onExitCommand(perform: onBack)
    }
}
