# 3. Deterministic reading ids and idempotent uploads

Status: accepted

## Context

Uploads fail in the middle. The app can write a batch, lose the network before the acknowledgement, and
retry. Blood pressure cuffs re-send stored measurements after a reconnect. Firestore keeps offline writes
and replays them. Each of these can create duplicates if a reading gets a new random id every time.

## Decision

A reading's id is the first 32 hex characters of
`sha256("<deviceId>|<kind>|<receivedAtMs>|<payload hex>")`. The same reading always maps to the same
document, so:

- the queue ignores a reading whose id it already holds,
- a retried batch rewrites the same documents,
- the Firestore rules allow an update only when the new data equals the stored data, so the identical
  rewrite succeeds and any real change is refused.

## Consequences

- No duplicates from retries, restarts or replays; the rules tests rewrite a whole batch to prove it.
- Readings are immutable from the phone. Corrections would be new documents or a server-side function.
- Two genuinely different readings with the same bytes in the same millisecond from the same device would
  collide. That can't happen with these characteristics, which notify at most a few times a second.
