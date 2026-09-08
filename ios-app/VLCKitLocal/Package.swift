// swift-tools-version:5.9
// SPDX-License-Identifier: MPL-2.0
import PackageDescription

// Wraps the downloaded VLCKit XCFramework. Run scripts/fetch-vlckit.sh first.
let package = Package(
    name: "VLCKitLocal",
    platforms: [.iOS(.v16), .tvOS(.v17)],
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
