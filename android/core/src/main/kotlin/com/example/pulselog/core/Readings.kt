package com.example.pulselog.core

import java.security.MessageDigest

enum class ReadingKind(val wire: String) { HEART_RATE("heartRate"), BLOOD_PRESSURE("bloodPressure") }

/**
 * One measurement as stored on the phone and uploaded to Firestore.
 *
 * [id] is derived from the device, the kind, the receive time and the raw bytes, so uploading the same
 * reading twice writes the same document twice instead of creating a duplicate (docs/adr/0003).
 */
data class Reading(
    val id: String,
    val deviceId: String,
    val kind: ReadingKind,
    val receivedAtMs: Long,
    val payloadHex: String,
) {
    companion object {
        fun from(deviceId: String, kind: ReadingKind, receivedAtMs: Long, payload: ByteArray) =
            Reading(ReadingIds.of(deviceId, kind, receivedAtMs, payload), deviceId, kind, receivedAtMs, payload.toHex())
    }

    /**
     * The Firestore document. Field names and shapes must match firebase/firestore.rules;
     * the rules tests use the same shape. Values are re-parsed from the raw bytes, so a reading
     * that fails to parse never reaches the queue.
     */
    fun toDocument(platform: String): Map<String, Any?> {
        val bytes = payloadHex.hexToBytes()
        val base = mapOf(
            "schema" to 1L,
            "deviceId" to deviceId,
            "kind" to kind.wire,
            "receivedAtMs" to receivedAtMs,
            "payloadHex" to payloadHex,
            "platform" to platform,
        )
        return when (kind) {
            ReadingKind.HEART_RATE -> {
                val hr = GattParsers.heartRate(bytes)
                base + ("heartRate" to mapOf(
                    "bpm" to hr.bpm.toLong(),
                    "sensorContact" to hr.sensorContact.wire,
                    "energyExpendedKj" to hr.energyExpendedKj?.toLong(),
                    "rr1024" to hr.rr1024.map { it.toLong() },
                ))
            }
            ReadingKind.BLOOD_PRESSURE -> {
                val bp = GattParsers.bloodPressure(bytes)
                base + ("bloodPressure" to mapOf(
                    "unit" to if (bp.unit == PressureUnit.KPA) "kPa" else "mmHg",
                    "systolic" to bp.systolic,
                    "diastolic" to bp.diastolic,
                    "meanArterial" to bp.meanArterial,
                    "pulseRate" to bp.pulseRate,
                    "deviceTime" to bp.timestamp?.iso(),
                    "irregularPulse" to bp.irregularPulse,
                    "bodyMovement" to bp.bodyMovement,
                ))
            }
        }
    }
}

val SensorContact.wire: String
    get() = when (this) {
        SensorContact.NOT_SUPPORTED -> "notSupported"
        SensorContact.NOT_DETECTED -> "notDetected"
        SensorContact.DETECTED -> "detected"
    }

object ReadingIds {
    /** sha256("<deviceId>|<kind>|<receivedAtMs>|<payload hex>"), first 32 hex characters. */
    fun of(deviceId: String, kind: ReadingKind, receivedAtMs: Long, payload: ByteArray): String {
        val source = "$deviceId|${kind.wire}|$receivedAtMs|${payload.toHex()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.toHex().substring(0, 32)
    }
}

/**
 * Heart-rate straps notify about once a second. Uploading every value would be 3,600 documents an hour
 * per user, so the uploader keeps one value per [intervalMs]. The live screen still shows every value.
 */
class HeartRateSampler(private val intervalMs: Long = 5_000) {
    private val lastKept = mutableMapOf<String, Long>()

    fun shouldKeep(deviceId: String, receivedAtMs: Long): Boolean {
        val last = lastKept[deviceId]
        if (last != null && receivedAtMs - last < intervalMs && receivedAtMs >= last) return false
        lastKept[deviceId] = receivedAtMs
        return true
    }
}
