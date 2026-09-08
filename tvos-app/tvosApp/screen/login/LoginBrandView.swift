// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct LoginBrandView: View {
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "play.tv.fill")
                .font(.system(size: 58))
                .foregroundStyle(appearance.accent)
            Text(String(localized: "JellyScope"))
                .font(.largeTitle.weight(.semibold))
        }
        .accessibilityElement(children: .combine)
    }
}
