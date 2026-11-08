// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SeerrView: View {
    @StateObject private var model: SeerrModel
    @Binding private var path: NavigationPath
    @State private var query = ""
    @Environment(\.tvAppearance) private var appearance
    @Environment(\.scenePhase) private var scenePhase

    init(session: Session, boundaryEpoch: Int64, path: Binding<NavigationPath>) {
        _model = StateObject(wrappedValue: SeerrModel(session: session, boundaryEpoch: boundaryEpoch))
        _path = path
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            if model.state.detail == nil {
            HStack {
                Text(SeerrCopy.text("seerr.title")).font(.largeTitle.bold())
                Spacer()
                if model.state.section != .connection {
                    sectionButton("seerr.manage", section: .connection)
                }
            }
            HStack(spacing: 24) {
                sectionButton("seerr.discover", section: .discover)
                sectionButton("seerr.my_requests", section: .requests)
            }
            .disabled(model.state.confirmation?.submitting == true)
            .focusSection()
            }

            if !model.state.ownerValid {
                SeerrMessage(key: "seerr.error.owner_changed")
            } else if model.state.section == .connection {
                Button(SeerrCopy.text("seerr.detail.back")) { model.send(TvSeerrIntentSelectSection(section: .discover)) }
                SeerrConnectionView(
                    state: model.state.connection,
                    onUrlChanged: { model.send(TvSeerrIntentUrlChanged(url: $0)) },
                    onValidate: { model.send(TvSeerrIntentValidateService(url: $0)) },
                    onLogin: model.login,
                    onQuickConnect: { model.send(TvSeerrIntentBeginQuickConnect(url: $0)) },
                    onCancel: { model.send(TvSeerrIntentCancelAuthentication.shared) },
                    onRestore: { model.send(TvSeerrIntentRestoreConnection.shared) },
                    onDisconnect: { model.send(TvSeerrIntentDisconnect.shared) }
                )
                .onExitCommand { model.send(TvSeerrIntentBack.shared) }
            } else if model.state.restricted {
                SeerrMessage(key: "seerr.error.restricted_account")
            } else if !model.state.connection.connected || model.state.connection.busy {
                VStack(alignment: .leading, spacing: 24) {
                    if model.state.connection.busy {
                        ProgressView(SeerrCopy.text("seerr.connection.checking"))
                    }
                    SeerrMessage(key: model.state.connection.errorKey ?? "seerr.setup.explanation")
                    Button(SeerrCopy.text("seerr.manage")) {
                        model.send(TvSeerrIntentSelectSection(section: .connection))
                    }
                }
            } else {
                catalogContent
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, model.state.detail == nil ? TvDimensions.screenInset : 0)
        .padding(.top, model.state.detail == nil ? 35 : 0)
        .background(appearance.background.ignoresSafeArea())
        .foregroundStyle(appearance.primaryText)
        .tint(appearance.accent)
        .sheet(isPresented: Binding(
            get: { model.state.confirmation != nil },
            set: { if !$0 { model.send(TvSeerrIntentBack.shared) } }
        )) {
            if let confirmation = model.state.confirmation {
                SeerrConfirmationView(
                    confirmation: confirmation,
                    onSubmit: { model.send(TvSeerrIntentSubmitRequest.shared) },
                    onCancel: { model.send(TvSeerrIntentBack.shared) }
                )
            }
        }
        .onAppear { model.appeared(active: scenePhase == .active) { itemId in path.append(MediaRoute(itemId: itemId)) } }
        .onChange(of: scenePhase) { _, phase in model.sceneChanged(active: phase == .active) }
        .onDisappear(perform: model.disappeared)
        .onChange(of: model.state.ownerValid) { _, valid in if !valid { query = "" } }
        .onChange(of: model.state.restricted) { _, restricted in if restricted { query = "" } }
    }

    private func sectionButton(_ key: String, section: TvSeerrSection) -> some View {
        Button {
            model.send(TvSeerrIntentSelectSection(section: section))
        } label: {
            HStack {
                if model.state.section == section { Image(systemName: "checkmark") }
                Text(SeerrCopy.text(key))
            }
        }
        .accessibilityAddTraits(model.state.section == section ? .isSelected : [])
    }

    private var catalogContent: some View {
        ZStack {
            SeerrBrowseView(
                state: model.state.browse,
                isRequests: model.state.section == .requests,
                active: model.state.detail == nil,
                reconciliationRequired: model.state.reconciliationRequired,
                query: $query,
                onCatalog: { model.send(TvSeerrIntentSelectCatalog(catalog: $0)) },
                onSearch: { model.send(TvSeerrIntentSearch(query: query)) },
                onClear: {
                    query = ""
                    model.send(TvSeerrIntentSelectCatalog(catalog: .trending))
                },
                onHideAvailable: { model.send(TvSeerrIntentToggleHideAvailable.shared) },
                onRefresh: { model.send(TvSeerrIntentRefresh.shared) },
                onLoadMore: { model.send(TvSeerrIntentLoadMore.shared) },
                onReconcile: { model.send(TvSeerrIntentReconcile.shared) },
                onFocus: { model.send(TvSeerrIntentFocusCard(id: $0)) },
                onOpen: { model.send(TvSeerrIntentOpenDetail(id: $0)) }
            )
            .opacity(model.state.detail == nil ? 1 : 0)
            .allowsHitTesting(model.state.detail == nil)
            .accessibilityHidden(model.state.detail != nil)

            if let detail = model.state.detail {
                SeerrDetailView(
                    state: detail,
                    onBack: { model.send(TvSeerrIntentBack.shared) },
                    onRefresh: { model.send(TvSeerrIntentRefresh.shared) },
                    onQuality: { model.send(TvSeerrIntentSelectQuality(fourK: $0)) },
                    onSeason: { model.send(TvSeerrIntentToggleSeason(number: $0)) },
                    onRequest: { model.send(TvSeerrIntentConfirmRequest.shared) },
                    onReconcile: { model.send(TvSeerrIntentReconcile.shared) },
                    onLibrary: { model.send(TvSeerrIntentViewInLibrary.shared) },
                    onSimilar: { model.send(TvSeerrIntentOpenSimilar(id: $0)) },
                    onRetrySimilar: { model.send(TvSeerrIntentRetrySimilar.shared) },
                    onFocus: { model.send(TvSeerrIntentFocusDetail(id: $0)) },
                    onPosition: { model.send(TvSeerrIntentRememberPosition(section: $0, id: $1)) }

                )
                .id(detail.entryId)
                .onExitCommand { model.send(TvSeerrIntentBack.shared) }
            }
        }
    }
}

