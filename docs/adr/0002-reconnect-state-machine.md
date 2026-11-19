# 2. Reconnect as a pure state machine

Status: accepted

## Context

Most Bluetooth app bugs are reconnect bugs: a retry loop that never stops, a retry that fires after the
user disconnected, a stale callback that reconnects to the wrong device, a connection that silently gives
up. These live in callback code that is hard to test because it needs a radio.

## Decision

All reconnect decisions live in one pure function, `reduce(state, event, config, random)`, returning the
next state and a list of effects (connect, discover, schedule a retry, cancel it, disconnect). The platform
code turns Bluetooth callbacks into events and carries out effects. It never decides anything.

The random number for jitter is passed in, so tests are deterministic and the shared scenarios can fix it.

Platform behavior that differs stays in the platform layer:

- Android's `connectGatt` times out after about 30 seconds, so a device out of range produces a stream of
  failures, each followed by a backoff delay. Every `BluetoothGatt` is closed after a disconnect, because
  leaked clients are the most common cause of status 133 on the next attempt.
- iOS `connect` never times out. A pending connection simply completes when the device returns, even while
  the app is suspended, and state restoration relaunches the app for it. On iOS the `connecting` phase can
  last for hours, which is the intended behavior.

## Consequences

- 13 scenarios cover every transition and are checked step by step on both platforms.
- The Bluetooth classes are thin enough to review line by line, and are verified on devices with the
  checklist in the test plan.
- New behavior (for example a different policy for a one-shot cuff) is a config change or a new event, with
  new scenarios in the reference.
