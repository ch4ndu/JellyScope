// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SearchPersonSuggestionsView: View {
    let selectedPerson: TvSearchPerson?
    let suggestions: [TvSearchPerson]
    let onSelect: (TvSearchPerson) -> Void
    let onClear: () -> Void

    var body: some View {
        if let selectedPerson {
            HStack(spacing: 18) {
                Label(
                    String(format: String(localized: "Person: %@"), selectedPerson.name),
                    systemImage: "person.fill"
                )
                Button(String(localized: "Clear Person"), action: onClear)
            }
            .padding(.horizontal, TvDimensions.screenInset)
            .focusSection()
        } else if !suggestions.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "People"))
                    .font(.title3.weight(.semibold))

                ScrollView(.horizontal) {
                    LazyHStack(spacing: 16) {
                        ForEach(suggestions, id: \.id) { person in
                            Button {
                                onSelect(person)
                            } label: {
                                HStack(spacing: 12) {
                                    RemoteImage(url: person.imageUrl, contentMode: .fill, fallbackTitle: person.name)
                                        .frame(width: 72, height: 72)
                                        .clipShape(Circle())
                                    Text(person.name)
                                        .lineLimit(1)
                                }
                                .frame(minWidth: 220, alignment: .leading)
                            }
                        }
                    }
                    .padding(.vertical, TvDimensions.ribbonFocusReserve)
                }
                .scrollClipDisabled()
            }
            .padding(.horizontal, TvDimensions.screenInset)
            .focusSection()
        }
    }
}
