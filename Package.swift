// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorStepSensor",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorStepSensor",
            targets: ["StepSensorPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "StepSensorPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/StepSensorPlugin"),
        .testTarget(
            name: "StepSensorPluginTests",
            dependencies: ["StepSensorPlugin"],
            path: "ios/Tests/StepSensorPluginTests")
    ]
)
