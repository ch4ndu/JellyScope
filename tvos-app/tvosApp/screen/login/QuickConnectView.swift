// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct QuickConnectView: View {
    let code: String?
    let error: TvErrorKind?
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text(String(localized: "Quick Connect"))
                .font(.title2.weight(.semibold))

            if let code {
                Text(code)
                    .font(.system(size: 64, weight: .bold, design: .monospaced))
                    .accessibilityLabel(
                        String(format: String(localized: "Quick Connect code %@"), code)
                    )
                Text(String(localized: "Enter this code in the Jellyfin app or web client under Settings → Quick Connect."))
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            } else if let error {
                Label(error.loginMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                Button(String(localized: "Retry Quick Connect"), action: onRetry)
            } else {
                ProgressView(String(localized: "Requesting a Quick Connect code…"))
            }
        }
        .frame(maxWidth: 700)
    }
}
