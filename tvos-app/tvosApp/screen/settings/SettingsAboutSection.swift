// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsAboutSection: View {
    var body: some View {
        Section(String(localized: "About")) {
            LabeledContent(
                String(localized: "App Version"),
                value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                    ?? String(localized: "Unknown")
            )
            NavigationLink(String(localized: "Source & Open-Source Notices")) {
                OpenSourceNoticesView(sourceRevision: TvosEntry.shared.sourceRevision())
            }
        }
    }
}
