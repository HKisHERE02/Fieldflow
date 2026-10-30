# Execution and verification results — October 30, 2026

This document records the initial build and verification, followed by the manual end-to-end testing and GitHub Actions results confirmed on October 30, 2026. See [focused hardening results](hardening-results.md) for retry/photo hardening and the initial APK/test evidence.

**Historical counts and artifact hashes below describe the original verification run; they should not be interpreted as freshly recomputed for every later build.**

## Environment

Initial verification: Windows, JDK 17.0.7, Gradle Wrapper 8.9, Kotlin 2.1.21, AGP 8.7.2, Android SDK 35, lint 8.8.2. Device testing used a Pixel 8 Pro Android 14/API 34 emulator. PostgreSQL 16.4 ran from a temporary workspace-local runtime bound to `127.0.0.1:55432`; Docker was not installed. Ktor was started from the built application distribution on port 8080.

The temporary Ktor process, PostgreSQL server and emulator were stopped after the initial verification, then restarted as needed for subsequent October 30 end-to-end checks. Their current running/stopped state is not asserted here.

## Initial build and test outcomes

| Command / check | Result |
|---|---|
| `.\gradlew.bat :app:assembleDebug` | PASS; debug APK generated and installed on emulator |
| `.\gradlew.bat :shared:test` | PASS; 18 tests, 0 failures/errors/skips |
| `.\gradlew.bat :app:testDebugUnitTest` | PASS; 26 tests (19 repository + 7 sync engine), 0 failures/errors/skips |
| `.\gradlew.bat :backend:build` | PASS; 18 tests, 0 failures/errors/skips, including real PostgreSQL integration |
| `.\gradlew.bat -p backend build` | PASS; backend and shared module built/tested without Android project configuration |
| `.\gradlew.bat :app:assembleDebugAndroidTest` | PASS; instrumentation APK generated |
| `.\gradlew.bat :app:connectedDebugAndroidTest` | PASS; 3 Compose workflow tests; 2 opt-in connected stages skipped in that initial default invocation and executed separately below |
| `.\gradlew.bat :app:lintDebug` | PASS; 0 errors, 57 warnings (pinned dependency/target versions and version-catalog recommendations) |
| Connected preparation stage | PASS; 1 test, 0.558 seconds |
| Actual server-outage stage | PASS; 1 test, 20.204 seconds |
| Restart / WorkManager recovery / replay / conflict stage | PASS; 1 test, 1.695 seconds |
| Runtime screenshots | Captured and visually inspected in light/dark themes at 360 dp width |

The **initial snapshot counted 68 distinct passing tests/check stages**: 18 shared + 26 Android JVM + 18 backend + 3 Compose + 3 connected stages. Repeated executions were not added to the count. The outage test was added after the initial default emulator run and executed separately. Later changes to tests are not reflected in this historical number.

The initial combined headless command was:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/fieldflow'
$env:TEST_DATABASE_USER='fieldflow'
$env:TEST_DATABASE_PASSWORD=''
.\gradlew.bat :shared:test :app:testDebugUnitTest :app:assembleDebug :backend:build
```

The empty test password above applied **only to the original temporary loopback-only PostgreSQL runtime**. Later local tests used the configured database password. The normal Docker configuration requires a password in `.env`.

## Initial connected-journey evidence

The tests exercised the installed app, Keystore session storage, file-backed Room, Retrofit, WorkManager, Ktor and PostgreSQL:

1. Authenticated and downloaded assigned work, saved five answers, a defect and a JPEG fixture, then submitted locally.
2. Force-stopped the app and stopped Ktor. An actual upload attempt failed; the same operation ID/payload, all five answers and the photo file remained pending.
3. Restarted Ktor and force-stopped the app again. Restored the encrypted session and Room queue; scheduled real WorkManager work.
4. Confirmed photo upload and inspection synchronization. Replayed the operation twice; the server retained a single version-1 inspection.
5. Submitted a new operation ID with stale expected version 0. The worker recorded `CONFLICT` with the server snapshot, preserving all local answers.

Direct PostgreSQL inspection confirmed record `7d707d66-482e-4b64-a9c0-9c5c53a00e44`, **one photo**, **version 1**, one synchronization audit and one version-conflict audit. Stage output is retained in [prepare](evidence/connected-prepare.txt), [outage](evidence/connected-outage.txt), and [restore](evidence/connected-restore.txt).

The generated JPEG fixture verifies upload/retry persistence independently of camera hardware. Separately, `WorkflowTest.cameraPhotoPersistsWithDefect` captured a CameraX JPEG from the emulator camera, persisted its file and Room association, and verified that it remained visible after Activity recreation in the initial test evidence.

## Additional end-to-end verification — October 30, 2026

The following checks were performed after the initial verification snapshot:

| Check | Observed result |
|---|---|
| Authenticated Android session | Logged in as the fictional technician `alex@fieldflow.example` |
| Offline inspection and process restart | Inspection `a9b40753-941c-4d89-bdf3-6621224a12fa` stayed `PENDING` after force-stop/reopen while offline |
| Offline-to-online inspection synchronization | Android showed `SYNCED` after one attempt; PostgreSQL contained exactly one matching record, owned by `tech-alex`, associated with `wo-3`, version `1` |
| Real PostgreSQL replay/integration test | `ServerTest.postgresIntegrationWhenConfigured` ran against separate database `fieldflow_integration_test`; XML reported **1 test, 0 skipped, 0 failures, 0 errors** |
| Android conflict tests | Targeted Gradle unit-test run succeeded for `structuredConflictKeepsLocalAnswers`, `manualRetryPreservesConflictAndServerSnapshot`, and `conflictPreservesOriginalAndCreatesDraft` |
| CameraX defect evidence | Captured a photo through the defect review screen; image and defect remained visible after force-stop/reopen offline |
| Offline photo synchronization | Inspection `a21f3d4c-a18c-4e2d-a3e9-c375230e1f6f` stored in PostgreSQL, version `1`, with exactly one photo reference and one attachment |
| Attachment integrity | Attachment `09a5b7be-ed3e-448d-bcbf-82b6879e4aec`, 67,426 bytes; JPEG header and SHA-256 content hash valid; attachment ID referenced by inspection |
| Equipment lookup | Manual code `FF-001` opened the Rooftop air handler, including when offline |
| QR recognition | Emulator CameraX/ML Kit scanner recognized a QR code for `fieldflow://asset/FF-001` and navigated to the asset |
| Network status banner | Fixed stale online/offline indication in `FieldViewModel.kt`; user confirmed automatic transitions without reopening app |
| GPS/location | **Not verified:** the emulator location request timed out; optional location capture remains an open issue |
| Combined Gradle regression | `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :backend:test` completed **BUILD SUCCESSFUL** |

