// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadsView: View {
    let session: Session
    let onPlay: (String, Bool) -> Void

    @StateObject private var model: DownloadsModel
    @State private var showingStorage = false
    @FocusState private var focusedDownloadId: String?
    @State private var lastFocusedDownloadId: String?
    @State private var lastFocusedIndex: Int?
    @State private var restoreFocusPending = false

    init(
        session: Session,
        onPlay: @escaping (String, Bool) -> Void
    ) {
        self.session = session
        self.onPlay = onPlay
        _model = StateObject(wrappedValue: DownloadsModel(session: session))
    }

    var body: some View {
        ScrollViewReader { proxy in
            Group {
                if model.state.isLoading && model.state.sections.isEmpty {
                    ProgressView(String(localized: "Loading downloads…"))
                } else {
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 38) {
                            storageSection
                            interruptionControls

                            if model.state.sections.isEmpty {
                                ContentUnavailableView(
                                    String(localized: "No downloads"),
                                    systemImage: "arrow.down.circle",
                                    description: Text(String(localized: "Download a movie or episode from its detail screen."))
                                )
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 80)
                            }

                            ForEach(model.state.sections, id: \.kind.name) { section in
                                DownloadSectionView(
                                    session: session,
                                    section: section,
                                    inFlightDownloadId: model.state.inFlightDownloadId,
                                    focusedDownloadId: $focusedDownloadId,
                                    onPlay: { onPlay($0.id, false) }
                                )
                            }

                            if let error = model.state.error {
                                Label(
                                    error == .queuewakerejected
                                        ? String(localized: "Queued downloads could not be resumed. Please try again.")
                                        : error.message,
                                    systemImage: "exclamationmark.triangle"
                                )
                                .foregroundStyle(.orange)
                            }

                            VStack(alignment: .leading, spacing: 8) {
                                Text(String(localized: "Apple TV may reclaim downloaded media when it needs space. Missing copies can be downloaded again."))
                                Text(String(localized: "Downloads transfer only while JellyScope is active."))
                            }
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.bottom, TvDimensions.screenBottomInset)
                        }
                        .padding(.horizontal, TvDimensions.screenInset)
                        .padding(.top, 28)
                    }
                    .onAppear { restoreFocus(using: proxy) }
                    .onChange(of: rowIdentity) { _, _ in
                        guard restoreFocusPending else { return }
                        restoreFocus(using: proxy)
                    }
                    .onChange(of: restoreFocusPending) { _, pending in
                        guard pending else { return }
                        restoreFocus(using: proxy)
                    }
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
        .sheet(isPresented: $showingStorage) {
            DownloadSettingsView(session: session)
        }
        .navigationDestination(for: DownloadDetailRoute.self) { route in
            DownloadDetailView(
                session: session,
                downloadId: route.downloadId,
                model: model,
                onPlay: onPlay,
                onReturn: requestFocusRestoration
            )
        }
        .onChange(of: focusedDownloadId) { _, downloadId in
            guard let downloadId,
                  let index = rows.firstIndex(where: { $0.id == downloadId })
            else { return }
            lastFocusedDownloadId = downloadId
            lastFocusedIndex = index
        }
    }

    private var storageSection: some View {
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

    @ViewBuilder
    private var interruptionControls: some View {
        if model.state.hasPausedDownloads || model.state.hasQueuedDownloads {
            HStack(spacing: 20) {
                if model.state.hasPausedDownloads {
                    Button(action: model.resumeAll) {
                        Label(String(localized: "Resume All Paused Downloads"), systemImage: "play.fill")
                    }
                    .disabled(
                        model.state.isBulkResumeInFlight ||
                        model.state.isQueueWakeInFlight ||
                        model.state.inFlightDownloadId != nil
                    )
                }
                if model.state.hasQueuedDownloads {
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
        }
    }

    private var rows: [TvDownloadRow] {
        model.state.sections.flatMap(\.rows)
    }

    private var rowIdentity: String {
        rows.map { "\($0.id)|\($0.attemptGeneration)" }.joined(separator: ",")
    }

    private func requestFocusRestoration() {
        restoreFocusPending = true
    }

    private func restoreFocus(using proxy: ScrollViewProxy) {
        guard restoreFocusPending || lastFocusedDownloadId != nil else { return }
        guard let target = restoredDownloadId else {
            restoreFocusPending = false
            return
        }
        restoreFocusPending = false
        Task { @MainActor in
            await Task.yield()
            guard rows.contains(where: { $0.id == target }) else { return }
            proxy.scrollTo(target, anchor: .center)
            await Task.yield()
            focusedDownloadId = target
        }
    }

    private var restoredDownloadId: String? {
        if let lastFocusedDownloadId,
           rows.contains(where: { $0.id == lastFocusedDownloadId }) {
            return lastFocusedDownloadId
        }
        guard let lastFocusedIndex, !rows.isEmpty else { return rows.first?.id }
        return rows[min(lastFocusedIndex, rows.count - 1)].id
    }
}
