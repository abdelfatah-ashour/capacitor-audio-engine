// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CaapcitorNativeAudio",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "CaapcitorNativeAudio",
            targets: ["CapacitorAudioEngine"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0")
    ],
    targets: [
        .target(
            name: "CapacitorAudioEngine",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CapacitorAudioEngine"),
        .testTarget(
            name: "NativeAudioPluginTests",
            dependencies: ["CapacitorAudioEngine"],
            path: "ios/Tests/NativeAudioPluginTests")
    ]
)
