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
    @Environment(\.tvAppearance) private var appearance
    @FocusState private var focusedAction: Action?
    @State private var rememberedAction: Action = .back
    private enum Action: Hashable { case back, library, request }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 36) {
                HStack(spacing: 24) {
                    Button(SeerrCopy.text("seerr.detail.back"), action: onBack)
                        .focused($focusedAction, equals: .back)
                    Button(SeerrCopy.text("seerr.refresh"), action: onRefresh).disabled(state.loading)
                }
                .focusSection()

                HStack(alignment: .top, spacing: 48) {
                    SeerrPosterView(url: state.card.posterUrl, width: appearance.cards.detailPosterWidth)
                    VStack(alignment: .leading, spacing: 22) {
                        Text(state.card.title ?? SeerrCopy.text("seerr.title.unknown"))
                            .font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
                        HStack {
                            Text(SeerrCopy.text(state.card.typeKey))
                            Text(state.card.year)
                        }
                        .font(.title3).foregroundStyle(appearance.secondaryText)
                        SeerrAvailabilityView(standardKey: state.card.standardKey, fourKKey: state.card.fourKKey)
                        if let overview = state.overview, !overview.isEmpty {
                            Text(overview).font(.body).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if state.loading { ProgressView(SeerrCopy.text("seerr.loading")) }
                if let error = state.errorKey { SeerrMessage(key: error, isError: true) }
                if let notice = state.noticeKey { SeerrMessage(key: notice) }

                VStack(alignment: .leading, spacing: 18) {
                    Text(SeerrCopy.text("seerr.request.history")).font(.title2.bold())
                    if state.card.requests.isEmpty && !state.loading {
                        SeerrMessage(key: "seerr.request.none")
                    } else {
                        ForEach(state.card.requests, id: \.id) { SeerrRequestStatusView(request: $0) }
                    }
                }

                VStack(alignment: .leading, spacing: 24) {
                    Text(SeerrCopy.text("seerr.quality.choose")).font(.title2.bold())
                    HStack(alignment: .top, spacing: 40) {
                        qualityChoice(fourK: false, reason: state.standardReasonKey)
                        qualityChoice(fourK: true, reason: state.fourKReasonKey)
                    }
                    .disabled(state.loading || !state.selectionEnabled)
                    .focusSection()
                    quota
                    if !state.seasons.isEmpty {
                        Text(SeerrCopy.text("seerr.seasons.choose")).font(.title2.bold())
                        LazyVStack(alignment: .leading, spacing: 24) {
                            ForEach(state.seasons, id: \.number) { season in
                                VStack(alignment: .leading, spacing: 10) {
                                    Button { onSeason(season.number) } label: {
                                        Label(SeerrCopy.season(season), systemImage: season.selected ? "checkmark.circle.fill" : "circle")
                                    }
                                    .disabled(!season.enabled || state.loading || !state.selectionEnabled)
                                    .accessibilityAddTraits(season.selected ? .isSelected : [])
                                    if let reason = season.reasonKey { SeerrMessage(key: reason) }
                                }
                            }
                        }
                    }
                    if let reason = state.requestReasonKey { SeerrMessage(key: reason) }
                    if state.reconciliationRequired {
                        Button(SeerrCopy.text("seerr.reconcile"), action: onReconcile).disabled(state.loading)
                    } else {
                        Button(SeerrCopy.text("seerr.request.review"), action: onRequest)
                            .disabled(!state.canRequest || state.loading)
                            .focused($focusedAction, equals: .request)
                    }
                    Button(SeerrCopy.text("seerr.library.open"), action: onLibrary)
                        .disabled(state.loading || state.resolvingLibrary)
                        .focused($focusedAction, equals: .library)
                    if state.resolvingLibrary { ProgressView(SeerrCopy.text("seerr.library.checking")) }
                }
            }
            .padding(TvDimensions.ribbonFocusReserve)
            .padding(.bottom, TvDimensions.screenBottomInset)
        }
        .background(appearance.background)
        .onAppear { focusedAction = rememberedAction }
        .onChange(of: focusedAction) { _, action in if let action { rememberedAction = action } }
    }

    private func qualityChoice(fourK: Bool, reason: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { onQuality(fourK) } label: {
                Label(SeerrCopy.text(fourK ? "seerr.quality.4k" : "seerr.quality.standard"),
                      systemImage: state.fourK == fourK ? "checkmark.circle.fill" : "circle")
            }
            .accessibilityAddTraits(state.fourK == fourK ? .isSelected : [])
            if let reason { SeerrMessage(key: reason) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var quota: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(SeerrCopy.text("seerr.quota.title")).font(.headline)
            if state.quotaKey == "seerr.quota.remaining" {
                Text(String(format: SeerrCopy.text(state.quotaKey), state.quotaRemaining))
            } else {
                Text(SeerrCopy.text(state.quotaKey))
            }
            if state.quotaDays > 0 {
                Text(String(format: SeerrCopy.text("seerr.quota.days"), state.quotaDays))
            }
        }
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