In the combined regression run, **17 backend tests passed** and `postgresIntegrationWhenConfigured` was **skipped in that run only**, because its test database environment variables were not supplied. The PostgreSQL integration test had already run successfully in isolation with its own XML confirmation. The combined build completed lint and APK tasks successfully; it does **not** substitute for a fresh full instrumentation run.

An informational `Failed to create image decoder with message 'invalid input'` appeared during the successful combined build and did not cause a Gradle failure. Further investigation is warranted only if it reproduces in a user-facing feature or test failure.

## GitHub repository and hosted CI — October 30, 2026

- Public repository: [HKisHERE02/Fieldflow](https://github.com/HKisHERE02/Fieldflow).
- Default branch: `main`.
- The initial GitHub Actions workflow failed during Android SDK setup because `android-actions/setup-android@v3` attempted to install an obsolete `tools` package.
- Workflow configuration was changed to `android-actions/setup-android@v4` in `.github/workflows/ci.yml`.
- GitHub Actions **Build and verify #2** completed successfully (green) on `main` for commit `9e91760` (`Fix Android SDK setup in GitHub Actions`), as shown in the Actions run on October 30, 2026.
- Hosted CI is therefore **VERIFIED for that commit**. This does not imply every optional connected test stage ran; staged tests require their documented arguments and environment.

## Artifacts from the initial verification snapshot

- APK: `app/build/outputs/apk/debug/app-debug.apk` (initially 44,439,232 bytes).
- Initial APK SHA-256: `e3fcf3da977ab0e7016099ff478a6d50b6b779bf33b38ad974f843f8138e3a65` (**not reverified for later builds**).
- Android JVM report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Device report: `app/build/reports/androidTests/connected/debug/index.html`.
- Backend report: `backend/build/reports/tests/test/index.html`.
- Shared report: `shared/build/reports/tests/test/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- Backend distributions: `backend/build/distributions/`.
- Runtime screenshots: `docs/screenshots/dashboard.png`, `docs/screenshots/dashboard-dark.png`.

## Verified on emulator, NOT verified on physical hardware

QR decoding through the emulator's virtual camera, CameraX photo capture, manual offline equipment lookup, network-banner transitions and offline synchronization were verified in the emulator.

**Physical-hardware** optical QR scanning, real GPS fixes and approximate-location behavior, OEM permissions/background-work behavior, TalkBack accessibility, API 26 execution and real-device airplane-mode transitions remain **NOT VERIFIED**. See `docs/demo-script.md` for procedures.

The emulator GPS/location request **timed out on October 30**; no successful coordinate capture was observed in that test. Location is optional in the demonstrated inspection workflow, but its success path still needs testing.

## Incomplete / deliberately limited

- Remote FCM delivery is not implemented; in-app notifications work from persisted state.
- Cross-device remote-photo cache hydration is not implemented; local photos persist and the backend supports authenticated photo retrieval.
- Change downloads are bounded full snapshots, with no cursor pagination or reassignment/tombstone protocol.
- Conflict copy retains the original and starts a new draft; there is no answer-by-answer merge or automatic photo reassignment.
- Docker Compose is configured but **not verified on the local Windows machine**; PostgreSQL itself was tested directly.
- No release signing, public hosting of the backend, or production deployment was performed. A **debug APK** and a public GitHub source repository are available; this is not a Play Store release.
- Complete current instrumentation re-execution across all test stages and physical-device/API 26 verification remain outstanding.

Initial compilation/test/lint failures were corrected and the affected checks rerun. No tests were commented out, assertions weakened to hide application defects, or lint detectors disabled. Lint's analysis-engine compatibility was resolved using the documented independent lint version; networking XML errors were fixed explicitly.