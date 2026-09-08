// SPDX-License-Identifier: MPL-2.0

import SwiftUI

struct PlayerStillWatchingView: View {
    let onContinue: () -> Void
    let onStop: () -> Void
    @FocusState private var focusedAction: StillWatchingAction?

    var body: some View {
        VStack(spacing: 20) {
            Text("Are you still watching?")
                .font(.title.bold())
            HStack(spacing: 18) {
                Button("Continue Watching", action: onContinue)
                    .focused($focusedAction, equals: .continueWatching)
                Button("Stop Watching", action: onStop)
                    .focused($focusedAction, equals: .stopWatching)
            }
        }
        .padding(40)
        .background(.black.opacity(0.94), in: RoundedRectangle(cornerRadius: 20))
        .onAppear { focusedAction = .continueWatching }
    }
}

private enum StillWatchingAction: Hashable {
    case continueWatching
    case stopWatching
}
