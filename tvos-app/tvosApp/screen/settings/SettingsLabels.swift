// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension TvAccountError {
    var settingsMessage: String {
        switch self {
        case .accountnotfound:
            return String(localized: "That account is no longer available.")
        case .removalconfirmationrequired:
            return String(localized: "Confirm removal of the retained downloads before removing this account.")
        case .removalconfirmationstale:
            return String(localized: "The account changed before it could be removed. Try again.")
        case .artifactinuse:
            return String(localized: "This account is still in use and could not be removed.")
        case .server:
            return String(localized: "The account operation could not be completed.")
        default:
            return String(localized: "The account operation could not be completed.")
        }
    }
}
