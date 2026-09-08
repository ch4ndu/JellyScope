// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

enum SearchLabels {
    static let genres: [TvSearchGenre] = [.comedy, .drama, .action, .thriller, .family]
    static let runtimeBuckets: [RuntimeBucket] = [.any, .under30, .under60, .under120]
    static let watchedFilters: [WatchedFilter] = [.any, .unwatched, .inprogress]
    static let resultCategories: [TvSearchResultCategory] = [.all, .movies, .shows, .episodes]

    static func genre(_ value: TvSearchGenre) -> String {
        switch value.name {
        case "Comedy": String(localized: "Comedy")
        case "Drama": String(localized: "Drama")
        case "Action": String(localized: "Action")
        case "Thriller": String(localized: "Thriller")
        default: String(localized: "Family")
        }
    }

    static func runtime(_ value: RuntimeBucket) -> String {
        switch value.name {
        case "Under30": String(localized: "Under 30 min")
        case "Under60": String(localized: "Under 60 min")
        case "Under120": String(localized: "Under 120 min")
        default: String(localized: "Any Runtime")
        }
    }

    static func watched(_ value: WatchedFilter) -> String {
        switch value.name {
        case "Unwatched": String(localized: "Unwatched")
        case "InProgress": String(localized: "In Progress")
        default: String(localized: "Any Watch Status")
        }
    }

    static func resultCategory(_ value: TvSearchResultCategory) -> String {
        switch value.name {
        case "Movies": String(localized: "Movies")
        case "Shows": String(localized: "TV Shows")
        case "Episodes": String(localized: "Episodes")
        default: String(localized: "All")
        }
    }
}
