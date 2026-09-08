// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SettingsSegmentSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section(String(localized: "Skip Segments")) {
            segmentPicker(String(localized: "Intros"), type: .intro, current: model.state.preferences.introSkip)
            segmentPicker(String(localized: "Outros"), type: .outro, current: model.state.preferences.outroSkip)
            segmentPicker(String(localized: "Recaps"), type: .recap, current: model.state.preferences.recapSkip)
            segmentPicker(String(localized: "Previews"), type: .preview, current: model.state.preferences.previewSkip)
            segmentPicker(String(localized: "Commercials"), type: .commercial, current: model.state.preferences.commercialSkip)
        }
    }

    private func segmentPicker(
        _ title: String,
        type: MediaSegmentType,
        current: SegmentSkipPolicy
    ) -> some View {
        Picker(
            title,
            selection: Binding(
                get: { current.name },
                set: { model.setSegmentPolicy(type: type, policyName: $0) }
            )
        ) {
            Text(String(localized: "Skip automatically")).tag("AutoSkip")
            Text(String(localized: "Ask")).tag("Ask")
            Text(String(localized: "Do nothing")).tag("Ignore")
        }
        .pickerStyle(.navigationLink)
        .disabled(model.state.isLoading)
    }
}
