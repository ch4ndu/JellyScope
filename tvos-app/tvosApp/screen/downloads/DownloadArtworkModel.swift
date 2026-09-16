// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv
import UIKit

@MainActor
final class DownloadArtworkModel: ObservableObject {
    @Published private(set) var image: UIImage?

    let identity: String
    private let presenter: TvDownloadArtworkPresenter
    private var handle: WatchHandle?
    private var decodeTask: Task<Void, Never>?
    private var latestReadGeneration: Int64 = -1
    private var closed = false
    private var active = true

    init(session: Session, row: TvDownloadRow, role: OfflineArtworkRole) {
        identity = "\(row.id)|\(row.attemptGeneration)|\(role.name)|\(row.state.name)|\(row.presentationBytes)"
        let presenter = TvosEntry.shared.downloadArtworkPresenter(
            session: session,
            downloadId: row.id,
            attemptGeneration: row.attemptGeneration,
            role: role
        )
        self.presenter = presenter
        self.handle = presenter.watchState { [weak self] state in
            self?.consume(state)
        }
    }

    func activate() {
        guard !closed, !active else { return }
        active = true
        presenter.load()
    }

    func deactivate() {
        guard !closed, active else { return }
        active = false
        decodeTask?.cancel()
        decodeTask = nil
        image = nil
        presenter.cancel()
    }

    func close() {
        guard !closed else { return }
        deactivate()
        closed = true
        handle?.close()
        handle = nil
        presenter.close()
    }

    private func consume(_ state: TvDownloadArtworkState) {
        guard active, !closed else { return }
        let readGeneration = state.readGeneration
        latestReadGeneration = readGeneration
        decodeTask?.cancel()
        image = nil
        guard let bytes = state.imageBytes else { return }
        let identity = identity
        decodeTask = Task { [weak self] in
            let decoded = await Task.detached(priority: .userInitiated) {
                UIImage(data: Self.data(from: bytes))
            }.value
            guard !Task.isCancelled,
                  let self,
                  self.active,
                  !self.closed,
                  self.identity == identity,
                  self.latestReadGeneration == readGeneration
            else { return }
            self.image = decoded
        }
    }

    nonisolated private static func data(from bytes: KotlinByteArray) -> Data {
        Data(
            (0 ..< Int(bytes.size)).map { index in
                UInt8(bitPattern: bytes.get(index: Int32(index)))
            }
        )
    }

    deinit {
        if !closed {
            decodeTask?.cancel()
            handle?.close()
            presenter.close()
        }
    }
}
