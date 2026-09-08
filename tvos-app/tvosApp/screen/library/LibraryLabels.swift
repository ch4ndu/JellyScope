// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

extension LibraryInnerView {
    var libraryDisplayTitle: String {
        switch self {
        case .recommended: String(localized: "Recommended")
        case .library: String(localized: "Library")
        default: ""
        }
    }
}

extension LibraryRecommendationSection {
    var libraryDisplayTitle: String {
        switch self {
        case .continuewatching: String(localized: "Continue Watching")
        case .recentlyadded: String(localized: "Recently Added")
        case .nextup: String(localized: "Next Up")
        case .movierecommendations: String(localized: "Recommended")
        default: String(localized: "Recommended")
        }
    }
}

extension TvLibraryRecommendationRow {
    var libraryDisplayTitle: String {
        guard section == .movierecommendations,
              let baseline = baselineItemName?.trimmingCharacters(in: .whitespacesAndNewlines),
              !baseline.isEmpty,
              let reason
        else {
            return section.libraryDisplayTitle
        }
        switch reason {
        case .similartorecentlyplayed:
            return String(format: String(localized: "Because you watched %@"), baseline)
        case .similartolikeditem:
            return String(format: String(localized: "Because you liked %@"), baseline)
        case .hasdirector:
            return String(format: String(localized: "Directed by %@"), baseline)
        case .hasactor:
            return String(format: String(localized: "Starring %@"), baseline)
        default:
            return section.libraryDisplayTitle
        }
    }
}

extension LibrarySortBy {
    var libraryDisplayTitle: String {
        switch self {
        case .name: String(localized: "Name")
        case .datecreated: String(localized: "Date Added")
        case .premieredate: String(localized: "Premiere Date")
        case .communityrating: String(localized: "Community Rating")
        case .runtime: String(localized: "Runtime")
        case .videobitrate: String(localized: "Bitrate")
        case .datelastcontentadded: String(localized: "Last Episode Added")
        default: name
        }
    }
}

extension LibrarySortOrder {
    var libraryDisplayTitle: String {
        switch self {
        case .ascending: String(localized: "Ascending")
        case .descending: String(localized: "Descending")
        default: name
        }
    }
}

extension TvLibraryFilterGroupKind {
    var libraryDisplayTitle: String {
        switch self {
        case .watched: String(localized: "Filters")
        case .genres: String(localized: "Genres")
        case .years: String(localized: "Years")
        case .ratings: String(localized: "Ratings")
        case .studios: String(localized: "Studios")
        case .tags: String(localized: "Tags")
        case .seriesstatus: String(localized: "Series Status")
        case .features: String(localized: "Features")
        default: ""
        }
    }
}

extension TvLibraryFilterOption {
    var libraryDisplayTitle: String {
        if let label, !label.isEmpty {
            return label
        }
        return switch kind {
        case .played: String(localized: "Played")
        case .unplayed: String(localized: "Unplayed")
        case .resumable: String(localized: "In Progress")
        case .favorite: String(localized: "Favorite")
        case .continuing: String(localized: "Continuing")
        case .ended: String(localized: "Ended")
        case .unreleased: String(localized: "Unreleased")
        case .hassubtitles: String(localized: "Has Subtitles")
        case .hastrailer: String(localized: "Has Trailer")
        case .hasspecialfeature: String(localized: "Has Special Feature")
        default: ""
        }
    }
}
