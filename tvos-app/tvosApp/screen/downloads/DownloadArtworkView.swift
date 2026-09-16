// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadArtworkView: View {
    let session: Session
    let row: TvDownloadRow
    let role: OfflineArtworkRole
    let contentMode: ContentMode
    let fallbackTitle: String?

    init(
        session: Session,
        row: TvDownloadRow,
        role: OfflineArtworkRole,
        contentMode: ContentMode = .fill,
        fallbackTitle: String? = nil
    ) {
        self.session = session
        self.row = row
        self.role = role
        self.contentMode = contentMode
        self.fallbackTitle = fallbackTitle
    }

    var body: some View {
        DownloadArtworkContent(
            session: session,
            row: row,
            role: role,
            contentMode: contentMode,
            fallbackTitle: fallbackTitle
        )
        .id("\(row.id)|\(row.attemptGeneration)|\(role.name)|\(row.state.name)|\(row.presentationBytes)")
    }
}

private struct DownloadArtworkContent: View {
    let contentMode: ContentMode
    let fallbackTitle: String?
    let role: OfflineArtworkRole
    @StateObject private var model: DownloadArtworkModel

    init(
        session: Session,
        row: TvDownloadRow,
        role: OfflineArtworkRole,
        contentMode: ContentMode,
        fallbackTitle: String?
    ) {
        self.contentMode = contentMode
        self.fallbackTitle = fallbackTitle
        self.role = role
        _model = StateObject(wrappedValue: DownloadArtworkModel(session: session, row: row, role: role))
    }

    var body: some View {
        Group {
            if role == .logo {
                if let image = model.image {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: TvDimensions.heroLogoWidth, height: TvDimensions.heroLogoHeight, alignment: .leading)
                } else if let fallbackTitle {
                    Text(fallbackTitle)
                        .font(.largeTitle.bold())
                        .lineLimit(2)
                }
            } else {
                ZStack {
                    Rectangle().fill(role == .poster ? Color.secondary.opacity(0.2) : Color.clear)
                    if role == .poster, let fallbackTitle, model.image == nil {
                        Text(fallbackTitle).font(.title2.bold()).lineLimit(2).padding()
                    }
                    if let image = model.image {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                    }
                }
                .clipped()
            }
        }
        .onAppear(perform: model.activate)
        .onDisappear(perform: model.deactivate)
    }
}