private struct SeerrBrowseView: View {
    let state: TvSeerrBrowseState
    let isRequests: Bool
    let active: Bool
    let reconciliationRequired: Bool
    @Binding var query: String
    let onCatalog: (SeerrCatalog) -> Void
    let onSearch: () -> Void
    let onClear: () -> Void
    let onHideAvailable: () -> Void
    let onRefresh: () -> Void
    let onLoadMore: () -> Void
    let onReconcile: () -> Void
    let onFocus: (String) -> Void
    let onOpen: (String) -> Void
    @Environment(\.tvAppearance) private var appearance
    @FocusState private var focusedCard: String?
    @FocusState private var searchFocused: Bool
    @FocusState private var refreshFocused: Bool

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 32) {
                    if !isRequests {
                        HStack(spacing: 24) {
                            category("seerr.trending", .trending)
                            category("seerr.movies", .movies)
                            category("seerr.tv", .tv)
                        }
                        .focusSection()
                        HStack(spacing: 24) {
                            TextField(SeerrCopy.text("seerr.search.prompt"), text: $query)
                                .accessibilityLabel(SeerrCopy.text("seerr.search.label"))
                                .focused($searchFocused)
                                .submitLabel(.search)
                                .onSubmit { submitSearch() }
                            Button(SeerrCopy.text("seerr.search.submit"), action: submitSearch)
                            Button(SeerrCopy.text("seerr.search.clear")) {
                                searchFocused = false
                                onClear()
                            }
                        }
                    }

                    if !isRequests && state.catalog != .search {
                        Button(action: onHideAvailable) {
                            Label(SeerrCopy.text("seerr.hide_available"), systemImage: state.hideAvailable ? "checkmark.circle.fill" : "circle")
                        }
                        SeerrMessage(key: "seerr.hide_available.hint")
                    }
                    HStack(spacing: 24) {
                        Button(SeerrCopy.text("seerr.refresh"), action: onRefresh)
                            .disabled(state.loading).focused($refreshFocused).id("refresh")
                        if state.loading { ProgressView(SeerrCopy.text("seerr.loading")) }
                    }
                    if reconciliationRequired {
                        SeerrMessage(key: "seerr.error.reconciliation_required")
                        Button(SeerrCopy.text("seerr.reconcile"), action: onReconcile).disabled(state.loading)
                    }
                    if let error = state.errorKey { SeerrMessage(key: error, isError: true) }
                    if !state.loading && state.errorKey == nil && state.cards.isEmpty {
                        SeerrMessage(key: state.loaded ? (!isRequests && state.catalog != .search && state.hideAvailable ? "seerr.results.filtered" : "seerr.results.empty") : "seerr.search.help")
                    }

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: appearance.cards.posterWidth), spacing: 34)], spacing: 48) {
                        ForEach(state.cards, id: \.id) { card in
                            Button { onOpen(card.id) } label: {
                                SeerrCardView(card: card, width: appearance.cards.posterWidth)
                            }
                            .buttonStyle(.card)
                            .focused($focusedCard, equals: card.id)
                            .id(card.id)
                            .accessibilityLabel(card.title ?? SeerrCopy.text("seerr.title.unknown"))
                            .accessibilityHint(SeerrCopy.text("seerr.detail.open"))
                        }
                    }
                    .padding(TvDimensions.ribbonFocusReserve)

                    if state.hasMore {
                        Button(SeerrCopy.text("seerr.load_more"), action: onLoadMore).disabled(state.loading)
                    }
                }
                .padding(.horizontal, TvDimensions.ribbonFocusReserve)
                .padding(.vertical, 30)
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
            .disabled(!active)
            .onChange(of: state.hideAvailable) { _, _ in if active { restoreFocus(proxy) } }
            .onChange(of: focusedCard) { _, id in
                if active, let id { onFocus(id) }
            }
            .onChange(of: active) { _, visible in
                if visible { restoreFocus(proxy) } else { focusedCard = nil; searchFocused = false }
            }
            .onAppear { if active { restoreFocus(proxy) } }
        }
    }

    private func submitSearch() {
        searchFocused = false
        onSearch()
    }

    private func category(_ key: String, _ catalog: SeerrCatalog) -> some View {
        Button(SeerrCopy.text(key)) { searchFocused = false; onCatalog(catalog) }
            .accessibilityAddTraits(state.catalog == catalog ? .isSelected : [])
    }

    private func restoreFocus(_ proxy: ScrollViewProxy) {
        guard let id = state.focusedCardId, state.cards.contains(where: { $0.id == id }) else {
            proxy.scrollTo("refresh", anchor: .center)
            DispatchQueue.main.async { refreshFocused = true }
            return
        }
        proxy.scrollTo(id, anchor: .center)
        DispatchQueue.main.async { focusedCard = id }
    }
}

