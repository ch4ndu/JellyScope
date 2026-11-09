// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SeerrDetailView: View {
    let state: TvSeerrDetailState
    let onBack: () -> Void
    let onRefresh: () -> Void
    let onQuality: (Bool) -> Void
    let onSeason: (Int32) -> Void
    let onRequest: () -> Void
    let onReconcile: () -> Void
    let onLibrary: () -> Void
    let onSimilar: (String) -> Void
    let onRetrySimilar: () -> Void
    let onFocus: (String) -> Void
    let onPosition: (String, String?) -> Void
    @State private var restoreTarget: String?
    @State private var verticalAnchor: String?
    @State private var seasonAnchor: String?
    @State private var castAnchor: String?
    @State private var similarAnchor: String?
    @Environment(\.tvAppearance) private var appearance
    @FocusState private var focusedID: String?

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 36) {
                    hero.id("hero")
                    metadata
                    if !state.cast.isEmpty { cast.id("cast-section") }
                    if state.relatedPhase != TvSeerrRelatedPhase.notattempted && (state.relatedPhase != TvSeerrRelatedPhase.ready || !state.similar.isEmpty) { similar.id("similar-section") }
                }
                .scrollTargetLayout()
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
            .scrollPosition(id: $verticalAnchor)
            .onChange(of: verticalAnchor) { _, id in onPosition("vertical", id) }
            .onChange(of: seasonAnchor) { _, id in onPosition("seasons", id) }
            .onChange(of: castAnchor) { _, id in onPosition("cast", id) }
            .onChange(of: similarAnchor) { _, id in onPosition("similar", id) }
            .onAppear {
                verticalAnchor = state.verticalAnchor
                seasonAnchor = state.seasonAnchor
                castAnchor = state.castAnchor
                similarAnchor = state.similarAnchor
                restoreTarget = state.focusedId
                let target = restorationTarget(state.focusedId)
                proxy.scrollTo(target, anchor: .center)
                DispatchQueue.main.async { focusedID = target }
            }
            .onChange(of: focusedID) { _, id in
                guard let id else { return }
                if id == restoreTarget || (id != "recovery" && id != "loading" && id != "summary") { restoreTarget = nil }
                if restoreTarget == nil { onFocus(id) }
            }
            .onChange(of: state.loading) { _, loading in
                if !loading {
                    let saved = restoreTarget ?? state.focusedId
                    let target = restorationTarget(saved)
                    proxy.scrollTo(target, anchor: .center)
                    DispatchQueue.main.async { focusedID = target }
                }
            }
        }
        .background(appearance.background)
    }

    private var metadata: some View {
        VStack(alignment: .leading, spacing: 28) {
            if state.loading { ProgressView(SeerrCopy.text("seerr.loading")).focusable().focused($focusedID, equals: "loading").id("loading") }
            if let error = state.errorKey { SeerrMessage(key: error, isError: true) }
            if !state.loading && (state.errorKey != nil || !state.selectionEnabled) {
                Button(SeerrCopy.text("seerr.retry"), action: onRefresh)
                    .focused($focusedID, equals: "recovery").id("recovery")
            }
            if let notice = state.noticeKey { SeerrMessage(key: notice) }
            SeerrAvailabilityView(standardKey: state.card.standardKey, fourKKey: state.card.fourKKey)
                .focusable().focused($focusedID, equals: "summary").id("summary")
            quota
            if !state.seasons.isEmpty { seasons }
            if let reason = state.requestReasonKey { SeerrMessage(key: reason) }
            if state.reconciliationRequired {
                Button(SeerrCopy.text("seerr.reconcile"), action: onReconcile).disabled(state.loading)
                    .focused($focusedID, equals: "reconcile").id("reconcile")
            }
            Text(SeerrCopy.text("seerr.request.history")).font(.title2.bold())
            if state.card.requests.isEmpty && !state.loading { SeerrMessage(key: "seerr.request.none") }
            ForEach(state.card.requests, id: \.id) { SeerrRequestStatusView(request: $0) }
        }
        .padding(.horizontal, TvDimensions.screenInset).id("metadata")
    }

    private var actions: some View {
        HStack(spacing: 24) {
            Button(requestLabel, action: onRequest)
                .disabled(!state.canRequest || state.loading).focused($focusedID, equals: "request").id("request")
            qualityChoice(fourK: false, reason: state.standardReasonKey)
                .disabled(state.loading || !state.selectionEnabled)
            if state.showFourK {
                qualityChoice(fourK: true, reason: state.fourKReasonKey)
                    .disabled(state.loading || !state.selectionEnabled)
            }
            if state.libraryAvailable {
                Button(SeerrCopy.text("seerr.library.open"), action: onLibrary)
                    .disabled(state.loading || state.resolvingLibrary || !state.selectionEnabled).focused($focusedID, equals: "library").id("library")
            }
        }
        .focusSection()
    }

    @ViewBuilder
    private var seasons: some View {
        Text(SeerrCopy.text("seerr.seasons.choose")).font(.title2.bold())
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 24) {
                ForEach(state.seasons, id: \.number) { season in
                    VStack(alignment: .leading, spacing: 10) {
                        seasonControl(season)
                        .focused($focusedID, equals: "season:\(season.number)")
                        .accessibilityAddTraits(season.selected ? .isSelected : [])
                        .accessibilityHint(season.reasonKey.map(SeerrCopy.text) ?? "")
                        Text(String(format: SeerrCopy.text("seerr.season.episodes"), Int(season.episodeCount)))
                        SeerrMessage(key: season.availabilityKey)
                        if let reason = season.reasonKey { SeerrMessage(key: reason) }
                    }
                    .frame(width: appearance.cards.posterWidth, alignment: .leading).id("season:\(season.number)")
                }
            }
            .scrollTargetLayout()
            .padding(TvDimensions.ribbonFocusReserve)
        }
        .scrollPosition(id: $seasonAnchor)
        .focusSection()
    }

    private func restorationTarget(_ preferred: String?) -> String {
        guard let preferred else { return defaultTarget }
        if state.loading { return "loading" }
        switch preferred {
        case "recovery" where state.errorKey != nil || !state.selectionEnabled: return preferred
        case "summary": return preferred
        case "request" where state.canRequest: return preferred
        case "library" where state.libraryAvailable && state.selectionEnabled && !state.resolvingLibrary: return preferred
        case "standard" where state.selectionEnabled: return preferred
        case "four-k" where state.selectionEnabled && state.showFourK: return preferred
        case "reconcile" where state.reconciliationRequired: return preferred
        case "similar-retry" where state.relatedEnabled && (state.relatedPhase == TvSeerrRelatedPhase.error || state.relatedPhase == TvSeerrRelatedPhase.interrupted): return preferred
        default:
            if state.selectionEnabled && state.seasons.contains(where: { "season:\($0.number)" == preferred && $0.enabled }) {
                return preferred
            }
            if state.cast.enumerated().contains(where: { "cast:\($0.offset):\($0.element.id)" == preferred }) {
                return preferred
            }
            if state.relatedEnabled && state.similar.contains(where: { "similar:\($0.id)" == preferred }) {
                return preferred
            }
            return defaultTarget
        }
    }

    private var defaultTarget: String {
        if state.loading { return "loading" }
        if state.canRequest { return "request" }
        if state.libraryAvailable && state.selectionEnabled && !state.resolvingLibrary { return "library" }
        if state.selectionEnabled { return "standard" }
        if state.errorKey != nil || !state.selectionEnabled { return "recovery" }
        return "summary"
    }

    private var hero: some View {
        HStack(alignment: .top, spacing: 44) {
            SeerrPosterView(url: state.card.posterUrl, width: appearance.cards.detailPosterWidth)
            VStack(alignment: .leading, spacing: 18) {
                Text(state.card.title ?? SeerrCopy.text("seerr.title.unknown"))
                    .font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
                Text([SeerrCopy.text(state.card.typeKey), state.card.year].filter { !$0.isEmpty }.joined(separator: " · "))
                    .foregroundStyle(appearance.secondaryText)
                if let overview = state.overview, !overview.isEmpty {
                    Text(overview).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: TvDimensions.heroTextWidth, alignment: .leading)
                }
                actions
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.top, TvDimensions.ribbonFocusReserve)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack {
                SeerrBackdropView(url: state.backdropUrl ?? state.card.posterUrl)
                appearance.backdropScrim
            }
            .clipped()
        }
    }

    private var requestLabel: String {
        guard state.card.typeKey == "seerr.tv" else { return SeerrCopy.text("seerr.request.review") }
        let count = Int(state.selectedSeasonCount)
        if count == 0 { return SeerrCopy.text("seerr.seasons.empty") }
        if count == 1 { return SeerrCopy.text("seerr.request.one_season") }
        return String(format: SeerrCopy.text("seerr.request.seasons"), count)
    }

    private var cast: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(SeerrCopy.text("seerr.cast")).font(.title2.bold()).padding(.horizontal, TvDimensions.screenInset)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 24) {
                    ForEach(Array(state.cast.enumerated()), id: \.offset) { index, member in
                        VStack(alignment: .leading, spacing: 10) {
                            SeerrPosterView(url: member.profileUrl, width: appearance.cards.peopleWidth, isPerson: true)
                            Text(member.name).font(.headline)
                            if let character = member.character { Text(character).font(.caption) }
                        }
                        .frame(width: appearance.cards.peopleWidth, alignment: .leading)
                        .accessibilityElement(children: .combine)
                        .focusable().focused($focusedID, equals: "cast:\(index):\(member.id)").id("cast:\(index):\(member.id)")
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.vertical, TvDimensions.ribbonFocusReserve)
            }
            .scrollPosition(id: $castAnchor)
        }
    }

    private var similar: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(SeerrCopy.text("seerr.similar")).font(.title2.bold()).padding(.horizontal, TvDimensions.screenInset)
            if state.relatedPhase == TvSeerrRelatedPhase.error || state.relatedPhase == TvSeerrRelatedPhase.interrupted {
                VStack(alignment: .leading, spacing: 18) {
                    SeerrMessage(key: state.relatedPhase == TvSeerrRelatedPhase.interrupted ? "seerr.similar.interrupted" : "seerr.similar.error")
                    Button(SeerrCopy.text("seerr.retry"), action: onRetrySimilar)
                        .disabled(!state.relatedEnabled || state.loading)
                        .focused($focusedID, equals: "similar-retry").id("similar-retry")
                }
                .padding(.horizontal, TvDimensions.screenInset)
            } else if state.relatedPhase == TvSeerrRelatedPhase.loading || state.relatedPhase == TvSeerrRelatedPhase.notattempted {
                ProgressView(SeerrCopy.text("seerr.loading")).padding(.horizontal, TvDimensions.screenInset)
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 34) {
                    ForEach(state.similar, id: \.id) { card in
                        Button { onSimilar(card.id) } label: {
                            MediaCardContent(title: card.artworkCard.title.isEmpty ? SeerrCopy.text("seerr.title.unknown") : card.artworkCard.title, subtitle: card.year, width: appearance.cards.posterWidth) {
                                SeerrPosterView(url: card.artworkCard.imageUrl, width: appearance.cards.posterWidth)
                            }
                        }
                        .buttonStyle(.card)
                        .disabled(!state.relatedEnabled || state.loading)
                        .focused($focusedID, equals: "similar:\(card.id)").id("similar:\(card.id)")
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.vertical, TvDimensions.ribbonFocusReserve)
            }
            .scrollPosition(id: $similarAnchor)
            .focusSection()
        }
    }

    @ViewBuilder
    private func seasonControl(_ season: TvSeerrSeason) -> some View {
        if season.enabled && state.selectionEnabled && !state.loading {
            Button { onSeason(season.number) } label: {
                Label(SeerrCopy.season(season), systemImage: season.selected ? "checkmark.circle.fill" : "circle")
            }
        } else {
            Label(SeerrCopy.season(season), systemImage: season.selected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(appearance.secondaryText).focusable()
        }
    }

    private func qualityChoice(fourK: Bool, reason: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { onQuality(fourK) } label: {
                Label(SeerrCopy.text(fourK ? "seerr.quality.4k" : "seerr.quality.standard"),
                      systemImage: state.fourK == fourK ? "checkmark.circle.fill" : "circle")
            }
            .focused($focusedID, equals: fourK ? "four-k" : "standard").id(fourK ? "four-k" : "standard")
            .accessibilityAddTraits(state.fourK == fourK ? .isSelected : [])
            if let reason { SeerrMessage(key: reason) }
        }
    }

    private var quota: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(SeerrCopy.text("seerr.quota.title")).font(.headline)
            if state.quotaKey == "seerr.quota.remaining" {
                Text(String(format: SeerrCopy.text(state.quotaKey), state.quotaRemaining))
            } else { Text(SeerrCopy.text(state.quotaKey)) }
            if state.quotaDays > 0 { Text(String(format: SeerrCopy.text("seerr.quota.days"), state.quotaDays)) }
        }
    }
}

