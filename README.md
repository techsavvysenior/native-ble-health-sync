# Pulse Log: native Bluetooth health readings on iOS and Android

[![CI](https://github.com/techsavvysenior/native-ble-health-sync/actions/workflows/ci.yml/badge.svg)](https://github.com/techsavvysenior/native-ble-health-sync/actions/workflows/ci.yml)

Two native apps, Swift for iOS and Kotlin for Android, that connect to standard Bluetooth heart rate monitors and blood pressure cuffs, survive dropped connections, and sync readings to Firebase without duplicates.

The hard parts of a Bluetooth app are the same on both platforms, so they are written once as a spec and held to it on both sides:

- **One definition of correct.** [`spec/reference.py`](spec/reference.py) is a small Python reference for the parsers, the reading ids, the Firestore document and the reconnect state machine. It generates the test vectors in `spec/`, and the Kotlin and Swift test suites both load the same files. If the two apps ever disagree on a byte, a test fails. [ADR 1](docs/adr/0001-one-spec-two-native-apps.md)
- **Reconnect as a pure state machine.** Link loss, timeouts, Bluetooth switched off, permission revoked, the user tapping Disconnect: every case is a transition in one function with no Bluetooth or timers inside, checked step by step against 13 shared scenarios. The platform code only turns callbacks into events and events into calls. [ADR 2](docs/adr/0002-reconnect-state-machine.md), [diagram](docs/reconnect.md)
- **No duplicate readings.** Each reading's id is a hash of the device, the kind, the time and the raw bytes. A retried upload rewrites the same document, and the Firestore rules accept an identical rewrite but refuse any change. [ADR 3](docs/adr/0003-deterministic-ids-and-idempotent-uploads.md)

## What it shows

| | Android | iOS |
|---|---|---|
| Language and UI | Kotlin 2.4, Jetpack Compose, Material 3 | Swift 6 language mode, SwiftUI, Observation |
| Bluetooth | `BluetoothGatt` with a serialized operation queue, `close()` after every disconnect | `CoreBluetooth` with state restoration, so iOS can relaunch the app for a reading |
| Background | Foreground service of type `connectedDevice` | `bluetooth-central` background mode, pending connections that wait for the device |
| Permissions | `BLUETOOTH_SCAN` (never for location) and `BLUETOOTH_CONNECT` on 12+, location on 11 and below | `NSBluetoothAlwaysUsageDescription`, unauthorized state handled |
| Parsing | Heart Rate Measurement 0x2A37 (uint8/uint16 bpm, sensor contact, energy, RR intervals) and Blood Pressure Measurement 0x2A35 (IEEE 11073 SFLOAT, kPa or mmHg, device time, pulse, status bits) | Same, same vectors |
| Offline queue | File-backed, capped, deduplicated, written atomically | Same rules |
| Cloud | Firestore batch writes, anonymous auth, optional at build time | Same |

## Quality

| | Count | Command |
|---|---:|---|
| Shared vector cases (parsers, SFLOAT, ids, documents) | 53 | run by both suites |
| Shared reconnect scenarios, checked step by step | 13 | run by both suites |
| Kotlin tests (`:core` and `:app`) | 16 | `cd android && ./gradlew :core:test :app:testDebugUnitTest` |
| Swift tests (Swift Testing) | 14 | `cd ios/PulseLogCore && swift test` |
| Firestore rules tests on the emulator | 28 | `cd firebase && npm ci && npm run test:emulator` |
| Use cases, each traced to tests or marked manual | 12 | `node scripts/traceability.mjs --check` |

CI runs all of it on every push: the spec check, Android unit tests plus lint plus a debug build, the Swift package tests on macOS, an iOS simulator build of the app with Firebase linked, and the rules tests against the Firestore emulator.

- [Use cases](docs/use-cases.md) and the generated [traceability matrix](docs/traceability.md)
- [Test plan](docs/test-plan.md): what each level covers, and the on-device checklist for what can't be automated
- [Privacy and security notes](docs/privacy.md): what is stored, what is not, and the known gaps
- [Contributing](CONTRIBUTING.md)

## Layout

```
spec/                 Python reference and the generated vectors both apps test against
android/
  core/               Plain Kotlin: parsers, reconnect state machine, ids, upload queue (JVM tests)
  app/                Compose UI, BluetoothGatt client, foreground service, Firestore sync
ios/
  PulseLogCore/       Swift package: the same core logic (Swift Testing)
  App/                SwiftUI, CoreBluetooth manager, Firestore sync
  project.yml         XcodeGen spec for the app target
firebase/             Firestore rules and their emulator tests
docs/                 Use cases, traceability, test plan, reconnect diagram, privacy notes, ADRs
scripts/              Traceability check
```

## Run it

**Android.** Android Studio with JDK 17. Open `android/` and run the `app` configuration on a phone; the emulator has no Bluetooth radio.

```bash
cd android
chmod +x gradlew   # the executable bit isn't kept by every git client
./gradlew :app:installDebug
```

**iOS.** Xcode 26.2 or later (Firebase 13 needs it) and [XcodeGen](https://github.com/yonaskolb/XcodeGen). Run on an iPhone; the simulator has no Bluetooth.

```bash
cd ios
brew install xcodegen
xcodegen generate
open PulseLog.xcodeproj
```

**Firebase (optional).** Without it both apps run local-only and keep readings in their queue. To sync, create a Firebase project, enable Anonymous sign-in and Firestore, deploy the rules (`cd firebase && npx firebase deploy --only firestore:rules --project <id>`), then:

- Android: put `pulselog.firebase.projectId`, `pulselog.firebase.applicationId` and `pulselog.firebase.apiKey` in `~/.gradle/gradle.properties`.
- iOS: copy `ios/Support/Secrets.example.xcconfig` to `Secrets.xcconfig` and fill it in.

No Firebase config file is committed and none is needed; see [ADR 5](docs/adr/0005-firebase-config-at-build-time.md).

**Any heart rate strap or blood pressure cuff that uses the standard Bluetooth services works.** Without one, an ESP32 board running Espressif's NimBLE heart rate example (`blehr` in ESP-IDF) is a cheap stand-in for the heart rate side.

## Limits

This is a sample, not a medical device and not a certified product.

- CI proves the code compiles, the shared logic matches the reference and the rules hold. It does not run Bluetooth. Scanning, background behavior and real devices are covered by the manual checklist in the test plan.
- Readings are stored under an anonymous Firebase user. There is no account linking, data export or delete flow, and no App Check; see the privacy notes.
- Only the two standard Bluetooth SIG profiles are supported. Vendor-specific protocols (many fitness bands, some OMRON features) need their own parsers.
- Heart rate is uploaded once every 5 seconds, not every beat, to keep Firestore costs reasonable ([ADR 4](docs/adr/0004-sample-heart-rate-for-upload.md)). The live screen shows every value.

## Versions

Checked November 2026. Android: AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, Compose BOM 2026.11.01, compileSdk 37.2, targetSdk 36, minSdk 26, Firebase BoM 35.2.0. iOS: Xcode 26, Swift 6.2 tools, iOS 17+, Firebase Apple SDK 13.2.0. Rules tests: firebase-tools 15.37.0, @firebase/rules-unit-testing 6.0.0, Node 22.

## License

MIT
