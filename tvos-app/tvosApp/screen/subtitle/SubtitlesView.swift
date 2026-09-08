// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct SubtitlesView: View {
    @StateObject private var model: SubtitlesModel

    init(
        session: Session,
        itemId: String,
        mediaSourceId: String?,
        onSelection: @escaping (String?) -> Void
    ) {
        _model = StateObject(
            wrappedValue: SubtitlesModel(
                session: session,
                itemId: itemId,
                mediaSourceId: mediaSourceId,
                onSelection: onSelection
            )
        )
    }

    var body: some View {
        content
            .navigationTitle(String(localized: "Subtitles"))
            .onDisappear(perform: model.close)
    }

    @ViewBuilder
    private var content: some View {
        switch model.state.availability {
        case .loading:
            ProgressView(String(localized: "Loading subtitles…"))
        case .available:
            subtitleList
        case .unsupported:
            ContentUnavailableView(
                String(localized: "Subtitles unavailable"),
                systemImage: "captions.bubble",
                description: Text(String(localized: "OpenSubtitles search is available for movies and episodes with an online media source."))
            )
        case .failed:
            ContentUnavailableView {
                Label(String(localized: "Subtitles could not be loaded"), systemImage: "exclamationmark.triangle")
            } actions: {
                Button(String(localized: "Retry"), action: model.reload)
            }
        default:
            EmptyView()
        }
    }

    private var subtitleList: some View {
        List {
            Section {
                Button {
                    model.select(nil)
                } label: {
                    Label(String(localized: "Off"), systemImage: "captions.bubble.fill")
                }
                .disabled(model.state.isSelecting)

                if model.state.selectionError {
                    Label(
                        String(localized: "The subtitle selection could not be saved."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)
                }
            }

            Section(String(localized: "Downloaded Subtitles")) {
                if model.state.localSubtitles.isEmpty {
                    Text(String(localized: "No downloaded subtitles."))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.state.localSubtitles, id: \.id) { subtitle in
                        LocalSubtitleRow(
                            subtitle: subtitle,
                            selecting: model.state.isSelecting,
                            deleting: model.state.deletingAssetId == subtitle.id,
                            syncing: model.state.syncingAssetId == subtitle.id,
                            deleteFailed: model.state.deleteErrorAssetId == subtitle.id,
                            syncFailed: model.state.syncErrorAssetId == subtitle.id,
                            onSelect: { model.select(subtitle.id) },
                            onDelete: { model.delete(subtitle.id) },
                            onRetrySync: { model.retrySync(subtitle.id) }
                        )
                    }
                }
            }

            SubtitleSearchView(model: model)
        }
    }
}
