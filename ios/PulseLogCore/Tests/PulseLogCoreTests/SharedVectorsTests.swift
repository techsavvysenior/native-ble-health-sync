import Foundation
import Testing
@testable import PulseLogCore

/// Runs every case in spec/gatt-vectors.json and spec/reconnect-cases.json.
/// The Kotlin core runs the same files, so both apps are held to one definition of correct.
enum Spec {
    static var directory: URL {
        if let override = ProcessInfo.processInfo.environment["PULSELOG_SPEC"] {
            return URL(fileURLWithPath: override)
        }
        // Tests/PulseLogCoreTests/<this file> -> repository root -> spec
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("spec")
    }

    static func load(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent(name))
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

private func value<T>(_ any: Any?) -> T? {
    if any is NSNull { return nil }
    return any as? T
}

struct SharedVectorsTests {
    @Test("UC-03 SFLOAT values decode exactly like the reference")
    func sfloat() throws {
        let cases = try #require(try Spec.load("gatt-vectors.json")["sfloat"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let raw = try #require(c["raw"] as? Int)
            #expect(SFloat.decode(raw) == value(c["value"]) as Double?, "raw \(raw)")
        }
    }

    @Test("UC-02 heart rate vectors")
    func heartRate() throws {
        let cases = try #require(try Spec.load("gatt-vectors.json")["heartRate"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let bytes = try #require([UInt8](hex: c["hex"] as? String ?? "x"), "\(name)")
            if let error = c["error"] as? String {
                #expect(throws: ParseError(rawValue: error)!, "\(name)") { try GattParsers.heartRate(bytes) }
                continue
            }
            let e = try #require(c["expected"] as? [String: Any])
            let hr = try GattParsers.heartRate(bytes)
            #expect(hr.bpm == e["bpm"] as? Int, "\(name)")
            #expect(hr.sensorContact.rawValue == e["sensorContact"] as? String, "\(name)")
            #expect(hr.energyExpendedKj == value(e["energyExpendedKj"]), "\(name)")
            #expect(hr.rr1024 == e["rr1024"] as? [Int], "\(name)")
        }
    }

    @Test("UC-03 blood pressure vectors")
    func bloodPressure() throws {
        let cases = try #require(try Spec.load("gatt-vectors.json")["bloodPressure"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let bytes = try #require([UInt8](hex: c["hex"] as? String ?? "x"), "\(name)")
            if let error = c["error"] as? String {
                #expect(throws: ParseError(rawValue: error)!, "\(name)") { try GattParsers.bloodPressure(bytes) }
                continue
            }
            let e = try #require(c["expected"] as? [String: Any])
            let bp = try GattParsers.bloodPressure(bytes)
            #expect(bp.unit.rawValue == e["unit"] as? String, "\(name)")
            #expect(bp.systolic == e["systolic"] as? Double, "\(name)")
            #expect(bp.diastolic == e["diastolic"] as? Double, "\(name)")
            #expect(bp.meanArterial == value(e["meanArterial"]), "\(name)")
            #expect(bp.pulseRate == value(e["pulseRate"]), "\(name)")
            #expect(bp.userId == value(e["userId"]), "\(name)")
            #expect(bp.status == value(e["status"]), "\(name)")
            #expect(bp.irregularPulse == e["irregularPulse"] as? Bool, "\(name)")
            #expect(bp.bodyMovement == e["bodyMovement"] as? Bool, "\(name)")
            if let ts: [String: Any] = value(e["timestamp"]) {
                func f(_ k: String) -> Int { ts[k] as? Int ?? -1 }
                let expected = DeviceTime(year: f("year"), month: f("month"), day: f("day"), hour: f("hour"), minute: f("minute"), second: f("second"))
                #expect(bp.timestamp == expected, "\(name)")
            } else {
                #expect(bp.timestamp == nil, "\(name)")
            }
        }
    }

    @Test("UC-08 reading ids match the reference")
    func readingIds() throws {
        let cases = try #require(try Spec.load("gatt-vectors.json")["readingIds"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let kind = try #require(ReadingKind(rawValue: c["kind"] as? String ?? ""))
            let id = ReadingIds.of(
                deviceId: c["deviceId"] as? String ?? "",
                kind: kind,
                receivedAtMs: (c["timestampMs"] as? NSNumber)?.int64Value ?? -1,
                payload: [UInt8](hex: c["hex"] as? String ?? "") ?? []
            )
            #expect(id == c["expected"] as? String)
        }
    }

    @Test("UC-09 Firestore documents match the reference field for field")
    func documents() throws {
        let cases = try #require(try Spec.load("gatt-vectors.json")["documents"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let kind = try #require(ReadingKind(rawValue: c["kind"] as? String ?? ""))
            let reading = Reading.from(
                deviceId: c["deviceId"] as? String ?? "",
                kind: kind,
                receivedAtMs: (c["receivedAtMs"] as? NSNumber)?.int64Value ?? -1,
                payload: [UInt8](hex: c["hex"] as? String ?? "") ?? []
            )
            #expect(reading.id == c["id"] as? String, "\(name)")
            var expected = try #require(c["expected"] as? [String: Any])
            expected["platform"] = "ios"
            let document = try reading.document(platform: "ios")
            // NSDictionary equality compares numbers by value and NSNull to NSNull, like Firestore does.
            #expect(NSDictionary(dictionary: document).isEqual(to: expected), "\(name): \(document)")
        }
    }

    @Test("UC-04 to UC-07 reconnect state machine matches the reference step by step")
    func reconnect() throws {
        let cases = try #require(try Spec.load("reconnect-cases.json")["cases"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let cfg = try #require(c["config"] as? [String: Any])
            let config = ReconnectConfig(
                baseMs: (cfg["baseMs"] as? NSNumber)?.int64Value ?? 0,
                maxMs: (cfg["maxMs"] as? NSNumber)?.int64Value ?? 0,
                jitter: cfg["jitter"] as? Double ?? 0,
                maxAttempts: value(cfg["maxAttempts"])
            )
            var state = ReconnectState()
            let steps = try #require(c["steps"] as? [[String: Any]])
            for (i, step) in steps.enumerated() {
                let r = step["random"] as? Double ?? 0
                let eventJSON = try #require(step["event"] as? [String: Any])
                let result = Reconnect.reduce(state, try event(eventJSON), config: config) { r }
                state = result.state
                let where_ = "\(name), step \(i) (\(eventJSON["type"] as? String ?? "?"))"
                let expected = try expectedState(try #require(step["state"] as? [String: Any]))
                let effectsJSON = try #require(step["effects"] as? [[String: Any]])
                let effects = try effectsJSON.map { try effect($0) }
                #expect(state == expected, "\(where_)")
                #expect(result.effects == effects, "\(where_)")
            }
        }
    }

    private struct UnknownValue: Error { let value: String }

    private func event(_ o: [String: Any]) throws -> ReconnectEvent {
        switch o["type"] as? String {
        case "connectRequested": return .connectRequested(deviceId: o["deviceId"] as? String ?? "")
        case "connected": return .connected
        case "ready": return .ready
        case "disconnected":
            let raw = o["cause"] as? String ?? ""
            guard let cause = DisconnectCause(rawValue: raw) else { throw UnknownValue(value: raw) }
            return .disconnected(cause)
        case "retryTimerFired": return .retryTimerFired
        case "bluetoothOff": return .bluetoothOff
        case "bluetoothOn": return .bluetoothOn
        case "permissionDenied": return .permissionDenied
        case "disconnectRequested": return .disconnectRequested
        default: throw UnknownValue(value: "\(o)")
        }
    }

    private func effect(_ o: [String: Any]) throws -> ReconnectEffect {
        switch o["type"] as? String {
        case "connect": return .connect(deviceId: o["deviceId"] as? String ?? "")
        case "discoverServices": return .discoverServices
        case "scheduleRetry": return .scheduleRetry(delayMs: (o["delayMs"] as? NSNumber)?.int64Value ?? -1)
        case "cancelRetry": return .cancelRetry
        case "disconnect": return .disconnect
        default: throw UnknownValue(value: "\(o)")
        }
    }

    private func expectedState(_ o: [String: Any]) throws -> ReconnectState {
        let raw = o["phase"] as? String ?? ""
        guard let phase = Phase(rawValue: raw) else { throw UnknownValue(value: raw) }
        let stop: String? = value(o["stopReason"])
        return ReconnectState(
            phase: phase,
            deviceId: value(o["deviceId"]),
            attempt: o["attempt"] as? Int ?? -1,
            stopReason: stop.flatMap(StopReason.init(rawValue:))
        )
    }
}
