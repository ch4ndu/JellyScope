// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

private struct DetailPersonFocusTarget: Hashable {
    let section: String
    let personId: String
}

private struct DetailPeopleFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

private extension FocusedValues {
    var detailPeopleFocusOwner: Bool? {
        get { self[DetailPeopleFocusOwnerKey.self] }
        set { self[DetailPeopleFocusOwnerKey.self] = newValue }
    }
}

struct DetailPeopleView: View {
    let cast: [TvDetailPerson]
    let crew: [TvDetailPerson]

    @Environment(\.tvAppearance) private var appearance
    @FocusedValue(\.detailPeopleFocusOwner) private var focusedPeopleOwner
    @FocusState private var focusedTarget: DetailPersonFocusTarget?
    @State private var peopleOwnFocus = false
    @State private var lastFocusedTarget: DetailPersonFocusTarget?
    @State private var lastFocusedIndex: Int?
    @State private var restoreTask: Task<Void, Never>?
    @State private var focusDepartureTask: Task<Void, Never>?
    @State private var focusRestorePending = false
    @State private var isVisible = false

    var body: some View {
        ScrollViewReader { proxy in
            VStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                if !cast.isEmpty {
                    peopleRow(title: String(localized: "Cast"), section: "cast", people: cast)
                }
                if !crew.isEmpty {
                    peopleRow(title: String(localized: "Crew"), section: "crew", people: crew)
                }
            }
            .onChange(of: topology) { _, _ in
                guard isVisible, peopleOwnFocus || focusRestorePending else { return }
                if focusedPeopleOwner == true,
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
            guard let target else { return }
            lastFocusedTarget = target
            lastFocusedIndex = people(in: target.section).firstIndex(where: { $0.id == target.personId })
        }
        .onChange(of: focusedPeopleOwner) { _, owner in
            focusDepartureTask?.cancel()
            if owner == true {
                peopleOwnFocus = true
                focusRestorePending = false
            } else if peopleOwnFocus {
                focusDepartureTask = Task { @MainActor in
                    await Task.yield()
                    guard !Task.isCancelled,
                          focusedPeopleOwner != true,
                          !focusRestorePending
                    else { return }
                    peopleOwnFocus = false
                    restoreTask?.cancel()
                }
            }
        }
    }

    private func peopleRow(title: String, section: String, people: [TvDetailPerson]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, TvDimensions.screenInset)

            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: TvDimensions.ribbonCardSpacing) {
                    ForEach(people, id: \.id) { person in
                        let target = DetailPersonFocusTarget(section: section, personId: person.id)
                        NavigationLink(value: PersonRoute(personId: person.id)) {
                            VStack(alignment: .leading, spacing: 8) {
                                RemoteImage(url: person.imageUrl, contentMode: .fill, fallbackTitle: person.name)
                                    .frame(
                                        width: appearance.cards.peopleWidth,
                                        height: appearance.cards.peopleWidth * 1.5
                                    )
                                    .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))
                                Text(person.name)
                                    .font(.callout.weight(.semibold))
                                    .lineLimit(1)
                                Text(person.credits.map(\.displayTitle).joined(separator: ", "))
                                    .font(.caption)
                                    .foregroundStyle(appearance.secondaryText)
                                    .lineLimit(1)
                            }
                            .frame(width: appearance.cards.peopleWidth, alignment: .leading)
                        }
                        .buttonStyle(.card)
                        .id(target)
                        .focused($focusedTarget, equals: target)
                        .focusedValue(\.detailPeopleFocusOwner, true)
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
        "cast:\(cast.map(\.id).joined(separator: ","))|crew:\(crew.map(\.id).joined(separator: ","))"
    }

    private func people(in section: String) -> [TvDetailPerson] {
        section == "cast" ? cast : crew
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

    private var restoredTarget: DetailPersonFocusTarget? {
        if let lastFocusedTarget, isValid(lastFocusedTarget) {
            return lastFocusedTarget
        }
        guard let lastFocusedTarget else { return nil }
        let available = people(in: lastFocusedTarget.section)
        if !available.isEmpty {
            let index = min(lastFocusedIndex ?? 0, available.count - 1)
            return DetailPersonFocusTarget(section: lastFocusedTarget.section, personId: available[index].id)
        }
        if let person = cast.first {
            return DetailPersonFocusTarget(section: "cast", personId: person.id)
        }
        if let person = crew.first {
            return DetailPersonFocusTarget(section: "crew", personId: person.id)
        }
        return nil
    }

    private func isValid(_ target: DetailPersonFocusTarget) -> Bool {
        people(in: target.section).contains(where: { $0.id == target.personId })
    }
}

private extension TvDetailPersonCredit {
    var displayTitle: String {
        role ?? type?.displayTitle ?? ""
    }
}
