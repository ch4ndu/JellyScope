// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct PlayerHost: View {
    @ObservedObject var model: PlaybackModel
    let interactionsEnabled: Bool

    var body: some View {
        Group {
            if let player = model.avPlayer, let state = model.onlineState {
                AvPlayerHost(
                    state: state,
                    player: player,
                    identity: model.playerIdentity,
                    interactionsEnabled: interactionsEnabled,
                    model: model
                )
            } else if let surface = model.vlcSurface {
                VlcPlayerHost(
                    surface: surface,
                    identity: model.playerIdentity,
                    interactionsEnabled: interactionsEnabled
                )
            } else {
                Color.black
            }
        }
        .background(.black)
        .allowsHitTesting(interactionsEnabled)
        .accessibilityHidden(!interactionsEnabled)
    }
}
