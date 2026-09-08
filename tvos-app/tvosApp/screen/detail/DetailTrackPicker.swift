// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

enum DetailTrackPickerKind {
    case audio
    case subtitles
}

struct DetailTrackPicker: View {
    let kind: DetailTrackPickerKind
    let tracks: [TvDetailTrackChoice]
    let subtitleMode: TvPlaybackSubtitleMode
    let onSelect: (Int32) -> Void
    let onSelectOff: () -> Void
    var subtitleContext: DetailSubtitleContext? = nil
    var onSelectLocalSubtitle: ((String?) -> Void)? = nil
    @State private var showsLocalSubtitles = false

    @Environment(\.dismiss) private var dismiss
    @Environment(\.tvAppearance) private var appearance

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 26) {
                Text(kind == .audio ? String(localized: "Audio") : String(localized: "Subtitles"))
                    .font(.title.bold())

                if kind == .subtitles {
                    Button {
                        onSelectOff()
                        dismiss()
                    } label: {
                        Label(
                            String(localized: "Off"),
                            systemImage: subtitleMode == .off ? "checkmark.circle.fill" : "circle"
                        )
                    }
                }

                if kind == .subtitles, subtitleContext != nil {
                    Button { showsLocalSubtitles = true } label: {
                        Label(
                            String(localized: "Downloaded Subtitles and Search"),
                            systemImage: subtitleContext?.selectedAssetId != nil ? "checkmark.circle.fill" : "magnifyingglass"
                        )
                    }
                }

                ForEach(tracks, id: \.streamIndex) { track in
                    Button {
                        onSelect(track.streamIndex)
                        dismiss()
                    } label: {
                        Label(track.label, systemImage: track.selected ? "checkmark.circle.fill" : "circle")
                    }
                }
            }
            .padding(64)
        }
        .frame(minWidth: 720, minHeight: 460, alignment: .topLeading)
        .background(appearance.background)
        .sheet(isPresented: $showsLocalSubtitles) {
            if let context = subtitleContext {
                SubtitlesView(
                    session: context.session,
                    itemId: context.itemId,
                    mediaSourceId: context.mediaSourceId
                ) { assetId in
                    onSelectLocalSubtitle?(assetId)
                    showsLocalSubtitles = false
                    dismiss()
                }
                .id(context.mediaSourceId)
            }
        }
    }
}

struct DetailSubtitleContext {
    let session: Session
    let itemId: String
    let mediaSourceId: String
    let selectedAssetId: String?
}
