// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct OpenSourceNoticesView: View {
    let sourceRevision: String

    private var sourceURL: URL? {
        URL(string: "https://github.com/ch4ndu/JellyScope/tree/\(sourceRevision)")
    }

    var body: some View {
        List {
            Section(String(localized: "JellyScope")) {
                Text(String(localized: "JellyScope-owned source is licensed under MPL-2.0."))
                LabeledContent(String(localized: "Revision"), value: sourceRevision)
                if let sourceURL {
                    Link(String(localized: "View Source Revision"), destination: sourceURL)
                }
                Text(String(localized: "Development builds may include uncommitted changes not represented by that revision."))
            }
            Section(String(localized: "Third-Party Software")) {
                Text(
                    String(localized: "JellyScope uses separately licensed third-party software. Release packages include the applicable notices, license texts, native manifests, and source routes. Third-party licenses remain unchanged.")
                )
            }
        }
        .navigationTitle(String(localized: "Source & Notices"))
    }
}
