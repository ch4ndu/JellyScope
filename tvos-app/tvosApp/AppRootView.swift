// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import SharedTv

struct AppRootView: View {
    @StateObject private var sessionModel = SessionModel()
    @StateObject private var appearanceModel = TvAppearanceModel()

    var body: some View {
        Group {
            if sessionModel.state.phase == .restoring {
                ProgressView()
            } else if let session = sessionModel.state.session, sessionModel.state.phase == .loggedin {
                MainTabView(session: session)
                    .id(sessionScopeKey(session, epoch: sessionModel.state.boundaryEpoch))
            } else {
                LoginFlowView()
            }
        }
        .environment(\.tvAppearance, appearanceModel.appearance)
        .environmentObject(appearanceModel)
        .onChange(of: sessionModel.state) { _, state in
            if let session = state.session, state.phase == .loggedin {
                ImageFetcher.shared.configure(
                    scopeKey: sessionScopeKey(session, epoch: state.boundaryEpoch),
                    authHeader: TvosEntry.shared.imageAuthHeader(session: session)
                )
            } else if state.phase == .loggedout {
                ImageFetcher.shared.reset()
            }
        }
    }

    private func sessionScopeKey(_ session: Session, epoch: Int64) -> String {
        "\(session.serverId)/\(session.userId)/\(epoch)"
    }
}
