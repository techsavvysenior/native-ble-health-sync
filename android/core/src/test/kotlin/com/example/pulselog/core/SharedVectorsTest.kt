package com.example.pulselog.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs every case in spec/gatt-vectors.json and spec/reconnect-cases.json.
 * The Swift package runs the same files, so both apps are held to one definition of correct.
 */
class SharedVectorsTest {
    private val specDir = File(System.getProperty("pulselog.spec") ?: "../../spec")

    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(File(specDir, name).readText()).jsonObject

    private val gatt by lazy { load("gatt-vectors.json") }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonElement.orNull(): JsonElement? = if (this is JsonNull) null else this

    private fun checkAll(cases: JsonArray, check: (JsonObject) -> Unit) {
        val failures = mutableListOf<String>()
        cases.forEach { element ->
            val case = element.jsonObject
            val name = case["name"]?.jsonPrimitive?.content ?: case.toString()
            try {
                check(case)
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("no cases loaded", cases.isNotEmpty())
    }

    @Test
    fun `UC-03 SFLOAT values decode exactly like the reference`() = checkAll(gatt.getValue("sfloat").jsonArray) { c ->
        val expected = c.getValue("value").orNull()?.jsonPrimitive?.double
        assertEquals("raw ${c.getValue("raw")}", expected, Sfloat.decode(c.getValue("raw").jsonPrimitive.int))
    }

    @Test
    fun `UC-02 heart rate vectors`() = checkAll(gatt.getValue("heartRate").jsonArray) { c ->
        val bytes = c.str("hex").hexToBytes()
        val error = c["error"]?.jsonPrimitive?.content
        if (error != null) {
            val thrown = runCatching { GattParsers.heartRate(bytes) }.exceptionOrNull()
            assertEquals(error, (thrown as? GattParseException)?.error?.name)
            return@checkAll
        }
        val e = c.getValue("expected").jsonObject
        val hr = GattParsers.heartRate(bytes)
        assertEquals(e.getValue("bpm").jsonPrimitive.int, hr.bpm)
        assertEquals(e.str("sensorContact"), hr.sensorContact.wire)
        assertEquals(e.getValue("energyExpendedKj").orNull()?.jsonPrimitive?.int, hr.energyExpendedKj)
        assertEquals(e.getValue("rr1024").jsonArray.map { it.jsonPrimitive.int }, hr.rr1024)
    }

    @Test
    fun `UC-03 blood pressure vectors`() = checkAll(gatt.getValue("bloodPressure").jsonArray) { c ->
        val bytes = c.str("hex").hexToBytes()
        val error = c["error"]?.jsonPrimitive?.content
        if (error != null) {
            val thrown = runCatching { GattParsers.bloodPressure(bytes) }.exceptionOrNull()
            assertEquals(error, (thrown as? GattParseException)?.error?.name)
            return@checkAll
        }
        val e = c.getValue("expected").jsonObject
        val bp = GattParsers.bloodPressure(bytes)
        assertEquals(e.str("unit"), if (bp.unit == PressureUnit.KPA) "kPa" else "mmHg")
        assertEquals(e.getValue("systolic").jsonPrimitive.double, bp.systolic, 0.0)
        assertEquals(e.getValue("diastolic").jsonPrimitive.double, bp.diastolic, 0.0)
        assertEquals(e.getValue("meanArterial").orNull()?.jsonPrimitive?.double, bp.meanArterial)
        assertEquals(e.getValue("pulseRate").orNull()?.jsonPrimitive?.double, bp.pulseRate)
        assertEquals(e.getValue("userId").orNull()?.jsonPrimitive?.int, bp.userId)
        assertEquals(e.getValue("status").orNull()?.jsonPrimitive?.int, bp.status)
        assertEquals(e.getValue("irregularPulse").jsonPrimitive.boolean, bp.irregularPulse)
        assertEquals(e.getValue("bodyMovement").jsonPrimitive.boolean, bp.bodyMovement)
        val ts = e.getValue("timestamp").orNull()?.jsonObject
        if (ts == null) {
            assertEquals(null, bp.timestamp)
        } else {
            fun f(k: String) = ts.getValue(k).jsonPrimitive.int
            assertEquals(DeviceTime(f("year"), f("month"), f("day"), f("hour"), f("minute"), f("second")), bp.timestamp)
        }
    }

    @Test
    fun `UC-08 reading ids match the reference`() = checkAll(gatt.getValue("readingIds").jsonArray) { c ->
        val kind = ReadingKind.entries.first { it.wire == c.str("kind") }
        val id = ReadingIds.of(c.str("deviceId"), kind, c.getValue("timestampMs").jsonPrimitive.long, c.str("hex").hexToBytes())
        assertEquals(c.str("expected"), id)
    }

    @Test
    fun `UC-09 Firestore documents match the reference field for field`() = checkAll(gatt.getValue("documents").jsonArray) { c ->
        val kind = ReadingKind.entries.first { it.wire == c.str("kind") }
        val reading = Reading.from(c.str("deviceId"), kind, c.getValue("receivedAtMs").jsonPrimitive.long, c.str("hex").hexToBytes())
        assertEquals(c.str("id"), reading.id)
        @Suppress("UNCHECKED_CAST")
        val expected = (plain(c.getValue("expected")) as Map<String, Any?>) + ("platform" to "android")
        assertEquals(expected, reading.toDocument(platform = "android"))
    }

    /** JSON to plain Kotlin values with the types the app writes: Long for integers, Double for decimals. */
    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" || e.content == "false" -> e.content.toBoolean()
            else -> e.content.toLongOrNull() ?: e.content.toDouble()
        }
        is JsonArray -> e.map { plain(it) }
        is JsonObject -> e.mapValues { plain(it.value) }
    }

