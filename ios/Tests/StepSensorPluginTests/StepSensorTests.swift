import XCTest
@testable import StepSensorPlugin

class StepSensorTests: XCTestCase {
    func testPluginInstantiates() {
        // StepSensor is a no-op on iOS — just verify it can be created
        let implementation = StepSensor()
        XCTAssertNotNil(implementation)
    }
}
