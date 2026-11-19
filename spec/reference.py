#!/usr/bin/env python3
"""Reference implementation of the shared behavior, and generator of the test vectors.

The Android app (Kotlin) and the iOS app (Swift) each implement the same four things:

  1. Heart Rate Measurement parsing      (Bluetooth SIG characteristic 0x2A37)
  2. Blood Pressure Measurement parsing  (Bluetooth SIG characteristic 0x2A35)
  3. Reading ids                          (deterministic, so an upload retry never duplicates)
  4. The reconnect state machine          (what to do after a link drops)

This file is the oracle. It writes spec/gatt-vectors.json and spec/reconnect-cases.json,
and both platforms' unit tests load those files and must produce the same answers.
CI runs `python3 spec/reference.py --check` so the JSON can't drift from this code.

No third-party packages: Python 3.10+ standard library only.
"""

from __future__ import annotations

import hashlib
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# --------------------------------------------------------------------------------------
# 1. IEEE 11073-20601 16-bit SFLOAT (used by Blood Pressure Measurement)
# --------------------------------------------------------------------------------------

SFLOAT_NAN = 0x07FF
SFLOAT_NRES = 0x0800
SFLOAT_POS_INF = 0x07FE
SFLOAT_NEG_INF = 0x0802
SFLOAT_RESERVED = 0x0801
SFLOAT_SPECIAL = {SFLOAT_NAN, SFLOAT_NRES, SFLOAT_POS_INF, SFLOAT_NEG_INF, SFLOAT_RESERVED}


def sfloat(raw: int) -> float | None:
    """Decode a 16-bit SFLOAT. Special values (NaN, NRes, +/-INF, reserved) return None."""
    if raw in SFLOAT_SPECIAL:
        return None
    mantissa = raw & 0x0FFF
    if mantissa >= 0x0800:
        mantissa -= 0x1000
    exponent = raw >> 12
    if exponent >= 0x8:
        exponent -= 0x10
    # Multiply or divide by an exact power of ten so both platforms round the same way.
    if exponent >= 0:
        return float(mantissa * (10 ** exponent))
    return mantissa / float(10 ** (-exponent))


def encode_sfloat(mantissa: int, exponent: int) -> int:
    return ((exponent & 0xF) << 12) | (mantissa & 0x0FFF)


# --------------------------------------------------------------------------------------
# 2. Parsers
# --------------------------------------------------------------------------------------


class ParseError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _u8(data: bytes, i: int) -> int:
    if i + 1 > len(data):
        raise ParseError("TOO_SHORT")
    return data[i]


def _u16(data: bytes, i: int) -> int:
    if i + 2 > len(data):
        raise ParseError("TOO_SHORT")
    return data[i] | (data[i + 1] << 8)


def parse_heart_rate(data: bytes) -> dict:
    flags = _u8(data, 0)
    i = 1
    if flags & 0x01:
        bpm = _u16(data, i)
        i += 2
    else:
        bpm = _u8(data, i)
        i += 1
    contact_bits = (flags >> 1) & 0x03
    sensor_contact = {0: "notSupported", 1: "notSupported", 2: "notDetected", 3: "detected"}[contact_bits]
    energy = None
    if flags & 0x08:
        energy = _u16(data, i)
        i += 2
    rr: list[int] = []
    if flags & 0x10:
        remaining = len(data) - i
        if remaining < 2 or remaining % 2 != 0:
            raise ParseError("BAD_RR_INTERVALS")
        while i < len(data):
            rr.append(_u16(data, i))
            i += 2
    if bpm == 0:
        raise ParseError("INVALID_VALUE")
    return {
        "bpm": bpm,
        "sensorContact": sensor_contact,
        "energyExpendedKj": energy,
        "rr1024": rr,
    }


