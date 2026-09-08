// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct SettingsBrowsingSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section {
            Toggle(String(localized: "Remember Library Tab"), isOn: rememberLibraryTabBinding)
                .disabled(model.state.isLoading)
        } header: {
            Text(String(localized: "Browsing"))
        } footer: {
            Text(String(localized: "Return to the last view used in each library."))
        }
    }

    private var rememberLibraryTabBinding: Binding<Bool> {
        Binding(
            get: { model.state.rememberLastLibraryView },
            set: model.setRememberLastLibraryView
        )
    }
}
