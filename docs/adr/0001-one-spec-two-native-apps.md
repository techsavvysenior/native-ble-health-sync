# 1. One spec, two native apps

Status: accepted

## Context

The app is native on both platforms: Kotlin with BluetoothGatt on Android, Swift with CoreBluetooth on
iOS. Native gives full control of background behavior and the Bluetooth stacks, which is the reason to
choose it. The cost is two implementations of the same logic, and two implementations drift. A parser that
reads the SFLOAT exponent one way in Kotlin and another in Swift shows two different blood pressures for
the same cuff, and nobody notices until a user compares phones.

## Decision

The logic that must agree (GATT parsing, SFLOAT, reading ids, the Firestore document, the reconnect state
machine) is defined once in `spec/reference.py`, a short Python file with no dependencies. It generates
`spec/gatt-vectors.json` and `spec/reconnect-cases.json`. The Kotlin `:core` tests and the Swift
`PulseLogCore` tests both load those files and assert identical results, down to the exact retry delay in
milliseconds. CI fails if the JSON is out of date with the reference.

Changing shared behavior means changing the reference first, regenerating, and making both platforms pass.

## Consequences

- A new device quirk becomes a vector: paste the payload into the reference, regenerate, and both apps are
  tested against it.
- The core modules hold no platform code, so they run on a laptop in seconds.
- Floating point must be computed the same way on all three. SFLOAT uses exact integer powers of ten, and
  the backoff formula is written in the same order of operations everywhere.
- Kotlin Multiplatform could share the code itself instead of the tests. It was not chosen because this
  repository shows native work on each platform, and many teams keep separate native codebases. The vectors
  give most of the safety either way.
