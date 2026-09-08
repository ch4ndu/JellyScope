// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailVersionPicker: View {
    let versions: [TvDetailVersionChoice]
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            Text(String(localized: "Choose Version"))
                .font(.title.bold())

            ForEach(versions, id: \.id) { version in
                Button {
                    onSelect(version.id)
                    dismiss()
                } label: {
                    Label(version.label, systemImage: version.selected ? "checkmark.circle.fill" : "circle")
                }
            }
        }
        .padding(64)
        .frame(minWidth: 720, minHeight: 420, alignment: .topLeading)
        .background(appearance.background)
    }
}