private struct SeerrCardView: View {
    let card: TvSeerrCard
    let width: CGFloat
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MediaCardContent(title: card.artworkCard.title.isEmpty ? SeerrCopy.text("seerr.title.unknown") : card.artworkCard.title,
                             subtitle: card.year, width: width) {
                SeerrPosterView(url: card.artworkCard.imageUrl, width: width)
            }
            SeerrAvailabilityView(standardKey: card.standardKey, fourKKey: card.fourKKey)
            ForEach(card.requests, id: \.id) { request in SeerrRequestStatusView(request: request) }
        }
        .frame(width: width, alignment: .leading)
    }
}

struct SeerrPosterView: View {
    let url: String?
    let width: CGFloat
    var isPerson = false
    @Environment(\.tvAppearance) private var appearance

    private var trustedURL: URL? {
        guard let url, let value = URL(string: url), value.scheme == "https",
              value.host == "image.tmdb.org", value.user == nil, value.password == nil,
              value.port == nil, value.path.hasPrefix("/t/p/") else { return nil }
        return value
    }

    var body: some View {
        AsyncImage(url: trustedURL) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                Rectangle().fill(appearance.surface)
                    .overlay(Image(systemName: isPerson ? "person.crop.rectangle" : "film").font(.largeTitle).foregroundStyle(appearance.secondaryText))
            }
        }
        .frame(width: width, height: width * 1.5)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
        .accessibilityHidden(true)
    }
}

struct SeerrMessage: View {
    let key: String
    var isError = false
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        Text(SeerrCopy.text(key))
            .font(.body)
            .foregroundStyle(isError ? appearance.error : appearance.secondaryText)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct SeerrAvailabilityView: View {
    let standardKey: String
    let fourKKey: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(format: SeerrCopy.text("seerr.availability.format"),
                        SeerrCopy.text("seerr.quality.standard"), SeerrCopy.text(standardKey)))
            Text(String(format: SeerrCopy.text("seerr.availability.format"),
                        SeerrCopy.text("seerr.quality.4k"), SeerrCopy.text(fourKKey)))
        }
        .font(.caption)
    }
}

struct SeerrRequestStatusView: View {
    let request: TvSeerrRequestStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(String(format: SeerrCopy.text("seerr.request.status_format"),
                        SeerrCopy.text(request.qualityKey), SeerrCopy.text(request.statusKey)))
            if !request.seasons.isEmpty {
                Text(String(format: SeerrCopy.text("seerr.request.seasons_format"), request.seasons))
            }
        }
        .font(.caption)
    }
}
