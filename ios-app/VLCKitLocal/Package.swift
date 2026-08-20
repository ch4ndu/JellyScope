// swift-tools-version:5.9
// SPDX-License-Identifier: MPL-2.0
import PackageDescription

// Local SPM package wrapping the vendored VLCKit binary for the iOS
// dual-player backend. The .xcframework itself is NOT committed — run
// `scripts/fetch-vlckit.sh` first to populate `ios-app/Frameworks/`.
// Add this package to the app via Xcode: File > Add Package Dependencies >
// Add Local... > select this `VLCKitLocal` folder, then link the
// `VLCKitLocal` product to the iosApp target.
let package = Package(
    name: "VLCKitLocal",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "VLCKitLocal", targets: ["VLCKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "VLCKit",
            path: "../Frameworks/VLCKit.xcframework"
        ),
    ]
)
