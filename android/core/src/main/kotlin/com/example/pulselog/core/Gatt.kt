package com.example.pulselog.core

/**
 * Parsers for the two Bluetooth SIG characteristics this app reads.
 *
 * Heart Rate Measurement (0x2A37), sent as notifications by chest straps and watches.
 * Blood Pressure Measurement (0x2A35), sent as indications by cuffs.
 *
 * Behavior is defined by spec/reference.py and checked against spec/gatt-vectors.json,
 * the same file the Swift parsers are tested against.
 */
object GattUuids {
    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"
    const val BLOOD_PRESSURE_SERVICE = "00001810-0000-1000-8000-00805f9b34fb"
    const val BLOOD_PRESSURE_MEASUREMENT = "00002a35-0000-1000-8000-00805f9b34fb"
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
}

/** Why a payload was rejected. The names match the error codes in the shared vectors. */
enum class ParseError { TOO_SHORT, BAD_RR_INTERVALS, BAD_TIMESTAMP, INVALID_VALUE }

class GattParseException(val error: ParseError) : Exception(error.name)

enum class SensorContact { NOT_SUPPORTED, NOT_DETECTED, DETECTED }

data class HeartRateMeasurement(
    val bpm: Int,
    val sensorContact: SensorContact,
    val energyExpendedKj: Int?,
    /** RR intervals in units of 1/1024 second, as sent by the device. */
    val rr1024: List<Int>,
) {
    val rrMillis: List<Double> get() = rr1024.map { it * 1000.0 / 1024.0 }
}

enum class PressureUnit { MMHG, KPA }

/** Device-reported local time. Devices send no time zone, so this stays a wall-clock value. */
data class DeviceTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int) {
    fun iso(): String = "%04d-%02d-%02dT%02d:%02d:%02d".format(year, month, day, hour, minute, second)
}

data class BloodPressureMeasurement(
    val unit: PressureUnit,
    val systolic: Double,
    val diastolic: Double,
    val meanArterial: Double?,
    val timestamp: DeviceTime?,
    val pulseRate: Double?,
    val userId: Int?,
    val status: Int?,
) {
    val bodyMovement: Boolean get() = status != null && status and 0x0001 != 0
    val irregularPulse: Boolean get() = status != null && status and 0x0004 != 0
}

/** IEEE 11073-20601 16-bit SFLOAT. Special values (NaN, NRes, +/-INF, reserved) decode to null. */
object Sfloat {
    private val SPECIAL = setOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)

    fun decode(raw: Int): Double? {
        if (raw in SPECIAL) return null
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = raw shr 12
        if (exponent >= 0x8) exponent -= 0x10
        // Exact integer powers of ten, so Kotlin, Swift and the Python reference round identically.
        var power = 1L
        repeat(kotlin.math.abs(exponent)) { power *= 10 }
        return if (exponent >= 0) (mantissa.toLong() * power).toDouble() else mantissa.toDouble() / power.toDouble()
    }
}

private class Reader(private val data: ByteArray) {
    var index = 0
        private set

    val remaining: Int get() = data.size - index

    fun u8(): Int {
        if (index + 1 > data.size) throw GattParseException(ParseError.TOO_SHORT)
        return data[index++].toInt() and 0xFF
    }

    fun u16(): Int {
        if (index + 2 > data.size) throw GattParseException(ParseError.TOO_SHORT)
        val value = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
        index += 2
        return value
    }
}

object GattParsers {
    fun heartRate(data: ByteArray): HeartRateMeasurement {
        val r = Reader(data)
        val flags = r.u8()
        val bpm = if (flags and 0x01 != 0) r.u16() else r.u8()
        val contact = when ((flags shr 1) and 0x03) {
            2 -> SensorContact.NOT_DETECTED
            3 -> SensorContact.DETECTED
            else -> SensorContact.NOT_SUPPORTED
        }
        val energy = if (flags and 0x08 != 0) r.u16() else null
        val rr = mutableListOf<Int>()
        if (flags and 0x10 != 0) {
            if (r.remaining < 2 || r.remaining % 2 != 0) throw GattParseException(ParseError.BAD_RR_INTERVALS)
            while (r.remaining > 0) rr += r.u16()
        }
        if (bpm == 0) throw GattParseException(ParseError.INVALID_VALUE)
        return HeartRateMeasurement(bpm, contact, energy, rr)
    }

    fun bloodPressure(data: ByteArray): BloodPressureMeasurement {
        val r = Reader(data)
        val flags = r.u8()
        val unit = if (flags and 0x01 != 0) PressureUnit.KPA else PressureUnit.MMHG
        val systolic = Sfloat.decode(r.u16())
        val diastolic = Sfloat.decode(r.u16())
        val meanArterial = Sfloat.decode(r.u16())

        var timestamp: DeviceTime? = null
        if (flags and 0x02 != 0) {
            val year = r.u16()
            val month = r.u8()
            val day = r.u8()
            val hour = r.u8()
            val minute = r.u8()
            val second = r.u8()
            // Zero year, month or day means the device doesn't know the time.
            if (year != 0 && month != 0 && day != 0) {
                val valid = month in 1..12 && day in 1..31 && hour <= 23 && minute <= 59 && second <= 59
                if (!valid) throw GattParseException(ParseError.BAD_TIMESTAMP)
                timestamp = DeviceTime(year, month, day, hour, minute, second)
            }
        }
        val pulse = if (flags and 0x04 != 0) Sfloat.decode(r.u16()) else null
        var userId: Int? = null
        if (flags and 0x08 != 0) {
            userId = r.u8().takeIf { it != 0xFF } // 0xFF means unknown user
        }
        val status = if (flags and 0x10 != 0) r.u16() else null

        if (systolic == null || diastolic == null) throw GattParseException(ParseError.INVALID_VALUE)
        return BloodPressureMeasurement(unit, systolic, diastolic, meanArterial, timestamp, pulse, userId, status)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
