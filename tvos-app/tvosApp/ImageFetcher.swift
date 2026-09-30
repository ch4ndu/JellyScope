// SPDX-License-Identifier: MPL-2.0

import SwiftUI
import UIKit
import ImageIO

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

    func image(for url: String, pixelSize: CGSize? = nil) async -> UIImage? {
        let variant = pixelSize.map { "\(Int($0.width))x\(Int($0.height))" } ?? "original"
        lock.lock()
        let key = "\(scopeKey)|\(url)|\(variant)" as NSString
        let header = authHeader
        lock.unlock()
        if let hit = cache.object(forKey: key) {
            return hit
        }
        guard var components = URLComponents(string: url) else {
            return nil
        }
        if let pixelSize, let items = components.queryItems {
            components.queryItems = items.map { item in
                guard item.name.lowercased() == "maxwidth" else { return item }
                // Reduced quality only lowers the server width; it never asks for more than High.
                let reducedWidth = Int(pixelSize.width)
                let highWidth = item.value.flatMap { Int($0) } ?? reducedWidth
                return URLQueryItem(name: item.name, value: String(min(reducedWidth, highWidth)))
            }
        }
        guard let requestUrl = components.url else { return nil }
        var request = URLRequest(url: requestUrl)
        if let header {
            request.setValue(header, forHTTPHeaderField: "Authorization")
        }
        guard
            let (data, _) = try? await URLSession.shared.data(for: request),
            let image = decodeArtwork(data, pixelSize: pixelSize)
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
    let contentMode: ContentMode
    let fallbackTitle: String?

    @EnvironmentObject private var appearanceModel: TvAppearanceModel
    @Environment(\.displayScale) private var displayScale
    @State private var bounds: CGSize = .zero
    @State private var image: UIImage?

    init(
        url: String?,
        contentMode: ContentMode = .fill,
        fallbackTitle: String? = nil
    ) {
        self.url = url
        self.contentMode = contentMode
        self.fallbackTitle = fallbackTitle
    }

    private var pixelSize: CGSize? {
        artworkPixelSize(bounds: bounds, displayScale: displayScale, decodeScale: CGFloat(appearanceModel.state.imageQuality.decodeScale))
    }

    var body: some View {
        ZStack {
            if image == nil {
                if let fallbackTitle {
                    Text(fallbackTitle)
                        .font(.largeTitle.bold())
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                } else {
                    Rectangle()
                        .fill(Color.secondary.opacity(0.2))
                }
            }
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            }
        }
        .clipped()
        .onGeometryChange(for: CGSize.self) { $0.size } action: { bounds = $0 }
        .task(id: "\(url ?? "")|\(appearanceModel.state.imageQuality.name)|\(pixelSize?.width ?? 0)x\(pixelSize?.height ?? 0)") {
            image = nil
            guard let url else { return }
            if appearanceModel.state.imageQuality.name != "High", pixelSize == nil { return }
            let loadedImage = await ImageFetcher.shared.image(for: url, pixelSize: pixelSize)
            guard !Task.isCancelled, self.url == url else { return }
            image = loadedImage
        }
    }
}

// Reduced-quality requests use measured pixels; High preserves the existing decode.
func artworkPixelSize(bounds: CGSize, displayScale: CGFloat, decodeScale: CGFloat) -> CGSize? {
    guard decodeScale < 1, bounds.width > 0, bounds.height > 0 else { return nil }
    return CGSize(width: max(1, (bounds.width * displayScale * decodeScale).rounded()),
                  height: max(1, (bounds.height * displayScale * decodeScale).rounded()))
}

func decodeArtwork(_ data: Data, pixelSize: CGSize?) -> UIImage? {
    guard let pixelSize else { return UIImage(data: data) }
    guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
          let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { return nil }
    let sourceWidth = CGFloat(width.doubleValue)
    let sourceHeight = CGFloat(height.doubleValue)
    guard sourceWidth > 0, sourceHeight > 0 else { return nil }
    let ratio = min(1, max(pixelSize.width / sourceWidth, pixelSize.height / sourceHeight))
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: max(1, Int(ceil(max(sourceWidth, sourceHeight) * ratio)))
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    return UIImage(cgImage: image)
}
