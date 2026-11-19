import CryptoKit
import Foundation

public enum ReadingKind: String, Codable, Sendable {
    case heartRate, bloodPressure
}

/// One measurement as stored on the phone and uploaded to Firestore.
///
/// `id` is derived from the device, the kind, the receive time and the raw bytes, so uploading the same
/// reading twice rewrites one document instead of creating a duplicate (docs/adr/0003).
public struct Reading: Codable, Equatable, Sendable {
    public let id: String
    public let deviceId: String
    public let kind: ReadingKind
    public let receivedAtMs: Int64
    public let payloadHex: String

    public init(id: String, deviceId: String, kind: ReadingKind, receivedAtMs: Int64, payloadHex: String) {
        self.id = id
        self.deviceId = deviceId
        self.kind = kind
        self.receivedAtMs = receivedAtMs
        self.payloadHex = payloadHex
    }

    public static func from(deviceId: String, kind: ReadingKind, receivedAtMs: Int64, payload: [UInt8]) -> Reading {
        Reading(
            id: ReadingIds.of(deviceId: deviceId, kind: kind, receivedAtMs: receivedAtMs, payload: payload),
            deviceId: deviceId, kind: kind, receivedAtMs: receivedAtMs, payloadHex: payload.hex
        )
    }

    /// The Firestore document. Field names and shapes must match firebase/firestore.rules.
    /// Missing values are NSNull so the stored document matches the Android one field for field.
    public func document(platform: String) throws -> [String: Any] {
        let bytes = [UInt8](hex: payloadHex) ?? []
        var doc: [String: Any] = [
            "schema": 1,
            "deviceId": deviceId,
            "kind": kind.rawValue,
            "receivedAtMs": receivedAtMs,
            "payloadHex": payloadHex,
            "platform": platform,
        ]
        switch kind {
        case .heartRate:
            let hr = try GattParsers.heartRate(bytes)
            doc["heartRate"] = [
                "bpm": hr.bpm,
                "sensorContact": hr.sensorContact.rawValue,
                "energyExpendedKj": hr.energyExpendedKj.map { $0 as Any } ?? NSNull(),
                "rr1024": hr.rr1024,
            ] as [String: Any]
        case .bloodPressure:
            let bp = try GattParsers.bloodPressure(bytes)
            doc["bloodPressure"] = [
                "unit": bp.unit.rawValue,
                "systolic": bp.systolic,
                "diastolic": bp.diastolic,
                "meanArterial": bp.meanArterial.map { $0 as Any } ?? NSNull(),
                "pulseRate": bp.pulseRate.map { $0 as Any } ?? NSNull(),
                "deviceTime": bp.timestamp.map { $0.iso as Any } ?? NSNull(),
                "irregularPulse": bp.irregularPulse,
                "bodyMovement": bp.bodyMovement,
            ] as [String: Any]
        }
        return doc
    }
}

public enum ReadingIds {
    /// sha256("<deviceId>|<kind>|<receivedAtMs>|<payload hex>"), first 32 hex characters.
    public static func of(deviceId: String, kind: ReadingKind, receivedAtMs: Int64, payload: [UInt8]) -> String {
        let source = "\(deviceId)|\(kind.rawValue)|\(receivedAtMs)|\(payload.hex)"
        let digest = SHA256.hash(data: Data(source.utf8))
        return String(Array(digest).hex.prefix(32))
    }
}

/// Heart-rate straps notify about once a second. Uploading every value would be 3,600 documents an hour
/// per user, so the uploader keeps one value per interval. The live screen still shows every value.
public final class HeartRateSampler {
    private let intervalMs: Int64
    private var lastKept: [String: Int64] = [:]

    public init(intervalMs: Int64 = 5_000) {
        self.intervalMs = intervalMs
    }

    public func shouldKeep(deviceId: String, receivedAtMs: Int64) -> Bool {
        if let last = lastKept[deviceId], receivedAtMs >= last, receivedAtMs - last < intervalMs { return false }
        lastKept[deviceId] = receivedAtMs
        return true
    }
}
