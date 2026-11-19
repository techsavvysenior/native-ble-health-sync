# Privacy and security notes

Heart rate and blood pressure are health data. This sample keeps the amount of it small and the access to
it narrow, and is honest about what a production app would still need.

## What is stored

On the phone: readings waiting for upload, in the app's private storage (`filesDir` on Android, Application
Support on iOS). Android backup is turned off, so the queue doesn't leave the phone through Auto Backup.

In Firestore, per reading, under `users/{uid}/readings/{id}`: the device id, the kind, the receive time, the
raw payload in hex, the parsed values, and the platform. Nothing else: no name, no email, no location, no
phone model.

The device id is the Bluetooth address on Android and the CoreBluetooth peripheral UUID on iOS. The iOS UUID
is specific to the phone and app. The Android address can identify the physical device, so a production app
should hash it with a per-user salt before upload.

## Who can read it

Firestore rules allow a signed-in user to read and write only `users/{their uid}/readings`. Everything else
in the database is closed. Readings can't be edited or deleted from a phone. The rules tests cover each of
these.

Scan results are never used to infer location: Android declares `BLUETOOTH_SCAN` with `neverForLocation`.

## Known gaps before production

- **Identity.** Anonymous auth means a reinstall loses access to old readings. Production needs account
  linking (Sign in with Apple, Google, or email).
- **App Check.** Without it, someone with the API key can call Firestore from outside the apps (the rules
  still apply). Enable App Check with Play Integrity and App Attest.
- **Deletion and export.** No in-app way to delete or export your data. Both are needed for GDPR-style
  rights; deletion belongs in a Cloud Function, since the rules block client deletes on purpose.
- **Regulation.** Nothing here makes the app a medical device or HIPAA-ready. That needs a BAA with the cloud
  provider, audit logging, and a regulatory assessment of the intended use.
- **Encryption on the phone.** The queue file relies on the operating system's file encryption. Apps with
  stricter requirements should encrypt it with a Keystore or Keychain key.
