// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import UIKit

/// Artwork loader with an in-memory cache. Jellyfin image requests carry the
/// auth header (SwiftUI's AsyncImage cannot send headers), so cache entries
/// are keyed by server/user scope and the whole cache purges whenever the
/// session boundary changes — a signed-out account's artwork must never leak
/// into the next session.
final class ImageFetcher: @unchecked Sendable {
    static let shared = ImageFetcher()

    private let cache = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private var scopeKey = ""
    private var authHeader: String?

    private init() {
        cache.countLimit = 400
    }

    func configure(scopeKey: String, authHeader: String?) {
        lock.lock()
        defer { lock.unlock() }
        if scopeKey != self.scopeKey {
            cache.removeAllObjects()
        }
        self.scopeKey = scopeKey
        self.authHeader = authHeader
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        cache.removeAllObjects()
        scopeKey = ""
        authHeader = nil
    }

    func image(for url: String) async -> UIImage? {
        lock.lock()
        let key = "\(scopeKey)|\(url)" as NSString
        let header = authHeader
        lock.unlock()
        if let hit = cache.object(forKey: key) {
            return hit
        }
        guard let requestUrl = URL(string: url) else {
            return nil
        }
        var request = URLRequest(url: requestUrl)
        if let header {
            request.setValue(header, forHTTPHeaderField: "Authorization")
        }
        guard
            let (data, _) = try? await URLSession.shared.data(for: request),
            let image = UIImage(data: data)
        else {
            return nil
        }
        cache.setObject(image, forKey: key)
        return image
    }
}

/// Focus-friendly remote artwork view backed by [ImageFetcher].
struct RemoteImage: View {
    let url: String?

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle()
                .fill(Color.secondary.opacity(0.2))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .clipped()
        .task(id: url) {
            image = nil
            guard let url else { return }
            image = await ImageFetcher.shared.image(for: url)
        }
    }
}