private struct SeerrBackdropView: View {
    let url: String?
    @Environment(\.tvAppearance) private var appearance
    private var trustedURL: URL? {
        guard let url, let value = URL(string: url), value.scheme == "https", value.host == "image.tmdb.org",
              value.user == nil, value.password == nil, value.port == nil, value.path.hasPrefix("/t/p/") else { return nil }
        return value
    }
    var body: some View {
        GeometryReader { geometry in
            AsyncImage(url: trustedURL) { image in image.resizable().scaledToFill() } placeholder: { appearance.surface }
                .frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }
        .accessibilityHidden(true)
    }
}

struct SeerrConfirmationView: View {
    let confirmation: TvSeerrConfirmation
    let onSubmit: () -> Void
    let onCancel: () -> Void
    @Environment(\.tvAppearance) private var appearance
    @FocusState private var cancelFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                Text(SeerrCopy.text("seerr.confirm.title")).font(.largeTitle.bold())
                Text(confirmation.title).font(.title)
                Text(SeerrCopy.text(confirmation.typeKey)).font(.title3)
                Text(SeerrCopy.text(confirmation.qualityKey)).font(.title3)
                if !confirmation.seasons.isEmpty {
                    Text(SeerrCopy.text("seerr.confirm.seasons")).font(.headline)
                    ForEach(confirmation.seasons, id: \.number) { Text(SeerrCopy.season($0)) }
                }
                SeerrMessage(key: confirmation.requiresApproval ? "seerr.confirm.approval" : "seerr.confirm.autoapproval")
                if confirmation.submitting {
                    ProgressView(SeerrCopy.text("seerr.request.submitting"))
                    SeerrMessage(key: "seerr.request.wait")
                }
                HStack(spacing: 40) {
                    Button(SeerrCopy.text("seerr.cancel"), action: onCancel).focused($cancelFocused)
                    Button(SeerrCopy.text("seerr.request.submit"), action: onSubmit)
                }
                .disabled(confirmation.submitting)
            }
            .padding(TvDimensions.screenInset)
            .frame(maxWidth: 1100, alignment: .leading)
        }
        .foregroundStyle(appearance.primaryText)
        .background(appearance.background.ignoresSafeArea())
        .tint(appearance.accent)
        .interactiveDismissDisabled(confirmation.submitting)
        .onExitCommand { if !confirmation.submitting { onCancel() } }
        .onAppear { cancelFocused = true }
    }
}
