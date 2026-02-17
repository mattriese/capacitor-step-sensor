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
        CAPPluginMethod(name: "getTrackedSteps", returnType: CAPPluginReturnPromise)
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
        call.resolve(["steps": []])
    }
}
