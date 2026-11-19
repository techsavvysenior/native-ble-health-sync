import Foundation

/// Bluetooth SIG UUIDs for the two characteristics this app reads.
public enum GattUUID {
    public static let heartRateService = "180D"
    public static let heartRateMeasurement = "2A37"
    public static let bloodPressureService = "1810"
    public static let bloodPressureMeasurement = "2A35"
}

/// Why a payload was rejected. Raw values match the error codes in spec/gatt-vectors.json.
public enum ParseError: String, Error, Sendable {
    case tooShort = "TOO_SHORT"
    case badRRIntervals = "BAD_RR_INTERVALS"
    case badTimestamp = "BAD_TIMESTAMP"
    case invalidValue = "INVALID_VALUE"
}

public enum SensorContact: String, Sendable {
    case notSupported, notDetected, detected
}

public struct HeartRateMeasurement: Equatable, Sendable {
    public let bpm: Int
    public let sensorContact: SensorContact
    public let energyExpendedKj: Int?
    /// RR intervals in units of 1/1024 second, as sent by the device.
    public let rr1024: [Int]

    public var rrMillis: [Double] { rr1024.map { Double($0) * 1000.0 / 1024.0 } }
}

public enum PressureUnit: String, Sendable {
    case mmHg, kPa
}

/// Device-reported local time. Devices send no time zone, so this stays a wall-clock value.
public struct DeviceTime: Equatable, Sendable {
    public let year, month, day, hour, minute, second: Int

    public init(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) {
        (self.year, self.month, self.day, self.hour, self.minute, self.second) = (year, month, day, hour, minute, second)
    }

    public var iso: String {
        String(format: "%04d-%02d-%02dT%02d:%02d:%02d", year, month, day, hour, minute, second)
    }
}

public struct BloodPressureMeasurement: Equatable, Sendable {
    public let unit: PressureUnit
    public let systolic: Double
    public let diastolic: Double
    public let meanArterial: Double?
    public let timestamp: DeviceTime?
    public let pulseRate: Double?
    public let userId: Int?
    public let status: Int?

    public var bodyMovement: Bool { (status ?? 0) & 0x0001 != 0 }
    public var irregularPulse: Bool { (status ?? 0) & 0x0004 != 0 }
}

/// IEEE 11073-20601 16-bit SFLOAT. Special values (NaN, NRes, +/-INF, reserved) decode to nil.
public enum SFloat {
    private static let special: Set<Int> = [0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801]

    public static func decode(_ raw: Int) -> Double? {
        if special.contains(raw) { return nil }
        var mantissa = raw & 0x0FFF
        if mantissa >= 0x0800 { mantissa -= 0x1000 }
        var exponent = raw >> 12
        if exponent >= 0x8 { exponent -= 0x10 }
        // Exact integer powers of ten, so Swift, Kotlin and the Python reference round identically.
        var power: Int64 = 1
        for _ in 0..<abs(exponent) { power *= 10 }
        return exponent >= 0 ? Double(Int64(mantissa) * power) : Double(mantissa) / Double(power)
    }
}

private struct Reader {
    let data: [UInt8]
    private(set) var index = 0

    init(_ data: [UInt8]) { self.data = data }

    var remaining: Int { data.count - index }

    mutating func u8() throws -> Int {
        guard index + 1 <= data.count else { throw ParseError.tooShort }
        defer { index += 1 }
        return Int(data[index])
    }

    mutating func u16() throws -> Int {
        guard index + 2 <= data.count else { throw ParseError.tooShort }
        defer { index += 2 }
        return Int(data[index]) | (Int(data[index + 1]) << 8)
    }
}

public enum GattParsers {
    public static func heartRate(_ data: [UInt8]) throws -> HeartRateMeasurement {
        var r = Reader(data)
        let flags = try r.u8()
        let bpm = try flags & 0x01 != 0 ? r.u16() : r.u8()
        let contact: SensorContact
        switch (flags >> 1) & 0x03 {
        case 2: contact = .notDetected
        case 3: contact = .detected
        default: contact = .notSupported
        }
        let energy: Int? = try flags & 0x08 != 0 ? r.u16() : nil
        var rr: [Int] = []
        if flags & 0x10 != 0 {
            guard r.remaining >= 2, r.remaining % 2 == 0 else { throw ParseError.badRRIntervals }
            while r.remaining > 0 { rr.append(try r.u16()) }
        }
        guard bpm != 0 else { throw ParseError.invalidValue }
        return HeartRateMeasurement(bpm: bpm, sensorContact: contact, energyExpendedKj: energy, rr1024: rr)
    }

    public static func bloodPressure(_ data: [UInt8]) throws -> BloodPressureMeasurement {
        var r = Reader(data)
        let flags = try r.u8()
        let unit: PressureUnit = flags & 0x01 != 0 ? .kPa : .mmHg
        let systolic = try SFloat.decode(r.u16())
        let diastolic = try SFloat.decode(r.u16())
        let meanArterial = try SFloat.decode(r.u16())

        var timestamp: DeviceTime?
        if flags & 0x02 != 0 {
            let year = try r.u16()
            let month = try r.u8(), day = try r.u8()
            let hour = try r.u8(), minute = try r.u8(), second = try r.u8()
            // Zero year, month or day means the device doesn't know the time.
            if year != 0 && month != 0 && day != 0 {
                guard (1...12).contains(month), (1...31).contains(day), hour <= 23, minute <= 59, second <= 59 else {
                    throw ParseError.badTimestamp
                }
                timestamp = DeviceTime(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
            }
        }
        let pulse: Double? = try flags & 0x04 != 0 ? SFloat.decode(r.u16()) : nil
        var userId: Int?
        if flags & 0x08 != 0 {
            let id = try r.u8()
            userId = id == 0xFF ? nil : id // 0xFF means unknown user
        }
        let status: Int? = try flags & 0x10 != 0 ? r.u16() : nil

        guard let systolic, let diastolic else { throw ParseError.invalidValue }
        return BloodPressureMeasurement(
            unit: unit, systolic: systolic, diastolic: diastolic, meanArterial: meanArterial,
            timestamp: timestamp, pulseRate: pulse, userId: userId, status: status
        )
    }
}

extension Array where Element == UInt8 {
    public init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = bytes
    }

    public var hex: String { map { String(format: "%02x", $0) }.joined() }
}