def parse_blood_pressure(data: bytes) -> dict:
    flags = _u8(data, 0)
    unit = "kPa" if flags & 0x01 else "mmHg"
    systolic = sfloat(_u16(data, 1))
    diastolic = sfloat(_u16(data, 3))
    mean_arterial = sfloat(_u16(data, 5))
    i = 7
    timestamp = None
    if flags & 0x02:
        year = _u16(data, i)
        month, day, hour, minute, second = (_u8(data, i + k) for k in range(2, 7))
        i += 7
        # Zero year/month/day means "not known" in the Date Time characteristic.
        if year != 0 and month != 0 and day != 0:
            if not (1 <= month <= 12 and 1 <= day <= 31 and hour <= 23 and minute <= 59 and second <= 59):
                raise ParseError("BAD_TIMESTAMP")
            timestamp = {"year": year, "month": month, "day": day, "hour": hour, "minute": minute, "second": second}
    pulse = None
    if flags & 0x04:
        pulse = sfloat(_u16(data, i))
        i += 2
    user_id = None
    if flags & 0x08:
        user_id = _u8(data, i)
        i += 1
        if user_id == 0xFF:  # 0xFF = unknown user
            user_id = None
    status = None
    if flags & 0x10:
        status = _u16(data, i)
        i += 2
    if systolic is None or diastolic is None:
        raise ParseError("INVALID_VALUE")
    return {
        "unit": unit,
        "systolic": systolic,
        "diastolic": diastolic,
        "meanArterial": mean_arterial,
        "timestamp": timestamp,
        "pulseRate": pulse,
        "userId": user_id,
        "status": status,
        "bodyMovement": bool(status & 0x0001) if status is not None else False,
        "irregularPulse": bool(status & 0x0004) if status is not None else False,
    }


# --------------------------------------------------------------------------------------
# 3. Reading ids
# --------------------------------------------------------------------------------------


def reading_id(device_id: str, kind: str, timestamp_ms: int, payload: bytes) -> str:
    """sha256("<deviceId>|<kind>|<timestampMs>|<payload lowercase hex>"), first 32 hex chars."""
    source = f"{device_id}|{kind}|{timestamp_ms}|{payload.hex()}"
    return hashlib.sha256(source.encode("utf-8")).hexdigest()[:32]


def document(device_id: str, kind: str, received_at_ms: int, payload: bytes, platform: str) -> dict:
    """The Firestore document both apps write to users/{uid}/readings/{readingId}."""
    doc = {
        "schema": 1,
        "deviceId": device_id,
        "kind": kind,
        "receivedAtMs": received_at_ms,
        "payloadHex": payload.hex(),
        "platform": platform,
    }
    if kind == "heartRate":
        hr = parse_heart_rate(payload)
        doc["heartRate"] = {
            "bpm": hr["bpm"],
            "sensorContact": hr["sensorContact"],
            "energyExpendedKj": hr["energyExpendedKj"],
            "rr1024": hr["rr1024"],
        }
    else:
        bp = parse_blood_pressure(payload)
        t = bp["timestamp"]
        doc["bloodPressure"] = {
            "unit": bp["unit"],
            "systolic": bp["systolic"],
            "diastolic": bp["diastolic"],
            "meanArterial": bp["meanArterial"],
            "pulseRate": bp["pulseRate"],
            "deviceTime": None if t is None else
                f"{t['year']:04d}-{t['month']:02d}-{t['day']:02d}T{t['hour']:02d}:{t['minute']:02d}:{t['second']:02d}",
            "irregularPulse": bp["irregularPulse"],
            "bodyMovement": bp["bodyMovement"],
        }
    return doc


# --------------------------------------------------------------------------------------
# 4. Reconnect state machine
# --------------------------------------------------------------------------------------
#
# State:  {"phase": ..., "deviceId": str|None, "attempt": int, "stopReason": str|None}
# phases: idle, connecting, discovering, streaming, waitingToRetry, bluetoothOff, stopped
#
# Events (dicts with "type"):
#   connectRequested{deviceId}  user picked a device
#   connected                    link up, services not discovered yet
#   ready                        services found and notifications enabled
#   disconnected{cause}          cause: linkLoss | timeout | gattError | userRequested
#   retryTimerFired
#   bluetoothOff / bluetoothOn
#   permissionDenied
#   disconnectRequested          user tapped Disconnect
#
# Effects (dicts with "type"): connect{deviceId}, discoverServices, scheduleRetry{delayMs},
#   cancelRetry, disconnect
#
# Backoff for failure n (n = 1, 2, ...):
#   capped   = min(maxMs, baseMs * 2^(n-1))
#   delayMs  = floor(capped * (1 - jitter * r)),  r in [0, 1) supplied by the caller
# maxAttempts = None means retry forever (right for a wearable the user expects to reconnect).


