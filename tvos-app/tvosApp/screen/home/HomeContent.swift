// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct HomeContent: View {
    let state: TvHomeState
    let onRetry: (TvHomeRowKind) -> Void
    let onViewAll: (TvHomeRowKind) -> Void
    let onPlay: (TvMediaCard) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.focusedMedia) private var focusedMedia
    @FocusedValue(\.homeFocusOwner) private var focusedHomeOwner
    @FocusState private var focusedTarget: HomeFocusTarget?
    @State private var homeOwnsFocus = false
    @State private var settledCard: TvMediaCard?
    @State private var lastFocusedTarget: HomeFocusTarget?
    @State private var restoreTarget: HomeFocusTarget?
    @State private var heroTask: Task<Void, Never>?
    @State private var focusRestoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    private let terminalStatusId = "home-status"

    var body: some View {
        ZStack(alignment: .top) {
            appearance.background
                .ignoresSafeArea()
            MediaBackdropView(card: heroCard)
                .frame(height: TvDimensions.backdropHeight)
                .ignoresSafeArea(edges: .top)

            VStack(spacing: 0) {
                MediaHeroView(card: heroCard)
                    .frame(height: TvDimensions.heroHeight)

                ScrollViewReader { proxy in
                    ScrollView(.vertical) {
                        VStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                            if showsTerminalStatus {
                                terminalStatus
                                    .id(terminalStatusId)
                            } else {
                                ForEach(visibleRows, id: \.stableId) { row in
                                    HomeRibbonView(
                                        row: row,
                                        focus: $focusedTarget,
                                        restoreTarget: restoreTarget,
                                        onRetry: { onRetry(row.kind) },
                                        onViewAll: { onViewAll(row.kind) },
                                        onPlay: onPlay
                                    )
                                    .id(row.stableId)
                                }
                            }
                        }
                        .padding(.bottom, TvDimensions.screenBottomInset)
                        .focusSection()
                    }
                    .onAppear {
                        isVisible = true
                        focusRestorePending = true
                        requestFocus(using: proxy, preferred: lastFocusedTarget)
                    }
                    .onChange(of: focusTopology) { _, _ in
                        guard isVisible, homeOwnsFocus || focusRestorePending else { return }
                        if focusedHomeOwner == true,
                           let focusedTarget,
                           isValid(focusedTarget) {
                            return
                        }
                        focusRestorePending = true
                        requestFocus(using: proxy, preferred: lastFocusedTarget)
                    }
                }
            }
        }
        .onChange(of: focusedMedia) { _, _ in
            settleFocusedMedia()
        }
        .onChange(of: focusedTarget) { _, target in
            guard let target else { return }
            lastFocusedTarget = target
        }
        .onChange(of: focusedHomeOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                homeOwnsFocus = true
                focusRestorePending = false
            } else if homeOwnsFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedHomeOwner != true,
                          !focusRestorePending
                    else { return }
                    homeOwnsFocus = false
                    focusRestoreTask?.cancel()
                    restoreTarget = nil
                }
            }
        }
        .onDisappear {
            isVisible = false
            heroTask?.cancel()
            focusRestoreTask?.cancel()
            focusDepartureTask?.cancel()
            focusRestorePending = false
            restoreTarget = nil
        }
    }

    private var rows: [TvMediaRow] {
        state.rows
    }

    private var visibleRows: [TvMediaRow] {
        rows.filter { row -> Bool in
            row.status != .empty
        }
    }

    private var showsTerminalStatus: Bool {
        !rows.contains { row in
            row.status == .content || row.status == .loading
        }
    }

    private var heroCard: TvMediaCard? {
        settledCard
    }

    private var focusTopology: String {
        rows.map { row in
            let items = row.items.map(\.id).joined(separator: ",")
            return "\(row.stableId):\(row.status.name):\(items)"
        }.joined(separator: "|")
    }

    private var terminalStatus: some View {
        let hasError = rows.contains { row in row.status == .error }
        return MediaStatusView(
            status: hasError ? .error : .empty,
            title:
                hasError
                    ? String(localized: "Could not load your home screen.")
                    : String(localized: "Your libraries are empty."),
            actionTitle: hasError ? String(localized: "Retry") : nil,
            action: {
                if hasError {
                    rows.filter { row in row.status == .error }.forEach { row in
                        onRetry(row.kind)
                    }
                }
            }
        )
        .focused($focusedTarget, equals: HomeFocusTarget(rowId: terminalStatusId, kind: .status))
        .focusedValue(\.homeFocusOwner, true)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, TvDimensions.screenInset)
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

    private func requestFocus(
        using proxy: ScrollViewProxy,
        preferred: HomeFocusTarget?
    ) {
        focusRestoreTask?.cancel()
        restoreTarget = nil
        focusRestoreTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            var target = resolvedTarget(preferred: preferred)
            proxy.scrollTo(target.rowId, anchor: .top)
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            let latestTarget = resolvedTarget(preferred: preferred)
            if latestTarget.rowId != target.rowId {
                target = latestTarget
                proxy.scrollTo(target.rowId, anchor: .top)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
            } else {
                target = latestTarget
            }
            restoreTarget = target
            if target.rowId == terminalStatusId {
                focusedTarget = target
            }
        }
    }

    private func resolvedTarget(preferred: HomeFocusTarget?) -> HomeFocusTarget {
        if showsTerminalStatus {
            return HomeFocusTarget(rowId: terminalStatusId, kind: .status)
        }

        if let preferred,
           let preferredIndex = rows.firstIndex(where: { row in row.stableId == preferred.rowId }) {
            if let target = target(in: rows[preferredIndex], preferredKind: preferred.kind) {
                return target
            }
            for index in (preferredIndex + 1)..<rows.count {
                if let target = target(in: rows[index], preferredKind: nil) {
                    return target
                }
            }
            for index in 0..<preferredIndex {
                if let target = target(in: rows[index], preferredKind: nil) {
                    return target
                }
            }
        }

        for row in rows {
            if let target = target(in: row, preferredKind: nil) {
                return target
            }
        }
        return HomeFocusTarget(rowId: terminalStatusId, kind: .status)
    }

    private func target(
        in row: TvMediaRow,
        preferredKind: HomeFocusKind?
    ) -> HomeFocusTarget? {
        switch row.status {
        case .content:
            if case let .card(itemId)? = preferredKind,
               row.items.contains(where: { card in card.id == itemId }) {
                return HomeFocusTarget(rowId: row.stableId, kind: .card(itemId))
            }
            if preferredKind == .viewAll {
                return HomeFocusTarget(rowId: row.stableId, kind: .viewAll)
            }
            guard let first = row.items.first else { return nil }
            return HomeFocusTarget(rowId: row.stableId, kind: .card(first.id))
        case .loading:
            return HomeFocusTarget(rowId: row.stableId, kind: .status)
        case .error:
            return HomeFocusTarget(rowId: row.stableId, kind: .retry)
        case .empty:
            return nil
        default:
            return nil
        }
    }

    private func isValid(_ target: HomeFocusTarget) -> Bool {
        if showsTerminalStatus {
            return target.rowId == terminalStatusId
        }
        guard let row = rows.first(where: { row in row.stableId == target.rowId }) else {
            return false
        }
        return self.target(in: row, preferredKind: target.kind) == target
    }
}
