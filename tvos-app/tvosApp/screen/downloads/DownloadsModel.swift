// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class DownloadsModel: ObservableObject {
    @Published private(set) var state: TvDownloadsState
    private let presenter: TvDownloadsPresenter
    private var handle: WatchHandle?
    private var closed = false

    init(session: Session) {
        let presenter = TvosEntry.shared.downloadsPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvDownloadsState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func refresh() {
        presenter.accept(intent: TvDownloadsIntentRefresh.shared)
    }

    func configureQuota(wholeGb: Int64) {
        presenter.accept(intent: TvDownloadsIntentConfigureQuota(wholeGb: wholeGb))
    }

    func pause(_ row: TvDownloadRow) {
        presenter.accept(intent: TvDownloadsIntentPause(downloadId: row.id))
    }

    func resume(_ row: TvDownloadRow) {
        presenter.accept(intent: TvDownloadsIntentResume(downloadId: row.id))
    }

    func resumeAll() {
        presenter.accept(intent: TvDownloadsIntentResumeAll.shared)
    }

    func resumeQueuedDownloads() {
        presenter.accept(intent: TvDownloadsIntentResumeQueuedDownloads.shared)
    }

    func retry(_ row: TvDownloadRow) {
        presenter.accept(intent: TvDownloadsIntentRetry(downloadId: row.id))
    }

    func cancel(_ row: TvDownloadRow) {
        presenter.accept(intent: TvDownloadsIntentCancel(downloadId: row.id, expectedAttemptGeneration: row.attemptGeneration))
    }

    func delete(_ row: TvDownloadRow) {
        presenter.accept(intent: TvDownloadsIntentDelete(downloadId: row.id, expectedAttemptGeneration: row.attemptGeneration))
    }

    func row(downloadId: String) -> TvDownloadRow? {
        state.sections
            .flatMap(\.rows)
            .first { $0.id == downloadId }
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        handle = nil
        presenter.close()
    }

    deinit {
        if !closed {
            handle?.close()
            presenter.close()
        }
    }
}