def backoff_ms(config: dict, failure: int, r: float) -> int:
    capped = min(config["maxMs"], config["baseMs"] * (2 ** (failure - 1)))
    return math.floor(capped * (1.0 - config["jitter"] * r))


INITIAL = {"phase": "idle", "deviceId": None, "attempt": 0, "stopReason": None}


def reduce(state: dict, event: dict, config: dict, r: float) -> tuple[dict, list[dict]]:
    s = dict(state)
    phase = s["phase"]
    t = event["type"]
    effects: list[dict] = []

    if t == "permissionDenied":
        if phase == "waitingToRetry":
            effects.append({"type": "cancelRetry"})
        s.update(phase="stopped", stopReason="unauthorized", attempt=0)
        return s, effects

    if t == "connectRequested":
        s.update(deviceId=event["deviceId"], attempt=0, stopReason=None)
        if phase == "waitingToRetry":
            effects.append({"type": "cancelRetry"})
        if phase == "bluetoothOff":
            return s, effects  # stay off; we connect when the radio comes back
        s["phase"] = "connecting"
        effects.append({"type": "connect", "deviceId": s["deviceId"]})
        return s, effects

    if t == "disconnectRequested":
        if phase == "waitingToRetry":
            effects.append({"type": "cancelRetry"})
        if phase in ("connecting", "discovering", "streaming"):
            effects.append({"type": "disconnect"})
        s.update(phase="idle", deviceId=None, attempt=0, stopReason=None)
        return s, effects

    if t == "bluetoothOff":
        if phase in ("idle", "stopped", "bluetoothOff"):
            return s, effects
        if phase == "waitingToRetry":
            effects.append({"type": "cancelRetry"})
        s["phase"] = "bluetoothOff"
        return s, effects

    if t == "bluetoothOn":
        if phase != "bluetoothOff":
            return s, effects
        if s["deviceId"] is None:
            s["phase"] = "idle"
            return s, effects
        s.update(phase="connecting", attempt=0)
        effects.append({"type": "connect", "deviceId": s["deviceId"]})
        return s, effects

    if t == "connected" and phase == "connecting":
        s["phase"] = "discovering"
        effects.append({"type": "discoverServices"})
        return s, effects

    if t == "ready" and phase == "discovering":
        s.update(phase="streaming", attempt=0)
        return s, effects

    if t == "disconnected" and phase in ("connecting", "discovering", "streaming"):
        if event["cause"] == "userRequested":
            s.update(phase="idle", deviceId=None, attempt=0)
            return s, effects
        failure = s["attempt"] + 1
        limit = config.get("maxAttempts")
        if limit is not None and failure > limit:
            s.update(phase="stopped", stopReason="gaveUp", attempt=0)
            return s, effects
        s.update(phase="waitingToRetry", attempt=failure)
        effects.append({"type": "scheduleRetry", "delayMs": backoff_ms(config, failure, r)})
        return s, effects

    if t == "retryTimerFired" and phase == "waitingToRetry":
        s["phase"] = "connecting"
        effects.append({"type": "connect", "deviceId": s["deviceId"]})
        return s, effects

    # Anything else is ignored: late callbacks after a state change are normal in BLE stacks.
    return s, effects


# --------------------------------------------------------------------------------------
# Vector definitions
# --------------------------------------------------------------------------------------


