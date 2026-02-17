import Foundation

@objc public class StepSensor: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
