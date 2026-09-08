// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DiscoveredServersView: View {
    let state: TvLoginState
    let onSelect: (TvDiscoveredServer) -> Void
    let onRetry: () -> Void

    var body: some View {
        Section(String(localized: "Nearby Servers")) {
            if state.isDiscovering {
                ProgressView(String(localized: "Looking for servers…"))
            }

            ForEach(state.discoveredServers, id: \.id) { server in
                Button {
                    onSelect(server)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(server.name)
                        Text(server.address)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            if let error = state.discoveryError {
                Label(error.loginMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                Button(String(localized: "Retry"), action: onRetry)
            } else if !state.isDiscovering {
                if state.discoveredServers.isEmpty {
                    Text(String(localized: "No nearby servers found."))
                        .foregroundStyle(.secondary)
                }
                Button(String(localized: "Search Again"), action: onRetry)
            }
        }
    }
}