def hr_vectors() -> list[dict]:
    cases = [
        ("UC-02 uint8 bpm, contact not supported", "0048"),
        ("UC-02 uint8 bpm, contact detected", "0648"),
        ("UC-02 uint8 bpm, contact supported but not detected", "0450"),
        ("UC-02 uint16 bpm", "012c01"),
        ("UC-02 energy expended present", "08480a01"),
        ("UC-02 one RR interval", "1048ff03"),
        ("UC-02 two RR intervals and contact detected", "164b00040004"),
        ("UC-02 every field: uint16 bpm, contact, energy, RR", "1f5a00e803800380040004"),
        ("UC-02 error: empty payload", ""),
        ("UC-02 error: flags only", "00"),
        ("UC-02 error: uint16 bpm cut short", "015a"),
        ("UC-02 error: energy flag without bytes", "0848"),
        ("UC-02 error: RR flag with odd trailing byte", "104800"),
        ("UC-02 error: RR flag with no RR bytes", "1048"),
        ("UC-02 error: zero bpm", "0000"),
    ]
    return [_vector(name, "heartRate", hx) for name, hx in cases]


def bp_vectors() -> list[dict]:
    def sf(m: int, e: int = 0) -> str:
        raw = encode_sfloat(m, e)
        return f"{raw & 0xFF:02x}{raw >> 8:02x}"

    def ts(y: int, mo: int, d: int, h: int, mi: int, s: int) -> str:
        return f"{y & 0xFF:02x}{y >> 8:02x}{mo:02x}{d:02x}{h:02x}{mi:02x}{s:02x}"

    nan = f"{SFLOAT_NAN & 0xFF:02x}{SFLOAT_NAN >> 8:02x}"
    cases = [
        ("UC-03 minimal mmHg reading", "00" + sf(120) + sf(80) + sf(93)),
        ("UC-03 kPa with decimal exponent", "01" + sf(160, -1) + sf(107, -1) + sf(124, -1)),
        ("UC-03 mean arterial pressure not available", "00" + sf(118) + sf(76) + nan),
        ("UC-03 with device timestamp", "02" + sf(121) + sf(79) + sf(93) + ts(2026, 11, 19, 14, 5, 9)),
        ("UC-03 timestamp of zeros means unknown time", "02" + sf(121) + sf(79) + sf(93) + ts(0, 0, 0, 0, 0, 0)),
        ("UC-03 with pulse rate", "04" + sf(130) + sf(85) + sf(100) + sf(72)),
        ("UC-03 with user id", "08" + sf(130) + sf(85) + sf(100) + "02"),
        ("UC-03 user id 0xFF means unknown user", "08" + sf(130) + sf(85) + sf(100) + "ff"),
        ("UC-03 irregular pulse and body movement in status", "10" + sf(142) + sf(91) + sf(108) + "0500"),
        (
            "UC-03 every field",
            "1e" + sf(119) + sf(78) + sf(92) + ts(2026, 1, 2, 3, 4, 5) + sf(64) + "01" + "0000",
        ),
        ("UC-03 positive exponent", "00" + sf(12, 1) + sf(8, 1) + sf(9, 1)),
        ("UC-03 error: too short for the three values", "00" + sf(120) + sf(80)),
        ("UC-03 error: systolic is NaN", "00" + nan + sf(80) + sf(93)),
        ("UC-03 error: timestamp flag without bytes", "02" + sf(120) + sf(80) + sf(93) + "ea07"),
        ("UC-03 error: month 13", "02" + sf(120) + sf(80) + sf(93) + ts(2026, 13, 1, 0, 0, 0)),
        ("UC-03 error: status flag without bytes", "10" + sf(120) + sf(80) + sf(93) + "05"),
    ]
    return [_vector(name, "bloodPressure", hx) for name, hx in cases]


def _vector(name: str, kind: str, hx: str) -> dict:
    data = bytes.fromhex(hx)
    try:
        result = parse_heart_rate(data) if kind == "heartRate" else parse_blood_pressure(data)
        return {"name": name, "kind": kind, "hex": hx, "expected": result}
    except ParseError as e:
        return {"name": name, "kind": kind, "hex": hx, "error": e.code}


