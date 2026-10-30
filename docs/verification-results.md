# Execution results — October 30, 2026

This records the initial build. See [focused hardening results](hardening-results.md) for the subsequent retry/photo fixes and current APK/test verification.

## Environment

Windows, JDK 17.0.7, Gradle Wrapper 8.9, Kotlin 2.1.21, AGP 8.7.2, Android SDK 35, lint 8.8.2. Device testing ran on the installed Pixel 8 Pro Android 14/API 34 emulator. PostgreSQL 16.4 ran from a temporary workspace-local runtime bound to 127.0.0.1:55432; Docker was not installed. Ktor was started from the built application distribution on port 8080.

After verification, the temporary Ktor process, PostgreSQL test server and emulator were stopped. Build artifacts, screenshots and test evidence remain in the workspace.

## Build and test outcomes

| Command / check | Result |
|---|---|
| `.\gradlew.bat :app:assembleDebug` | PASS; real debug APK generated and installed on emulator |
| `.\gradlew.bat :shared:test` | PASS; 18 tests, 0 failures/errors/skips |
| `.\gradlew.bat :app:testDebugUnitTest` | PASS; 26 tests (19 repository + 7 sync engine), 0 failures/errors/skips |
| `.\gradlew.bat :backend:build` | PASS; 18 tests, 0 failures/errors/skips, including real PostgreSQL integration |
| `.\gradlew.bat -p backend build` | PASS; backend and shared module built/tested without Android project configuration |
| `.\gradlew.bat :app:assembleDebugAndroidTest` | PASS; instrumentation APK generated |
| `.\gradlew.bat :app:connectedDebugAndroidTest` | PASS; 3 Compose workflow tests; 2 opt-in connected stages skipped in that default invocation and executed separately below |
| `.\gradlew.bat :app:lintDebug` | PASS; 0 errors, 57 warnings (pinned dependency/target versions and version-catalog recommendations) |
| Connected preparation stage | PASS; 1 test, 0.558 seconds |
| Actual server-outage stage | PASS; 1 test, 20.204 seconds |
| Restart / WorkManager recovery / replay / conflict stage | PASS; 1 test, 1.695 seconds |
| Runtime screenshots | Captured and visually inspected in light/dark themes at 360 dp width |

There are **68 distinct passing tests/check stages**: 18 shared + 26 Android JVM + 18 backend + 3 Compose + 3 connected stages. Repeated executions are not added to this count. The outage test was added after the default emulator run and executed explicitly. Future default emulator runs skip all three connected stages unless their corresponding `journeyStage` argument is supplied.

The final combined headless command was:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/fieldflow'
$env:TEST_DATABASE_USER='fieldflow'
$env:TEST_DATABASE_PASSWORD=''
.\gradlew.bat :shared:test :app:testDebugUnitTest :app:assembleDebug :backend:build
```

The empty test password above applies only to the temporary loopback-only PostgreSQL runtime used during verification. The normal Docker configuration requires the password in `.env`.

## Connected journey evidence

The tests used the actual installed app, Keystore session storage, file-backed Room, Retrofit, WorkManager, Ktor and PostgreSQL:

1. Authenticated and downloaded assigned work, saved five answers, a defect and a JPEG fixture, then submitted locally.
2. Force-stopped the app and stopped Ktor. An actual upload attempt failed; the same operation ID/payload, all five answers and the photo file remained pending.
3. Restarted Ktor and force-stopped the app again. Restored the encrypted session and Room queue; scheduled real WorkManager work.
4. Confirmed photo upload and inspection synchronization. Replayed the operation twice; the server retained a single version-1 inspection.
5. Submitted a new operation ID with stale expected version 0. The worker recorded CONFLICT with the server snapshot, preserving all local answers.

Direct PostgreSQL inspection confirmed record `7d707d66-482e-4b64-a9c0-9c5c53a00e44`, **one photo**, **version 1**, one synchronization audit and one version-conflict audit. Stage output is retained in [prepare](evidence/connected-prepare.txt), [outage](evidence/connected-outage.txt), and [restore](evidence/connected-restore.txt).

The generated JPEG fixture verifies upload/retry persistence independently of camera hardware. Separately, `WorkflowTest.cameraPhotoPersistsWithDefect` captured a real CameraX JPEG from the emulator camera, persisted its file and Room association, and verified it remained visible after Activity recreation.

## Artifacts

- APK: `app/build/outputs/apk/debug/app-debug.apk` (44,439,232 bytes).
- APK SHA-256: `e3fcf3da977ab0e7016099ff478a6d50b6b779bf33b38ad974f843f8138e3a65`.
- Android JVM report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Device report: `app/build/reports/androidTests/connected/debug/index.html`.
- Backend report: `backend/build/reports/tests/test/index.html`.
- Shared report: `shared/build/reports/tests/test/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- Backend distributions: `backend/build/distributions/`.
- Runtime screenshots: `docs/screenshots/dashboard.png`, `docs/screenshots/dashboard-dark.png`.

## Implemented but NOT VERIFIED on physical hardware

Optical QR decoding with a real camera, real GPS fixes/approximate-location behavior, real-device permission edge cases, TalkBack and OEM background-work behavior. CameraX capture was verified on the emulator only. API 26 device execution and physical airplane-mode transitions were NOT VERIFIED. Procedures are in `docs/demo-script.md`.

## Incomplete / deliberately limited

- Remote FCM delivery is not implemented; in-app notifications work from persisted state.
- Cross-device remote-photo cache hydration is not implemented; local photos persist and the backend supports authenticated photo retrieval.
- Change downloads are bounded full snapshots, with no cursor pagination or reassignment/tombstone protocol.
- Conflict copy retains the original and starts a new draft; there is no answer-by-answer merge or automatic photo reassignment.
- GitHub Actions is configured but its hosted execution is NOT VERIFIED. Docker Compose is configured but NOT VERIFIED on this machine; PostgreSQL itself was tested directly.
- No release signing, public hosting or deployment was performed. No commits or pushes were made.

Initial compilation/test/lint failures were corrected and the affected checks rerun. No tests were commented out, assertions weakened to hide application defects, or lint detectors disabled. Lint's analysis-engine compatibility was resolved using the documented independent lint version; networking XML errors were fixed explicitly.
