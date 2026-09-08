// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private struct DetailRelatedFocusTarget: Hashable {
    let groupId: String
    let itemId: String
}

private struct DetailRelatedFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

private extension FocusedValues {
    var detailRelatedFocusOwner: Bool? {
        get { self[DetailRelatedFocusOwnerKey.self] }
        set { self[DetailRelatedFocusOwnerKey.self] = newValue }
    }
}

struct DetailRelatedView: View {
    let groups: [TvRelatedGroup]
    let onPlay: (PlaybackRoute) -> Void

    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.detailRelatedFocusOwner) private var focusedRelatedOwner
    @FocusState private var focusedTarget: DetailRelatedFocusTarget?
    @State private var relatedOwnsFocus = false
    @State private var lastFocusedTarget: DetailRelatedFocusTarget?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    var body: some View {
        ScrollViewReader { proxy in
            VStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                ForEach(groups, id: \.groupId) { group in
                    relatedRow(group)
                }
            }
            .onChange(of: topology) { _, _ in
                guard isVisible, relatedOwnsFocus || focusRestorePending else { return }
                if focusedRelatedOwner == true,
                   let focusedTarget,
                   isValid(focusedTarget) { return }
                focusRestorePending = true
                restoreFocus(using: proxy)
            }
            .onAppear {
                isVisible = true
                if lastFocusedTarget != nil {
                    focusRestorePending = true
                    restoreFocus(using: proxy)
                }
            }
            .onDisappear {
                isVisible = false
                restoreTask?.cancel()
                focusDepartureTask?.cancel()
                focusRestorePending = false
            }
        }
        .onChange(of: focusedTarget) { _, target in
            guard let target, let group = group(id: target.groupId) else { return }
            lastFocusedTarget = target
            lastFocusedIndex = group.items.firstIndex(where: { $0.id == target.itemId })
        }
        .onChange(of: focusedRelatedOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                relatedOwnsFocus = true
                focusRestorePending = false
            } else if relatedOwnsFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedRelatedOwner != true,
                          !focusRestorePending
                    else { return }
                    relatedOwnsFocus = false
                    restoreTask?.cancel()
                }
            }
        }
    }

    private func relatedRow(_ group: TvRelatedGroup) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(group.displayTitle)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, TvDimensions.screenInset)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                    ForEach(group.items, id: \.id) { card in
                        let target = DetailRelatedFocusTarget(groupId: group.groupId, itemId: card.id)
                        MediaCardView(
                            card: card,
                            width: appearance.cards.posterWidth,
                            style: .poster,
                            onPlay: { selected in
                                guard selected.isDirectlyPlayable else { return }
                                onPlay(PlaybackRoute(itemId: selected.id, mediaSourceId: nil, startPositionTicks: selected.playbackPositionTicks))
                            }
                        )
                        .id(target)
                        .focused($focusedTarget, equals: target)
                        .focusedValue(\.detailRelatedFocusOwner, true)
                    }
                }
                .padding(.horizontal, TvDimensions.screenInset)
                .padding(.vertical, TvDimensions.ribbonFocusReserve)
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }

    private var topology: String {
        groups.map { group in "\(group.groupId):\(group.items.map(\.id).joined(separator: ","))" }.joined(separator: "|")
    }

    private func group(id: String) -> TvRelatedGroup? {
        groups.first(where: { $0.groupId == id })
    }

    private func restoreFocus(using proxy: ScrollViewProxy) {
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            for _ in 0..<3 {
                await Task.yield()
                guard !Task.isCancelled, isVisible, let target = restoredTarget else { return }
                proxy.scrollTo(target, anchor: .center)
                await Task.yield()
                guard !Task.isCancelled, isVisible else { return }
                if restoredTarget == target, isValid(target) {
                    focusedTarget = target
                    return
                }
            }
        }
    }

    private var restoredTarget: DetailRelatedFocusTarget? {
        if let lastFocusedTarget, isValid(lastFocusedTarget) {
            return lastFocusedTarget
        }
        guard let lastFocusedTarget else { return nil }
        if let sameGroup = group(id: lastFocusedTarget.groupId), !sameGroup.items.isEmpty {
            let index = min(lastFocusedIndex ?? 0, sameGroup.items.count - 1)
            return DetailRelatedFocusTarget(groupId: sameGroup.groupId, itemId: sameGroup.items[index].id)
        }
        guard let group = groups.first(where: { !$0.items.isEmpty }), let card = group.items.first else { return nil }
        return DetailRelatedFocusTarget(groupId: group.groupId, itemId: card.id)
    }

    private func isValid(_ target: DetailRelatedFocusTarget) -> Bool {
        group(id: target.groupId)?.items.contains(where: { $0.id == target.itemId }) == true
    }
}