def sfloat_vectors() -> list[dict]:
    raws = [0x0078, 0x0050, 0xF4B0, 0xF06B, 0x100C, 0x0FFF, 0x0801 + 0x1000, 0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801, 0x0000, 0x8001]
    return [{"raw": r, "value": sfloat(r)} for r in raws]


def id_vectors() -> list[dict]:
    cases = [
        ("AA:BB:CC:DD:EE:01", "heartRate", 1795113924796, "0648"),
        ("AA:BB:CC:DD:EE:01", "heartRate", 1795113924797, "0648"),
        ("AA:BB:CC:DD:EE:01", "bloodPressure", 1795113924796, "00780050005d00"),
        ("6F1C2A9E-1D7B-4B8E-9C3A-0E5D2F7A1B44", "heartRate", 0, ""),
    ]
    out = []
    for dev, kind, ts, hx in cases:
        out.append({
            "deviceId": dev,
            "kind": kind,
            "timestampMs": ts,
            "hex": hx,
            "expected": reading_id(dev, kind, ts, bytes.fromhex(hx)),
        })
    return out


def document_vectors() -> list[dict]:
    """Readings and the exact Firestore documents for them. The Kotlin and Swift tests build the same
    documents, and the Firestore rules tests write these documents, so all three agree on the shape."""
    cases = [
        ("UC-09 heart rate with RR intervals", "AA:BB:CC:DD:EE:01", "heartRate", 1795113924796, "164b00040004"),
        ("UC-09 heart rate, energy expended", "AA:BB:CC:DD:EE:01", "heartRate", 1795113929796, "0e5ae803"),
        ("UC-09 blood pressure, every field", "6F1C2A9E-1D7B-4B8E-9C3A-0E5D2F7A1B44", "bloodPressure", 1795113924796,
         "1e77004e005c00ea070102030405400001" + "0000"),
        ("UC-09 blood pressure in kPa, mean arterial unknown", "AA:BB:CC:DD:EE:02", "bloodPressure", 1795113924796,
         "01a0f06bf0ff07"),
    ]
    out = []
    for name, dev, kind, ts, hx in cases:
        payload = bytes.fromhex(hx)
        out.append({
            "name": name,
            "deviceId": dev,
            "kind": kind,
            "receivedAtMs": ts,
            "hex": hx,
            "id": reading_id(dev, kind, ts, payload),
            "expected": document(dev, kind, ts, payload, platform="PLATFORM"),
        })
    return out


CONFIG = {"baseMs": 1000, "maxMs": 30000, "jitter": 0.2, "maxAttempts": None}


