// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension TvErrorKind {
    var loginMessage: String {
        switch self {
        case .invalidurl:
            return String(localized: "That server address is not valid.")
        case .notreachable:
            return String(localized: "The Jellyfin server is not reachable.")
        case .invalidcredentials:
            return String(localized: "Wrong username or password.")
        case .quickconnectexpired:
            return String(localized: "The Quick Connect code expired.")
        case .quickconnectunavailable:
            return String(localized: "Quick Connect is disabled on this server.")
        case .server:
            return String(localized: "The server returned an error.")
        case .network:
            return String(localized: "A network error occurred.")
        default:
            return String(localized: "Something went wrong.")
        }
    }
}
