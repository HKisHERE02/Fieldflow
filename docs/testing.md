# Testing and verification

## Commands

```sh
./gradlew :app:assembleDebug
./gradlew :shared:test :app:testDebugUnitTest
./gradlew :backend:build
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

The emulator suite uses the real app Activity, Hilt ViewModel, repository and Room. It verifies dashboard navigation, required-answer errors, checklist entry, draft navigation, submission, activity recreation, queue visibility, search empty state and manual unknown-code handling. Opt-in connected tests are skipped in default runs.

For real PostgreSQL integration, set:

```sh
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/fieldflow
export TEST_DATABASE_USER=fieldflow
export TEST_DATABASE_PASSWORD=your-local-password
./gradlew :backend:test --rerun-tasks
```

The database must be an expendable development/test database. The test creates fictional records and leaves them for inspection. H2 tests use isolated migrated databases and include a file-backed close/reopen persistence test. An H2 pass is not presented as PostgreSQL verification.

## Process-death connected journey

Start Ktor and PostgreSQL; install both APKs. With the backend reachable at 10.0.2.2:8080:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.harshkatariya.fieldflow.ConnectedJourneyTest#prepareOfflineSubmission -e journeyStage prepare -e demoPassword 'FieldFlow-Demo-2026!' com.harshkatariya.fieldflow.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.harshkatariya.fieldflow
# Stop only the local Ktor server, leaving PostgreSQL running, then:
adb shell am instrument -w -e class com.harshkatariya.fieldflow.ConnectedJourneyTest#serverOutagePreservesSubmission -e journeyStage outage com.harshkatariya.fieldflow.test/androidx.test.runner.AndroidJUnitRunner
# Restart Ktor before the next command:
adb shell am instrument -w -e class com.harshkatariya.fieldflow.ConnectedJourneyTest#restoreAndSynchronizeExactlyOnce -e journeyStage restore com.harshkatariya.fieldflow.test/androidx.test.runner.AndroidJUnitRunner
```

Preparation disables scheduling, saves a connected session and assigned work, then queues a submission and a generated JPEG fixture locally without contacting the submission endpoint. The outage stage runs the engine against the stopped server and verifies that the stable operation, answers and photo survive the actual connection failure. Restoration reads the persisted session and queue, schedules the real WorkManager worker, verifies attachment upload, server persistence and replay, then injects a stale operation to confirm a structured conflict preserves local answers. The separate Compose camera test captures a real CameraX image from the emulator camera and verifies its durable file/Room reference.

## Coverage map

| Layer | Coverage |
|---|---|
| Shared rules | All answer types, required responses, numeric bounds/NaN, evidence, defect validation, duplicate/unknown answers, transitions, serialization, retry codes, expiry, search, seed relationships |
| Room/repository | Seed idempotency, ownership, drafts, defects, atomic submission, duplicate prevention, restart persistence, merge protection, retries, conflict copy, audit and demo isolation |
| Ktor/storage | Login, token validation/revocation, ownership, idempotency, payload-key reuse, versions, bad submissions, photo ownership/retry, HTTP statuses, file persistence, optional real PostgreSQL |
| Compose/device | Actual navigation and ViewModel state, validation feedback, draft/resume, queue UI, empty/error states; opt-in connected WorkManager journey |

## Execution evidence

Focused reliability-hardening results, exact commands, new regression assertions and changed files are recorded in `docs/hardening-results.md`. The original verification report remains a historical record of the initial build.

The final execution results are recorded in `docs/verification-results.md`. Commands listed above are reproducible instructions, not an assertion that every case ran. Raw local logs are stored in the ignored `.tools/` directory; Gradle HTML/XML reports are in each module's `build/` directory.

## Hardware and external services

Real-device QR scanning, camera optical quality, GPS fixes, accessibility screen-reader checks, physical airplane-mode transitions and FCM remote delivery require separate verification. FCM is not implemented. Follow `docs/demo-script.md` for camera/location/permission procedures. Do not count unavailable hardware checks as passing tests.
