// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibraryTabView: View {
    let session: Session
    let library: TvLibraryTile

    @Binding private var path: NavigationPath
    @StateObject private var hubModel: LibraryHubModel
    @StateObject private var browseModel: LibraryBrowseModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.focusedMedia) private var focusedMedia
    @State private var settledCard: TvMediaCard?
    @State private var heroTask: Task<Void, Never>?

    init(
        session: Session,
        library: TvLibraryTile,
        path: Binding<NavigationPath>
    ) {
        self.session = session
        self.library = library
        _path = path
        _hubModel = StateObject(wrappedValue: LibraryHubModel(session: session, library: library))
        _browseModel = StateObject(wrappedValue: LibraryBrowseModel(session: session, library: library))
    }

    var body: some View {
        ZStack(alignment: .top) {
            appearance.background
                .ignoresSafeArea()
            MediaBackdropView(card: settledCard)
                .frame(height: TvDimensions.backdropHeight)
                .ignoresSafeArea(edges: .top)

            VStack(spacing: 0) {
                MediaHeroView(card: settledCard, fallbackTitle: library.name)
                    .frame(height: TvDimensions.heroHeight)
                innerViewControls
                selectedContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
            }
        }
        .navigationTitle(library.name)
        .onChange(of: focusedMedia) { _, _ in
            settleFocusedMedia()
        }
        .onDisappear {
            heroTask?.cancel()
        }
    }

    private var innerViewControls: some View {
        HStack(spacing: 16) {
            ForEach(hubModel.state.availableViews, id: \.name) { view in
                if hubModel.state.selectedView == view {
                    Button(view.libraryDisplayTitle) {
                        hubModel.selectView(view)
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button(view.libraryDisplayTitle) {
                        hubModel.selectView(view)
                    }
                    .buttonStyle(.bordered)
                }
            }
            Spacer()
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.bottom, 16)
        .focusSection()
    }

    @ViewBuilder
    private var selectedContent: some View {
        switch hubModel.state.selectedView {
        case .recommended:
            LibraryRecommendedView(
                state: hubModel.state,
                onRetry: hubModel.retryRecommendation,
                onRefresh: hubModel.refreshRecommendedSilently,
                onPlay: openPlayback
            )
        case .library:
            LibraryBrowseView(
                model: browseModel,
                onPlay: openPlayback
            )
        default:
            EmptyView()
        }
    }

    private func openPlayback(_ card: TvMediaCard) {
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

    private func settleFocusedMedia() {
        heroTask?.cancel()
        guard let focusedMedia else { return }
        let card = focusedMedia.card
        heroTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard !Task.isCancelled else { return }
            if reduceMotion {
                settledCard = card
            } else {
                withAnimation(.easeInOut(duration: 0.45)) {
                    settledCard = card
                }
            }
        }
    }
}
