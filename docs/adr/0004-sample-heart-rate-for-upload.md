# 4. Sample heart rate for upload, keep every blood pressure reading

Status: accepted

## Context

Heart rate straps notify about once a second. One document per notification is 3,600 Firestore writes an
hour per user, which is expensive and rarely useful. A blood pressure reading is one indication per
measurement and every one matters.

## Decision

The live screen shows every heart rate value. The upload keeps one heart rate value per device every
5 seconds (`HeartRateSampler`). Every blood pressure reading is kept.

## Consequences

- 720 heart rate documents an hour while streaming instead of 3,600.
- Beat-to-beat analysis (heart rate variability from RR intervals) would need a different design, such as
  per-minute documents holding the RR arrays. The raw payload is kept on each sampled document, so the
  format can change without losing information on what is stored.
