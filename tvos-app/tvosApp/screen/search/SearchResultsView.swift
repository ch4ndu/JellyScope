// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchResultsView: View {
    let state: TvSearchState
    let scrollProxy: ScrollViewProxy
    let onPlay: (TvMediaCard) -> Void

    @FocusedValue(\.searchResultsFocusOwner) private var focusedResultsOwner
    @FocusState private var focusedTarget: SearchFocusTarget?
    @State private var resultsOwnFocus = false
    @State private var lastFocusedTarget: SearchFocusTarget?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTarget: SearchFocusTarget?
    @State private var restoreTask: Task<Void, Never>?
    @State private var departureTask: Task<Void, Never>?
    @State private var restorePending = false
    @State private var restoreOnReentry = false
    @State private var isVisible = false

    var body: some View {
        VStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
            ForEach(sections, id: \.id) { section in
                SearchRibbonView(
                    rowId: section.id,
                    title: section.title,
                    cards: section.cards,
                    style: section.style,
                    focus: $focusedTarget,
                    restoreTarget: restoreTarget,
                    onPlay: onPlay
                )
            }
        }
        .onAppear {
            isVisible = true
            if restoreOnReentry {
                restoreOnReentry = false
                restorePending = true
                restoreFocus()
            }
        }
        .onChange(of: topology) { _, _ in
            guard isVisible, resultsOwnFocus || restorePending else {
                return
            }
            if focusedResultsOwner == true,
               let focusedTarget,
               isValid(focusedTarget) {
                return
            }
            restorePending = true
            restoreFocus()
        }
        .onChange(of: focusedTarget) { _, target in
            guard let target, let section = section(id: target.rowId) else {
                return
            }
            lastFocusedTarget = target
            lastFocusedIndex = section.cards.firstIndex(where: { $0.id == target.itemId })
        }
        .onChange(of: focusedResultsOwner) { _, owner in
            departureTask?.cancel()
            if owner == true {
                resultsOwnFocus = true
                restorePending = false
                restoreTarget = nil
            } else if resultsOwnFocus {
                departureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedResultsOwner != true,
                          !restorePending else {
                        return
                    }
                    resultsOwnFocus = false
                    restoreTask?.cancel()
                    restoreTarget = nil
                }
            }
        }
        .onDisappear {
            restoreOnReentry = focusedResultsOwner == true || resultsOwnFocus
            resultsOwnFocus = false
            isVisible = false
            restoreTask?.cancel()
            departureTask?.cancel()
            restorePending = false
            restoreTarget = nil
        }
    }

    private var sections: [SearchResultSection] {
        state.visibleResultSections.map { section in
            SearchResultSection(
                id: section.category.name.lowercased(),
                title: SearchLabels.resultCategory(section.category),
                cards: section.cards,
                style: section.category == .episodes ? .landscape : .poster
            )
        }
    }

    private var topology: String {
        sections.map { section in
            "\(section.id):\(section.cards.map(\.id).joined(separator: ","))"
        }.joined(separator: "|")
    }

    private func section(id: String) -> SearchResultSection? {
        sections.first(where: { $0.id == id })
    }

    private func isValid(_ target: SearchFocusTarget) -> Bool {
        section(id: target.rowId)?.cards.contains(where: { $0.id == target.itemId }) == true
    }

    private var resolvedTarget: SearchFocusTarget? {
        if let lastFocusedTarget, isValid(lastFocusedTarget) {
            return lastFocusedTarget
        }
        if let lastFocusedTarget,
           let sameSection = section(id: lastFocusedTarget.rowId),
           !sameSection.cards.isEmpty {
            let index = min(lastFocusedIndex ?? 0, sameSection.cards.count - 1)
            return SearchFocusTarget(rowId: sameSection.id, itemId: sameSection.cards[index].id)
        }
        guard let section = sections.first, let card = section.cards.first else {
            return nil
        }
        return SearchFocusTarget(rowId: section.id, itemId: card.id)
    }

    private func restoreFocus() {
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            for _ in 0..<3 {
                await Task.yield()
                guard !Task.isCancelled, isVisible, let target = resolvedTarget else {
                    return
                }
                scrollProxy.scrollTo(target.rowId, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else {
                    return
                }
                if resolvedTarget == target, isValid(target) {
                    restoreTarget = target
                    return
                }
            }
        }
    }
}

private struct SearchResultSection {
    let id: String
    let title: String
    let cards: [TvMediaCard]
    let style: MediaCardStyle
}
