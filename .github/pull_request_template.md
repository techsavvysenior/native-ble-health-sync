## What changed

## Use cases
<!-- UC ids this touches, from docs/use-cases.md -->

## Checklist
- [ ] Behavior that both apps share is changed in `spec/reference.py` first, then `python3 spec/reference.py` regenerated the vectors
- [ ] Kotlin and Swift both pass the regenerated vectors
- [ ] New tests carry a UC id, and `node scripts/traceability.mjs --write` was run
- [ ] Firestore document shape unchanged, or `firestore.rules`, the rules tests and both apps changed together
- [ ] Tried on a real phone with a real device for anything touching Bluetooth (UC-01, UC-12)
