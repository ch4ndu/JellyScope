// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct LibraryRecommendedView: View {
    let state: TvLibraryHubState
    let onRetry: (LibraryRecommendationSection) -> Void
    let onRefresh: () -> Void
    let onPlay: (TvMediaCard) -> Void

    @FocusedValue(\.libraryRecommendationFocusOwner) private var focusedRecommendationOwner
    @FocusState private var focusedTarget: LibraryRecommendationFocusTarget?
    @State private var recommendationOwnsFocus = false
    @State private var lastFocusedTarget: LibraryRecommendationFocusTarget?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTarget: LibraryRecommendationFocusTarget?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    private let terminalStatusId = "library-recommended-empty"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                    if visibleSections.isEmpty {
                        terminalEmpty
                            .id(terminalStatusId)
                    } else {
                        ForEach(visibleSections, id: \.stableId) { section in
                            sectionContent(section)
                                .id(section.stableId)
                        }
                    }
                }
                .padding(.bottom, TvDimensions.screenBottomInset)
            }
            .onAppear {
                isVisible = true
                onRefresh()
                focusRestorePending = true
                requestFocus(using: proxy)
            }
            .onChange(of: topology) { _, _ in
                guard isVisible, recommendationOwnsFocus || focusRestorePending else { return }
                if focusedRecommendationOwner == true,
                   let focusedTarget,
                   isValid(focusedTarget) {
                    return
                }
                focusRestorePending = true
                requestFocus(using: proxy)
            }
            .onDisappear {
                isVisible = false
                restoreTask?.cancel()
                focusDepartureTask?.cancel()
                focusRestorePending = false
                restoreTarget = nil
            }
        }
        .onChange(of: focusedTarget) { _, target in
            guard let target else { return }
            lastFocusedTarget = target
            lastFocusedIndex = cardIndex(for: target)
        }
        .onChange(of: focusedRecommendationOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                recommendationOwnsFocus = true
                focusRestorePending = false
            } else if recommendationOwnsFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedRecommendationOwner != true,
                          !focusRestorePending
                    else { return }
                    recommendationOwnsFocus = false
                    restoreTask?.cancel()
                    restoreTarget = nil
                }
            }
        }
    }

    private var visibleSections: [TvLibraryRecommendationSectionState] {
        state.recommendationSections.filter { section in section.status != .empty }
    }

    @ViewBuilder
    private func sectionContent(_ section: TvLibraryRecommendationSectionState) -> some View {
        switch section.status {
        case .loading:
            sectionStatus(
                section,
                status: .loading,
                title: String(localized: "Loading…"),
                actionTitle: nil,
                action: {}
            )
        case .error:
            sectionStatus(
                section,
                status: .error,
                title: String(localized: "Could not load this row."),
                actionTitle: String(localized: "Retry"),
                action: { onRetry(section.section) }
            )
        case .content:
            ForEach(section.rows, id: \.stableId) { row in
                LibraryRecommendationRibbon(
                    row: row,
                    focus: $focusedTarget,
                    restoreTarget: restoreTarget,
                    onPlay: onPlay
                )
                .id(row.stableId)
            }
        case .empty:
            EmptyView()
        default:
            EmptyView()
        }
    }

    private func sectionStatus(
        _ section: TvLibraryRecommendationSectionState,
        status: MediaStatus,
        title: String,
        actionTitle: String?,
        action: @escaping () -> Void
    ) -> some View {
        let kind: LibraryRecommendationFocusKind = status == .error ? .retry : .status
        let target = LibraryRecommendationFocusTarget(rowId: section.stableId, kind: kind)
        return VStack(alignment: .leading, spacing: 12) {
            Text(section.section.libraryDisplayTitle)
                .font(.title3.weight(.semibold))
            MediaStatusView(
                status: status,
                title: title,
                actionTitle: actionTitle,
                action: action
            )
            .focused($focusedTarget, equals: target)
            .focusedValue(\.libraryRecommendationFocusOwner, true)
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .focusSection()
    }

    private var terminalEmpty: some View {
        let target = LibraryRecommendationFocusTarget(rowId: terminalStatusId, kind: .status)
        return MediaStatusView(
            status: .empty,
            title: String(localized: "No recommendations found.")
        )
        .focused($focusedTarget, equals: target)
        .focusedValue(\.libraryRecommendationFocusOwner, true)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, TvDimensions.screenInset)
    }

    private var topology: String {
        state.recommendationSections.map { section in
            let rows = section.rows.map { row in
                "\(row.stableId):\(row.items.map(\.id).joined(separator: ","))"
            }.joined(separator: ";")
            return "\(section.stableId):\(section.status.name):\(rows)"
        }.joined(separator: "|")
    }

    private func requestFocus(using proxy: ScrollViewProxy) {
        restoreTask?.cancel()
        restoreTarget = nil
        restoreTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            var target = resolvedTarget()
            proxy.scrollTo(target.rowId, anchor: .center)
            await Task.yield()
            guard !Task.isCancelled, isVisible else { return }
            let latestTarget = resolvedTarget()
            if latestTarget.rowId != target.rowId {
                target = latestTarget
                proxy.scrollTo(target.rowId, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
            } else {
                target = latestTarget
            }
            let validatedTarget = resolvedTarget()
            guard isValid(validatedTarget) else { return }
            if validatedTarget.rowId != target.rowId {
                proxy.scrollTo(validatedTarget.rowId, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled,
                      isVisible,
                      isValid(validatedTarget)
                else { return }
            }
            restoreTarget = validatedTarget
            if case .card = validatedTarget.kind {
                return
            }
            focusedTarget = validatedTarget
        }
    }

    private func resolvedTarget() -> LibraryRecommendationFocusTarget {
        if let lastFocusedTarget {
            if isValid(lastFocusedTarget) {
                return lastFocusedTarget
            }
            if case .card = lastFocusedTarget.kind,
               let row = row(with: lastFocusedTarget.rowId),
               let card = row.items[safe: lastFocusedIndex ?? 0] ?? row.items.first {
                return LibraryRecommendationFocusTarget(
                    rowId: row.stableId,
                    kind: .card(card.id)
                )
            }
        }
        for section in visibleSections {
            switch section.status {
            case .loading:
                return LibraryRecommendationFocusTarget(rowId: section.stableId, kind: .status)
            case .error:
                return LibraryRecommendationFocusTarget(rowId: section.stableId, kind: .retry)
            case .content:
                for row in section.rows {
                    if let card = row.items.first {
                        return LibraryRecommendationFocusTarget(rowId: row.stableId, kind: .card(card.id))
                    }
                }
            default:
                continue
            }
        }
        return LibraryRecommendationFocusTarget(rowId: terminalStatusId, kind: .status)
    }

    private func isValid(_ target: LibraryRecommendationFocusTarget?) -> Bool {
        guard let target else { return false }
        if visibleSections.isEmpty {
            return target.rowId == terminalStatusId && target.kind == .status
        }
        if let section = visibleSections.first(where: { $0.stableId == target.rowId }) {
            switch target.kind {
            case .status: return section.status == .loading
            case .retry: return section.status == .error
            case .card: return false
            }
        }
        guard let row = row(with: target.rowId) else { return false }
        if case let .card(itemId) = target.kind {
            return row.items.contains(where: { $0.id == itemId })
        }
        return false
    }

    private func row(with stableId: String) -> TvLibraryRecommendationRow? {
        for section in visibleSections where section.status == .content {
            if let row = section.rows.first(where: { $0.stableId == stableId }) {
                return row
            }
        }
        return nil
    }

    private func cardIndex(for target: LibraryRecommendationFocusTarget) -> Int? {
        guard case let .card(itemId) = target.kind,
              let row = row(with: target.rowId)
        else { return nil }
        return row.items.firstIndex(where: { $0.id == itemId })
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
