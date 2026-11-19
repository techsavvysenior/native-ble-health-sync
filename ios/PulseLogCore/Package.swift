// swift-tools-version: 6.2
import PackageDescription

// Plain Swift, no CoreBluetooth: parsers, reconnect state machine, reading ids and the upload queue.
// Tested with `swift test` against the shared vectors in ../../spec, the same files the Kotlin core uses.
let package = Package(
    name: "PulseLogCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PulseLogCore", targets: ["PulseLogCore"]),
    ],
    targets: [
        .target(name: "PulseLogCore"),
        .testTarget(name: "PulseLogCoreTests", dependencies: ["PulseLogCore"]),
    ]
)
