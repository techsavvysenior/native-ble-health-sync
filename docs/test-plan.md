# Test plan

## Levels

| Level | What it proves | Where | Runs |
|---|---|---|---|
| Shared vectors | Kotlin and Swift parse every payload, build every id and document, and step through every reconnect scenario exactly like the Python reference | `spec/`, `SharedVectorsTest.kt`, `SharedVectorsTests.swift` | CI, both platforms |
| Unit | Queue rules (dedupe, cap, acknowledge, restart, damaged file), heart rate sampling, Android status mapping, local-only mode | `android/*/src/test`, `ios/PulseLogCore/Tests` | CI |
| Rules | Owner-only access, document shape, identical retries accepted, edits and deletes refused | `firebase/tests` on the Firestore emulator | CI |
| Build | Android lint and debug build; iOS simulator build with Firebase linked, Swift 6 strict concurrency | CI | CI |
| Device | Scanning, real reconnects, background behavior | Checklist below | Before each release |

Bluetooth itself can't run in CI: neither the Android emulator nor the iOS simulator has a radio. That is why
everything that can be pulled out of the Bluetooth callbacks (parsing, ids, the state machine, the queue)
lives in the core modules, where it is tested on every push.

## Risk-based priorities

1. **Wrong numbers.** A parser bug shows a wrong blood pressure. Highest priority: every flag combination in
   both characteristics is a shared vector, including SFLOAT special values and malformed payloads.
2. **Silent data loss.** A reading that never reaches the cloud. Covered by queue tests (acknowledge only
   after upload, survive restart, capped with a visible dropped count) and the idempotent upload design.
3. **Duplicate data.** A retried batch creating copies. Covered by deterministic ids, queue dedupe and the
   rules test that rewrites a whole batch.
4. **A connection that never comes back.** Covered by the reconnect scenarios on both platforms and the device
   checklist.
5. **Data visible to the wrong user.** Covered by the rules tests.

## Device checklist (UC-01, UC-12 and a live pass over the rest)

Run on one Android phone (Android 12 or later) and one iPhone, with a heart rate strap or the ESP32 example.

- [ ] Scan lists the device within 5 seconds and shows nothing that lacks the Heart Rate or Blood Pressure service (UC-01)
- [ ] Live bpm updates about once a second; the RR interval line appears for straps that send it (UC-02)
- [ ] A cuff measurement shows systolic, diastolic, pulse and the irregular pulse flag when set (UC-03)
- [ ] Walk out of range for 2 minutes, come back: the app reconnects without a tap (UC-04)
- [ ] Power the device off and on: the app reconnects (UC-04)
- [ ] Tap Disconnect during a retry wait: no reconnect afterwards (UC-05)
- [ ] Turn Bluetooth off and on in Control Center or Quick Settings: the app reconnects (UC-06)
- [ ] Revoke Bluetooth permission in Settings while connected: the app stops and says why (UC-07)
- [ ] Airplane mode for 10 minutes while recording, then back online: pending count drains to 0 and Firestore has no duplicates (UC-08)
- [ ] Screen off for 15 minutes while connected: readings keep arriving (UC-12). Android: the notification stays. iOS: background the app, lock the phone
- [ ] iOS only: force a memory-pressure termination from Xcode while connected, wait for a reading: iOS relaunches the app and the reading is queued (UC-12)

## Release criteria

- CI green on `main`.
- Device checklist passed on both platforms for any change to `ble/`, `BLEManager.swift` or the core state machine.
- No change to the document shape unless the rules, the rules tests and both apps changed in the same pull request.
