// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DetailMediaInfoView: View {
    let rows: [TvDetailMediaInfoRow]

    @Environment(\.dismiss) private var dismiss
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text(String(localized: "Media Info"))
                        .font(.title.bold())
                    Spacer()
                    Button(String(localized: "Close"), action: dismiss.callAsFunction)
                }

                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 6) {
                        if let streamLabel = row.streamLabel {
                            Text(streamLabel)
                                .font(.caption)
                                .foregroundStyle(appearance.secondaryText)
                        }
                        LabeledContent(row.field.displayTitle, value: row.displayValue)
                            .font(.body)
                    }
                    .padding(.vertical, 5)
                }
            }
            .padding(64)
        }
        .frame(minWidth: 820, minHeight: 540)
        .background(appearance.background)
    }
}
