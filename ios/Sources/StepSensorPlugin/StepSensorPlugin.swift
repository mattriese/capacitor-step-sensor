import Foundation
import Capacitor

@objc(StepSensorPlugin)
public class StepSensorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "StepSensorPlugin"
    public let jsName = "StepSensor"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "scheduleStepTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startStepTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopStepTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getTrackedSteps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "configureFitnessNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearFitnessNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFitnessNotificationDebugState", returnType: CAPPluginReturnPromise)
    ]

    @objc func scheduleStepTracking(_ call: CAPPluginCall) {
        // No-op on iOS — HealthKit handles step tracking natively
        call.resolve()
    }

    @objc func startStepTracking(_ call: CAPPluginCall) {
        // No-op on iOS
        call.resolve()
    }

    @objc func stopStepTracking(_ call: CAPPluginCall) {
        // No-op on iOS
        call.resolve()
    }

    @objc func getTrackedSteps(_ call: CAPPluginCall) {
        // Return empty array — iOS step data comes through the health plugin's getChanges() pipeline
        call.resolve(["steps": [], "syncToken": ISO8601DateFormatter().string(from: Date())])
    }

    @objc func configureFitnessNotifications(_ call: CAPPluginCall) {
        // No-op on iOS for now — native ownership will come through the HealthKit plugin path
        call.resolve()
    }

    @objc func clearFitnessNotifications(_ call: CAPPluginCall) {
        // No-op on iOS for now
        call.resolve()
    }

    @objc func getFitnessNotificationDebugState(_ call: CAPPluginCall) {
        call.resolve([
            "generatedAt": NSNull(),
            "commitments": [],
            "scheduledReminders": []
        ])
    }
}
