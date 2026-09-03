// swift-tools-version:5.9
import PackageDescription

// The chain-handling core of the iOS wallet, kept as a library so it can be
// built and tested on a macOS CI runner without Xcode, signing or a device.
let package = Package(
    name: "IXcoinKit",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "IXcoinKit", targets: ["IXcoinKit"])],
    targets: [
        .target(name: "IXcoinKit"),
        .testTarget(
            name: "IXcoinKitTests",
            dependencies: ["IXcoinKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