    @Test
    fun `UC-04 to UC-07 reconnect state machine matches the reference step by step`() {
        val spec = load("reconnect-cases.json")
        checkAll(spec.getValue("cases").jsonArray) { case ->
            val cfg = case.getValue("config").jsonObject
            val config = ReconnectConfig(
                baseMs = cfg.getValue("baseMs").jsonPrimitive.long,
                maxMs = cfg.getValue("maxMs").jsonPrimitive.long,
                jitter = cfg.getValue("jitter").jsonPrimitive.double,
                maxAttempts = cfg.getValue("maxAttempts").orNull()?.jsonPrimitive?.int,
            )
            var state = ReconnectState()
            case.getValue("steps").jsonArray.forEachIndexed { i, stepElement ->
                val step = stepElement.jsonObject
                val r = step.getValue("random").jsonPrimitive.double
                val t = Reconnect.reduce(state, event(step.getValue("event").jsonObject), config) { r }
                state = t.state
                val where = "step $i (${step.getValue("event").jsonObject.str("type")})"
                assertEquals("$where state", stateJson(step.getValue("state").jsonObject), state)
                assertEquals("$where effects", step.getValue("effects").jsonArray.map { effect(it.jsonObject) }, t.effects)
            }
        }
    }

    private fun event(o: JsonObject): ReconnectEvent = when (o.str("type")) {
        "connectRequested" -> ReconnectEvent.ConnectRequested(o.str("deviceId"))
        "connected" -> ReconnectEvent.Connected
        "ready" -> ReconnectEvent.Ready
        "disconnected" -> ReconnectEvent.Disconnected(
            when (o.str("cause")) {
                "linkLoss" -> DisconnectCause.LINK_LOSS
                "timeout" -> DisconnectCause.TIMEOUT
                "gattError" -> DisconnectCause.GATT_ERROR
                "userRequested" -> DisconnectCause.USER_REQUESTED
                else -> error("cause ${o.str("cause")}")
            },
        )
        "retryTimerFired" -> ReconnectEvent.RetryTimerFired
        "bluetoothOff" -> ReconnectEvent.BluetoothOff
        "bluetoothOn" -> ReconnectEvent.BluetoothOn
        "permissionDenied" -> ReconnectEvent.PermissionDenied
        "disconnectRequested" -> ReconnectEvent.DisconnectRequested
        else -> error("event ${o.str("type")}")
    }

    private fun effect(o: JsonObject): ReconnectEffect = when (o.str("type")) {
        "connect" -> ReconnectEffect.Connect(o.str("deviceId"))
        "discoverServices" -> ReconnectEffect.DiscoverServices
        "scheduleRetry" -> ReconnectEffect.ScheduleRetry(o.getValue("delayMs").jsonPrimitive.long)
        "cancelRetry" -> ReconnectEffect.CancelRetry
        "disconnect" -> ReconnectEffect.Disconnect
        else -> error("effect ${o.str("type")}")
    }

    private fun stateJson(o: JsonObject) = ReconnectState(
        phase = when (o.str("phase")) {
            "idle" -> Phase.IDLE
            "connecting" -> Phase.CONNECTING
            "discovering" -> Phase.DISCOVERING
            "streaming" -> Phase.STREAMING
            "waitingToRetry" -> Phase.WAITING_TO_RETRY
            "bluetoothOff" -> Phase.BLUETOOTH_OFF
            "stopped" -> Phase.STOPPED
            else -> error("phase ${o.str("phase")}")
        },
        deviceId = o.getValue("deviceId").orNull()?.jsonPrimitive?.content,
        attempt = o.getValue("attempt").jsonPrimitive.int,
        stopReason = when (o.getValue("stopReason").orNull()?.jsonPrimitive?.content) {
            null -> null
            "unauthorized" -> StopReason.UNAUTHORIZED
            "gaveUp" -> StopReason.GAVE_UP
            else -> error("stopReason")
        },
    )
}
