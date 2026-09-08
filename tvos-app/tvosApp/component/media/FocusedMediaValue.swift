// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct FocusedMediaValue: Equatable {
    let rowId: String
    let card: TvMediaCard

    var identity: String {
        "\(rowId):\(card.id)"
    }

    static func == (lhs: FocusedMediaValue, rhs: FocusedMediaValue) -> Bool {
        lhs.rowId == rhs.rowId && lhs.card == rhs.card
    }
}

private struct FocusedMediaValueKey: FocusedValueKey {
    typealias Value = FocusedMediaValue
}

private struct HomeFocusOwnerKey: FocusedValueKey {
    typealias Value = Bool
}

extension FocusedValues {
    var focusedMedia: FocusedMediaValue? {
        get { self[FocusedMediaValueKey.self] }
        set { self[FocusedMediaValueKey.self] = newValue }
    }

    var homeFocusOwner: Bool? {
        get { self[HomeFocusOwnerKey.self] }
        set { self[HomeFocusOwnerKey.self] = newValue }
    }
}

enum HomeFocusKind: Hashable {
    case card(String)
    case viewAll
    case retry
    case status
}

struct HomeFocusTarget: Hashable {
    let rowId: String
    let kind: HomeFocusKind
}
