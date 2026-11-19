import Foundation

/// The reconnect state machine as a pure function, mirroring the Kotlin core and spec/reference.py.
/// The app turns CoreBluetooth callbacks into `ReconnectEvent`s and carries out the returned effects.
public struct ReconnectConfig: Equatable, Sendable {
    public var baseMs: Int64
    public var maxMs: Int64
    /// 0.2 means each delay is shortened by up to 20%, so many phones don't retry in lockstep.
    public var jitter: Double
    /// nil retries forever, which suits a wearable the user expects to come back on its own.
    public var maxAttempts: Int?

    public init(baseMs: Int64 = 1_000, maxMs: Int64 = 30_000, jitter: Double = 0.2, maxAttempts: Int? = nil) {
        self.baseMs = baseMs
        self.maxMs = maxMs
        self.jitter = jitter
        self.maxAttempts = maxAttempts
    }
}

public enum Phase: String, Sendable {
    case idle, connecting, discovering, streaming, waitingToRetry, bluetoothOff, stopped
}

public enum StopReason: String, Sendable {
    case unauthorized, gaveUp
}

public struct ReconnectState: Equatable, Sendable {
    public var phase: Phase = .idle
    public var deviceId: String?
    /// Consecutive failed attempts since the last successful connection.
    public var attempt: Int = 0
    public var stopReason: StopReason?

    public init(phase: Phase = .idle, deviceId: String? = nil, attempt: Int = 0, stopReason: StopReason? = nil) {
        self.phase = phase
        self.deviceId = deviceId
        self.attempt = attempt
        self.stopReason = stopReason
    }

    public var isActive: Bool { phase != .idle && phase != .stopped }
}

public enum DisconnectCause: String, Sendable {
    case linkLoss, timeout, gattError, userRequested
}

public enum ReconnectEvent: Equatable, Sendable {
    case connectRequested(deviceId: String)
    case connected
    case ready
    case disconnected(DisconnectCause)
    case retryTimerFired
    case bluetoothOff
    case bluetoothOn
    case permissionDenied
    case disconnectRequested
}

public enum ReconnectEffect: Equatable, Sendable {
    case connect(deviceId: String)
    case discoverServices
    case scheduleRetry(delayMs: Int64)
    case cancelRetry
    case disconnect
}

public enum Reconnect {
    /// Delay before retry number `failure` (1-based). `random` is in [0, 1).
    public static func backoffMs(_ config: ReconnectConfig, failure: Int, random: Double) -> Int64 {
        var uncapped = config.baseMs
        for _ in 0..<max(0, failure - 1) where uncapped < config.maxMs { uncapped *= 2 }
        let capped = min(config.maxMs, uncapped)
        return Int64(floor(Double(capped) * (1.0 - config.jitter * random)))
    }

    public static func reduce(
        _ state: ReconnectState,
        _ event: ReconnectEvent,
        config: ReconnectConfig,
        random: () -> Double
    ) -> (state: ReconnectState, effects: [ReconnectEffect]) {
        var s = state
        var effects: [ReconnectEffect] = []
        let phase = state.phase
        let active = phase == .connecting || phase == .discovering || phase == .streaming

        switch event {
        case .permissionDenied:
            if phase == .waitingToRetry { effects.append(.cancelRetry) }
            s.phase = .stopped
            s.stopReason = .unauthorized
            s.attempt = 0
            return (s, effects)

        case .connectRequested(let deviceId):
            s.deviceId = deviceId
            s.attempt = 0
            s.stopReason = nil
            if phase == .waitingToRetry { effects.append(.cancelRetry) }
            if phase == .bluetoothOff { return (s, effects) } // connect when the radio is back
            s.phase = .connecting
            effects.append(.connect(deviceId: deviceId))
            return (s, effects)

        case .disconnectRequested:
            if phase == .waitingToRetry { effects.append(.cancelRetry) }
            if active { effects.append(.disconnect) }
            return (ReconnectState(), effects)

        case .bluetoothOff:
            if phase == .idle || phase == .stopped || phase == .bluetoothOff { return (s, effects) }
            if phase == .waitingToRetry { effects.append(.cancelRetry) }
            s.phase = .bluetoothOff
            return (s, effects)

        case .bluetoothOn:
            guard phase == .bluetoothOff else { return (s, effects) }
            guard let deviceId = s.deviceId else {
                s.phase = .idle
                return (s, effects)
            }
            s.phase = .connecting
            s.attempt = 0
            effects.append(.connect(deviceId: deviceId))
            return (s, effects)

        case .connected where phase == .connecting:
            s.phase = .discovering
            effects.append(.discoverServices)
            return (s, effects)

        case .ready where phase == .discovering:
            s.phase = .streaming
            s.attempt = 0
            return (s, effects)

        case .disconnected(let cause) where active:
            if cause == .userRequested { return (ReconnectState(), effects) }
            let failure = s.attempt + 1
            if let limit = config.maxAttempts, failure > limit {
                s.phase = .stopped
                s.stopReason = .gaveUp
                s.attempt = 0
                return (s, effects)
            }
            effects.append(.scheduleRetry(delayMs: backoffMs(config, failure: failure, random: random())))
            s.phase = .waitingToRetry
            s.attempt = failure
            return (s, effects)

        case .retryTimerFired where phase == .waitingToRetry:
            guard let deviceId = s.deviceId else { return (s, effects) }
            s.phase = .connecting
            effects.append(.connect(deviceId: deviceId))
            return (s, effects)

        default:
            // Late callbacks in the wrong phase are normal with BLE stacks; ignore them.
            return (s, effects)
        }
    }
}
