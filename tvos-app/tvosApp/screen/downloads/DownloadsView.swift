// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadsView: View {
    let session: Session
    let onPlay: (String, Bool) -> Void

    @StateObject private var model: DownloadsModel
    @State private var selectedDownload: SelectedDownload?
    @State private var showingStorage = false

    init(
        session: Session,
        onPlay: @escaping (String, Bool) -> Void
    ) {
        self.session = session
        self.onPlay = onPlay
        _model = StateObject(wrappedValue: DownloadsModel(session: session))
    }

    var body: some View {
        Group {
            if model.state.isLoading && model.state.sections.isEmpty {
                ProgressView(String(localized: "Loading downloads…"))
            } else {
                List {
                    storageSection

                    if model.state.sections.isEmpty {
                        Section {
                            ContentUnavailableView(
                                String(localized: "No downloads"),
                                systemImage: "arrow.down.circle",
                                description: Text(String(localized: "Download a movie or episode from its detail screen."))
                            )
                        }
                    }

                    if model.state.hasPausedDownloads {
                        Section {
                            Button(action: model.resumeAll) {
                                Label(String(localized: "Resume All Paused Downloads"), systemImage: "play.fill")
                            }
                            .disabled(
                                model.state.isBulkResumeInFlight ||
                                model.state.isQueueWakeInFlight ||
                                model.state.inFlightDownloadId != nil
                            )
                        }
                    }

                    if model.state.hasQueuedDownloads {
                        Section {
                            Button(action: model.resumeQueuedDownloads) {
                                if model.state.isQueueWakeInFlight {
                                    ProgressView(String(localized: "Requesting queued downloads…"))
                                } else {
                                    Label(String(localized: "Resume Queued Downloads"), systemImage: "play.circle")
                                }
                            }
                            .disabled(
                                model.state.isQueueWakeInFlight ||
                                model.state.isBulkResumeInFlight ||
                                model.state.inFlightDownloadId != nil
                            )
                        }
                    }

                    ForEach(model.state.sections, id: \.kind.name) { section in
                        DownloadSectionView(
                            section: section,
                            inFlightDownloadId: model.state.inFlightDownloadId,
                            onSelect: { selectedDownload = SelectedDownload(row: $0) },
                            onPlay: { onPlay($0.id, false) }
                        )
                    }

                    if let error = model.state.error {
                        Section {
                            Label(
                                error == .queuewakerejected
                                    ? String(localized: "Queued downloads could not be resumed. Please try again.")
                                    : error.message,
                                systemImage: "exclamationmark.triangle"
                            )
                                .foregroundStyle(.orange)
                        }
                    }

                    Section {
                        Text(String(localized: "Apple TV may reclaim downloaded media when it needs space. Missing copies can be downloaded again."))
                        Text(String(localized: "Downloads transfer only while JellyScope is active."))
                    }
                    .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(String(localized: "Downloads"))
        .toolbar {
            ToolbarItem {
                Button(action: model.refresh) {
                    Label(String(localized: "Refresh"), systemImage: "arrow.clockwise")
                }
            }
        }
        .sheet(item: $selectedDownload) { selection in
            DownloadActionsView(
                row: selection.row,
                model: model,
                onPlay: { restart in
                    onPlay(selection.row.id, restart)
                    selectedDownload = nil
                }
            )
        }
        .sheet(isPresented: $showingStorage) {
            DownloadSettingsView(session: session)
        }
    }

    private var storageSection: some View {
        Section {
            Button {
                showingStorage = true
            } label: {
                HStack {
                    Label(String(localized: "Download Storage"), systemImage: "internaldrive")
                    Spacer()
                    if let storage = model.state.storage {
                        Text(DownloadLabels.bytes(storage.projectedCommittedBytes))
                            .foregroundStyle(.secondary)
                    } else if model.state.isRefreshingStorage {
                        ProgressView()
                    }
                }
            }
        }
    }
}

private struct SelectedDownload: Identifiable {
    let row: TvDownloadRow
    var id: String { row.id }
}
