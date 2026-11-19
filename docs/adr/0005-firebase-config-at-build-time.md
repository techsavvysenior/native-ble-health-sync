# 5. Firebase configuration at build time, optional

Status: accepted

## Context

The usual setup commits `google-services.json` and `GoogleService-Info.plist`. In a public repository that
publishes a project's keys, and it forces everyone who clones the code to create a Firebase project before
the app will even build.

## Decision

Both apps build and run with no Firebase configuration. The project id, app id and API key come from
`~/.gradle/gradle.properties` (or `PULSELOG_FIREBASE_*` environment variables) on Android and from a
gitignored `Secrets.xcconfig` on iOS, and are passed to `FirebaseOptions` in code. If any value is missing,
the app runs local-only and says so on screen.

## Consequences

- Clone, build, run. CI builds both apps with Firebase linked but not configured.
- No Google Services Gradle plugin. Anything that relies on it (Crashlytics mapping upload, for example)
  would need its own setup.
- Firebase API keys are not secrets in the usual sense, since they identify the project and access is
  controlled by the rules. Keeping them out of the repository is about hygiene and about not tying the sample
  to one project.
