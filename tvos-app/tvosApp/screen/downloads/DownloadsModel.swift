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
        presenter.refresh()
    }

    func configureQuota(wholeGb: Int64) {
        presenter.configureQuota(wholeGb: wholeGb)
    }

    func pause(_ row: TvDownloadRow) {
        presenter.pause(downloadId: row.id)
    }

    func resume(_ row: TvDownloadRow) {
        presenter.resume(downloadId: row.id)
    }

    func resumeAll() {
        presenter.resumeAll()
    }

    func resumeQueuedDownloads() {
        presenter.resumeQueuedDownloads()
    }

    func retry(_ row: TvDownloadRow) {
        presenter.retry(downloadId: row.id)
    }

    func cancel(_ row: TvDownloadRow) {
        presenter.cancel(downloadId: row.id, expectedAttemptGeneration: row.attemptGeneration)
    }

    func delete(_ row: TvDownloadRow) {
        presenter.delete(downloadId: row.id, expectedAttemptGeneration: row.attemptGeneration)
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