def reconnect_cases() -> list[dict]:
    D = "AA:BB:CC:DD:EE:01"
    cases = [
        {
            "name": "UC-04 happy path: connect, discover, stream",
            "events": [{"type": "connectRequested", "deviceId": D}, {"type": "connected"}, {"type": "ready"}],
        },
        {
            "name": "UC-04 link loss while streaming schedules a retry, then reconnects",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "connected"}, {"type": "ready"},
                {"type": "disconnected", "cause": "linkLoss"}, {"type": "retryTimerFired"},
                {"type": "connected"}, {"type": "ready"},
            ],
        },
        {
            "name": "UC-04 consecutive failures back off exponentially up to the cap",
            "events": [{"type": "connectRequested", "deviceId": D}]
            + [e for _ in range(7) for e in ({"type": "disconnected", "cause": "timeout"}, {"type": "retryTimerFired"})],
        },
        {
            "name": "UC-04 jitter shortens the delay by up to 20 percent",
            "config": {**CONFIG},
            "random": [0.0, 0.5, 0.999],
            "events": [{"type": "connectRequested", "deviceId": D}]
            + [e for _ in range(3) for e in ({"type": "disconnected", "cause": "gattError"}, {"type": "retryTimerFired"})],
        },
        {
            "name": "UC-04 a successful connection resets the backoff",
            "events": [
                {"type": "connectRequested", "deviceId": D},
                {"type": "disconnected", "cause": "timeout"}, {"type": "retryTimerFired"},
                {"type": "disconnected", "cause": "timeout"}, {"type": "retryTimerFired"},
                {"type": "connected"}, {"type": "ready"},
                {"type": "disconnected", "cause": "linkLoss"},
            ],
        },
        {
            "name": "UC-05 user disconnect cancels a pending retry and does not reconnect",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "disconnected", "cause": "timeout"},
                {"type": "disconnectRequested"}, {"type": "retryTimerFired"},
            ],
        },
        {
            "name": "UC-05 user disconnect while streaming closes the link",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "connected"}, {"type": "ready"},
                {"type": "disconnectRequested"}, {"type": "disconnected", "cause": "userRequested"},
            ],
        },
        {
            "name": "UC-06 Bluetooth turned off pauses, turned on reconnects",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "connected"}, {"type": "ready"},
                {"type": "bluetoothOff"}, {"type": "disconnected", "cause": "linkLoss"}, {"type": "bluetoothOn"},
            ],
        },
        {
            "name": "UC-06 Bluetooth off during a retry wait cancels the timer",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "disconnected", "cause": "timeout"},
                {"type": "bluetoothOff"}, {"type": "retryTimerFired"}, {"type": "bluetoothOn"},
            ],
        },
        {
            "name": "UC-06 device picked while Bluetooth is off connects when it comes back",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "bluetoothOff"},
                {"type": "connectRequested", "deviceId": D}, {"type": "bluetoothOn"},
            ],
        },
        {
            "name": "UC-07 permission revoked stops for good",
            "events": [
                {"type": "connectRequested", "deviceId": D}, {"type": "disconnected", "cause": "timeout"},
                {"type": "permissionDenied"}, {"type": "retryTimerFired"},
            ],
        },
        {
            "name": "UC-04 a retry limit gives up after the last attempt",
            "config": {**CONFIG, "maxAttempts": 2},
            "events": [
                {"type": "connectRequested", "deviceId": D},
                {"type": "disconnected", "cause": "timeout"}, {"type": "retryTimerFired"},
                {"type": "disconnected", "cause": "timeout"}, {"type": "retryTimerFired"},
                {"type": "disconnected", "cause": "timeout"},
            ],
        },
        {
            "name": "UC-04 late callbacks in the wrong phase are ignored",
            "events": [
                {"type": "connected"}, {"type": "ready"}, {"type": "retryTimerFired"},
                {"type": "connectRequested", "deviceId": D}, {"type": "ready"}, {"type": "retryTimerFired"},
            ],
        },
    ]
    out = []
    for case in cases:
        config = case.get("config", CONFIG)
        randoms = case.get("random", [])
        state = dict(INITIAL)
        steps = []
        failure_index = 0
        for event in case["events"]:
            r = 0.0
            if event["type"] == "disconnected" and randoms:
                r = randoms[min(failure_index, len(randoms) - 1)]
                failure_index += 1
            state, effects = reduce(state, event, config, r)
            steps.append({"event": event, "random": r, "state": dict(state), "effects": effects})
        out.append({"name": case["name"], "config": config, "steps": steps})
    return out


# --------------------------------------------------------------------------------------


def build() -> dict[str, str]:
    gatt = {
        "comment": "Generated by spec/reference.py. Do not edit by hand.",
        "sfloat": sfloat_vectors(),
        "heartRate": hr_vectors(),
        "bloodPressure": bp_vectors(),
        "readingIds": id_vectors(),
        "documents": document_vectors(),
    }
    reconnect = {
        "comment": "Generated by spec/reference.py. Do not edit by hand.",
        "initial": INITIAL,
        "cases": reconnect_cases(),
    }
    return {
        "gatt-vectors.json": json.dumps(gatt, indent=2) + "\n",
        "reconnect-cases.json": json.dumps(reconnect, indent=2) + "\n",
    }


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        stale = [name for name, text in files.items() if (HERE / name).read_text() != text]
        if stale:
            print("Out of date: " + ", ".join(stale) + ". Run python3 spec/reference.py")
            return 1
        print("Vectors are up to date.")
        return 0
    for name, text in files.items():
        (HERE / name).write_text(text)
        print(f"wrote spec/{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
