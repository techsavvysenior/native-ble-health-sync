# Use cases

Every behavior the apps promise, with an id. Tests carry the id in their name, and
`node scripts/traceability.mjs` checks that every automated use case has at least one test.
The generated matrix is in [traceability.md](traceability.md).

| Id | Use case | How it is verified |
|---|---|---|
| UC-01 | Find nearby heart rate and blood pressure devices, filtered by service UUID | manual |
| UC-02 | Show live heart rate from a Heart Rate Measurement (0x2A37) notification | automated |
| UC-03 | Record a Blood Pressure Measurement (0x2A35) indication, including SFLOAT values and device time | automated |
| UC-04 | Reconnect on its own after the link drops, backing off from 1 s to 30 s with jitter | automated |
| UC-05 | Stay disconnected after the user taps Disconnect, even if a retry was scheduled | automated |
| UC-06 | Pause while Bluetooth is off and reconnect when it comes back on | automated |
| UC-07 | Stop, and say why, when Bluetooth permission is removed | automated |
| UC-08 | Keep readings on the phone until uploaded, with no duplicates after retries or restarts | automated |
| UC-09 | Only the signed-in user can read and write their readings, and only in the agreed shape | automated |
| UC-10 | An uploaded reading can't be changed or deleted from a phone, but an identical retry is accepted | automated |
| UC-11 | Run local-only when no Firebase project is configured | automated |
| UC-12 | Keep receiving readings with the screen off (Android foreground service, iOS state restoration) | manual |

Manual cases are covered by the release checklist in [test-plan.md](test-plan.md), because they need
a real phone and a real device in range.
