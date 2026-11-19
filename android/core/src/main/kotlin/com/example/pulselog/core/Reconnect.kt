package com.example.pulselog.core

import kotlin.math.floor
import kotlin.math.min

/**
 * The reconnect state machine, as a pure function.
 *
 * The platform layer (BluetoothGatt here, CoreBluetooth on iOS) turns callbacks into [ReconnectEvent]s,
 * calls [Reconnect.reduce], and carries out the returned [ReconnectEffect]s. Nothing in here touches
 * Bluetooth, timers or threads, so every transition is covered by spec/reconnect-cases.json on both
 * platforms. See docs/reconnect.md for the diagram.
 */
data class ReconnectConfig(
    val baseMs: Long = 1_000,
    val maxMs: Long = 30_000,
    /** 0.2 means each delay is shortened by up to 20%, so many phones don't retry in lockstep. */
    val jitter: Double = 0.2,
    /** null retries forever, which suits a wearable the user expects to come back on its own. */
    val maxAttempts: Int? = null,
)

enum class Phase { IDLE, CONNECTING, DISCOVERING, STREAMING, WAITING_TO_RETRY, BLUETOOTH_OFF, STOPPED }

enum class StopReason { UNAUTHORIZED, GAVE_UP }

data class ReconnectState(
    val phase: Phase = Phase.IDLE,
    val deviceId: String? = null,
    /** Consecutive failed attempts since the last successful connection. */
    val attempt: Int = 0,
    val stopReason: StopReason? = null,
)

enum class DisconnectCause { LINK_LOSS, TIMEOUT, GATT_ERROR, USER_REQUESTED }

sealed interface ReconnectEvent {
    data class ConnectRequested(val deviceId: String) : ReconnectEvent
    data object Connected : ReconnectEvent
    data object Ready : ReconnectEvent
    data class Disconnected(val cause: DisconnectCause) : ReconnectEvent
    data object RetryTimerFired : ReconnectEvent
    data object BluetoothOff : ReconnectEvent
    data object BluetoothOn : ReconnectEvent
    data object PermissionDenied : ReconnectEvent
    data object DisconnectRequested : ReconnectEvent
}

sealed interface ReconnectEffect {
    data class Connect(val deviceId: String) : ReconnectEffect
    data object DiscoverServices : ReconnectEffect
    data class ScheduleRetry(val delayMs: Long) : ReconnectEffect
    data object CancelRetry : ReconnectEffect
    data object Disconnect : ReconnectEffect
}

data class Transition(val state: ReconnectState, val effects: List<ReconnectEffect>)

object Reconnect {
    /** Delay before retry number [failure] (1-based). [random] is in [0, 1). */
    fun backoffMs(config: ReconnectConfig, failure: Int, random: Double): Long {
        var uncapped = config.baseMs
        repeat(failure - 1) { if (uncapped < config.maxMs) uncapped *= 2 }
        val capped = min(config.maxMs, uncapped)
        return floor(capped.toDouble() * (1.0 - config.jitter * random)).toLong()
    }

    fun reduce(
        state: ReconnectState,
        event: ReconnectEvent,
        config: ReconnectConfig,
        random: () -> Double,
    ): Transition {
        val phase = state.phase
        val active = phase == Phase.CONNECTING || phase == Phase.DISCOVERING || phase == Phase.STREAMING
        val effects = mutableListOf<ReconnectEffect>()
        fun done(next: ReconnectState) = Transition(next, effects)

        when (event) {
            ReconnectEvent.PermissionDenied -> {
                if (phase == Phase.WAITING_TO_RETRY) effects += ReconnectEffect.CancelRetry
                return done(state.copy(phase = Phase.STOPPED, stopReason = StopReason.UNAUTHORIZED, attempt = 0))
            }

            is ReconnectEvent.ConnectRequested -> {
                val next = state.copy(deviceId = event.deviceId, attempt = 0, stopReason = null)
                if (phase == Phase.WAITING_TO_RETRY) effects += ReconnectEffect.CancelRetry
                if (phase == Phase.BLUETOOTH_OFF) return done(next) // connect when the radio is back
                effects += ReconnectEffect.Connect(event.deviceId)
                return done(next.copy(phase = Phase.CONNECTING))
            }

            ReconnectEvent.DisconnectRequested -> {
                if (phase == Phase.WAITING_TO_RETRY) effects += ReconnectEffect.CancelRetry
                if (active) effects += ReconnectEffect.Disconnect
                return done(ReconnectState())
            }

            ReconnectEvent.BluetoothOff -> {
                if (phase == Phase.IDLE || phase == Phase.STOPPED || phase == Phase.BLUETOOTH_OFF) return done(state)
                if (phase == Phase.WAITING_TO_RETRY) effects += ReconnectEffect.CancelRetry
                return done(state.copy(phase = Phase.BLUETOOTH_OFF))
            }

            ReconnectEvent.BluetoothOn -> {
                if (phase != Phase.BLUETOOTH_OFF) return done(state)
                val device = state.deviceId ?: return done(state.copy(phase = Phase.IDLE))
                effects += ReconnectEffect.Connect(device)
                return done(state.copy(phase = Phase.CONNECTING, attempt = 0))
            }

            ReconnectEvent.Connected -> if (phase == Phase.CONNECTING) {
                effects += ReconnectEffect.DiscoverServices
                return done(state.copy(phase = Phase.DISCOVERING))
            }

            ReconnectEvent.Ready -> if (phase == Phase.DISCOVERING) {
                return done(state.copy(phase = Phase.STREAMING, attempt = 0))
            }

            is ReconnectEvent.Disconnected -> if (active) {
                if (event.cause == DisconnectCause.USER_REQUESTED) return done(ReconnectState())
                val failure = state.attempt + 1
                val limit = config.maxAttempts
                if (limit != null && failure > limit) {
                    return done(state.copy(phase = Phase.STOPPED, stopReason = StopReason.GAVE_UP, attempt = 0))
                }
                effects += ReconnectEffect.ScheduleRetry(backoffMs(config, failure, random()))
                return done(state.copy(phase = Phase.WAITING_TO_RETRY, attempt = failure))
            }

            ReconnectEvent.RetryTimerFired -> if (phase == Phase.WAITING_TO_RETRY) {
                effects += ReconnectEffect.Connect(requireNotNull(state.deviceId))
                return done(state.copy(phase = Phase.CONNECTING))
            }
        }
        // Late callbacks in the wrong phase are normal with BLE stacks; ignore them.
        return done(state)
    }
}
