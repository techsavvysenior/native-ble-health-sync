# Contributing

## Workflow

1. Branch from `main`: `feat/<short-name>`, `fix/<short-name>`.
2. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org): `fix(ble): close the GATT client after a setup failure`.
3. Open a pull request with the template. CI must be green.
4. Squash-merge.

## Definition of done

- The behavior is in [docs/use-cases.md](docs/use-cases.md).
- Shared behavior changed in `spec/reference.py` first, `python3 spec/reference.py` regenerated the vectors, and both platforms pass them.
- Each new test carries a use case id in its name, and `node scripts/traceability.mjs --write` has been run.
- Bluetooth changes were tried on a real phone with the device checklist in [docs/test-plan.md](docs/test-plan.md).

## Rules for the code

**Core modules stay platform-free.** `android/core` and `ios/PulseLogCore` must not import Android,
CoreBluetooth, Firebase or UI code. If logic can be tested without a radio, it belongs there.

**The platform layer carries out effects; it doesn't decide.** Retry timing, what to do on Bluetooth off,
when to stop: all of it is in `Reconnect.reduce`. A new `if` about reconnecting in `ConnectionController` or
`BLEManager` is a sign it belongs in the state machine.

**Android GATT.** One operation at a time per connection. Always `close()` a `BluetoothGatt` after it
disconnects. Every Bluetooth call sits behind a permission check.

**iOS.** The central manager runs on the main queue and `BLEManager` is main-actor isolated. Don't move
CoreBluetooth to a background queue without revisiting that.

**Firestore.** The document shape is defined three times on purpose (Kotlin, Swift, rules), and the shared
`documents` vectors keep them equal. Change all three in one pull request.

## Review checklist

- What happens when this callback arrives late, twice, or after the user disconnected?
- What happens with Bluetooth off, permission revoked, or the app in the background?
- Could this create a duplicate reading or lose one?
- Does anything new leave the phone? If so, is it in [docs/privacy.md](docs/privacy.md)?
