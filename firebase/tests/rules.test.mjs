// Firestore rules tests. Run against the emulator: npm run test:emulator
// The valid documents come from spec/gatt-vectors.json "documents", the same documents the Android and
// iOS tests build, so a change to the document shape fails here and in both apps at once.
import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, getDocs, collection, setDoc, writeBatch } from 'firebase/firestore';

const vectors = JSON.parse(readFileSync(new URL('../../spec/gatt-vectors.json', import.meta.url), 'utf8'));
const documents = vectors.documents.map((v) => ({ ...v, data: { ...v.expected, platform: 'android' } }));
const heart = documents.find((d) => d.kind === 'heartRate');
const pressure = documents.find((d) => d.kind === 'bloodPressure');

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-pulselog',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

const db = (uid) => (uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore());
const reading = (firestore, uid, id) => doc(firestore, 'users', uid, 'readings', id);
const seed = (uid, id, data) =>
  env.withSecurityRulesDisabled((ctx) => setDoc(reading(ctx.firestore(), uid, id), data));

describe('who can read and write', () => {
  test('UC-09 the owner can write every reference document', async () => {
    for (const d of documents) {
      await assertSucceeds(setDoc(reading(db('alice'), 'alice', d.id), d.data));
    }
  });

  test('UC-09 the owner can read and list their readings', async () => {
    await seed('alice', heart.id, heart.data);
    await assertSucceeds(getDoc(reading(db('alice'), 'alice', heart.id)));
    await assertSucceeds(getDocs(collection(db('alice'), 'users', 'alice', 'readings')));
  });

  test("UC-09 another user can't read or write someone else's readings", async () => {
    await seed('alice', heart.id, heart.data);
    await assertFails(getDoc(reading(db('mallory'), 'alice', heart.id)));
    await assertFails(getDocs(collection(db('mallory'), 'users', 'alice', 'readings')));
    await assertFails(setDoc(reading(db('mallory'), 'alice', pressure.id), pressure.data));
  });

  test('UC-09 a signed-out client can do nothing', async () => {
    await seed('alice', heart.id, heart.data);
    await assertFails(getDoc(reading(db(null), 'alice', heart.id)));
    await assertFails(setDoc(reading(db(null), 'alice', pressure.id), pressure.data));
  });

  test('UC-09 anything outside users/{uid}/readings is closed', async () => {
    await assertFails(setDoc(doc(db('alice'), 'users', 'alice'), { name: 'Alice' }));
    await assertFails(setDoc(doc(db('alice'), 'devices', 'x'), { a: 1 }));
  });
});

describe('readings are a record', () => {
  test('UC-10 a retried upload with identical data succeeds', async () => {
    const alice = db('alice');
    await assertSucceeds(setDoc(reading(alice, 'alice', heart.id), heart.data));
    await assertSucceeds(setDoc(reading(alice, 'alice', heart.id), heart.data));
  });

  test('UC-10 a batch retried after a lost acknowledgement succeeds', async () => {
    const alice = db('alice');
    const write = () => {
      const batch = writeBatch(alice);
      documents.forEach((d) => batch.set(reading(alice, 'alice', d.id), d.data));
      return batch.commit();
    };
    await assertSucceeds(write());
    await assertSucceeds(write());
  });

  test('UC-10 changing a stored reading is refused', async () => {
    await seed('alice', heart.id, heart.data);
    const edited = { ...heart.data, heartRate: { ...heart.data.heartRate, bpm: 60 } };
    await assertFails(setDoc(reading(db('alice'), 'alice', heart.id), edited));
  });

  test('UC-10 deleting a reading is refused, even for the owner', async () => {
    await seed('alice', heart.id, heart.data);
    await assertFails(deleteDoc(reading(db('alice'), 'alice', heart.id)));
  });
});

describe('document shape', () => {
  const rejects = (name, mutate, id = heart.id, base = heart.data) =>
    test(`UC-09 rejects ${name}`, async () => {
      const data = structuredClone(base);
      mutate(data);
      await assertFails(setDoc(reading(db('alice'), 'alice', id), data));
    });

  rejects('an id that is not 32 lowercase hex characters', () => {}, 'NOT-A-READING-ID');
  rejects('an unknown top-level field', (d) => { d.note = 'hello'; });
  rejects('a missing field', (d) => { delete d.deviceId; });
  rejects('an unknown kind', (d) => { d.kind = 'glucose'; });
  rejects('a heart rate document carrying blood pressure', (d) => { d.bloodPressure = pressure.data.bloodPressure; });
  rejects('a kind that does not match its values', (d) => { d.kind = 'bloodPressure'; });
  rejects('a zero heart rate', (d) => { d.heartRate.bpm = 0; });
  rejects('a decimal heart rate', (d) => { d.heartRate.bpm = 72.5; });
  rejects('an unknown sensor contact value', (d) => { d.heartRate.sensorContact = 'maybe'; });
  rejects('a reading timestamped two days in the future', (d) => { d.receivedAtMs = Date.now() + 2 * 86400000; });
  rejects('a reading timestamped in seconds instead of milliseconds', (d) => { d.receivedAtMs = 1795113924; });
  rejects('a payload that is not hex', (d) => { d.payloadHex = 'zz'; });
  rejects('a payload over 32 bytes', (d) => { d.payloadHex = 'ab'.repeat(33); });
  rejects('an unknown platform', (d) => { d.platform = 'web'; });
  rejects('schema version 2', (d) => { d.schema = 2; });
  rejects('a blood pressure unit the app never sends', (d) => { d.bloodPressure.unit = 'psi'; }, pressure.id, pressure.data);
  rejects('a negative systolic value', (d) => { d.bloodPressure.systolic = -1; }, pressure.id, pressure.data);
  rejects('a device time that is not ISO local time', (d) => { d.bloodPressure.deviceTime = 'yesterday'; }, pressure.id, pressure.data);
  rejects('an extra blood pressure field', (d) => { d.bloodPressure.userId = 2; }, pressure.id, pressure.data);
});
