# Focused reliability hardening — October 30, 2026

The existing Android/Room/WorkManager/Ktor/PostgreSQL architecture, dependency versions, demo data and screen layout are preserved. No dependencies or product features were added. No commit, push, publication or deployment was performed. The workspace has no root Git repository; ignore behavior was tested with isolated temporary Git metadata under the ignored `.tools/` directory.

## Fixes

### Retry budget and authentication

Operations now persist a failure category in Room schema 3. The 2→3 migration preserves IDs, immutable payloads, timestamps, counters and evidence. It recognizes existing authentication and permanent HTTP failures; unclassified legacy rows remain available for bounded explicit retry. Schema 2 is retained unchanged.

Manual retry transactionally resets eligible FAILED operations to PENDING with zero attempts, updates the inspection's visible state and records an audit event. It does not create another operation or modify its payload. Transient transport/408/429/5xx failures get up to eight automatic attempts per manually started retry cycle. Evidence and unknown failures need explicit retry. Permanent validation/request failures stay FAILED and visible; neither background execution, batch retry nor signing in resubmits them indefinitely. Conflicted records and their server snapshots are untouched.

HTTP 401 is separate: only successful reauthentication resumes AUTH failures belonging to that technician. A new login does not reset unrelated failures. Session-file changes are serialized, and expiry/401 cleanup is conditional on the session still matching the failed request, protecting a newer login from a late response.

### Photo validation

Submission snapshots the draft/template/attachment references, validates files outside the Room transaction on Dispatchers.IO, then rechecks the snapshot before atomically submitting/queuing. Validation requires a readable regular file, at most 5 MiB (the existing limit), JPEG start/end markers, valid JPEG dimensions/type and successful sampled decoding. The file read itself is bounded even if the file grows after the size check.

Rejected evidence leaves the draft, answers and attachment references unchanged and creates no sync operation. Errors identify the affected photo and instruct restoration of the original readable JPEG. The same validator is used before uploading an outstanding attachment. A file disappearing after submission becomes a visible EVIDENCE failure; restoring the original file and manually retrying retains the original logical operation. No attachments are deleted by validation or retry.

### Connectivity wording

The existing banner now says **NETWORK AVAILABLE · Work saved locally** when Android reports validated internet access. It makes no claim that the backend is reachable. Demo/offline wording remains explicit about local storage. Historical screenshots were preserved and labeled as predating this wording change.

### Ignore rules

Expanded `.gitignore` to cover environment variants, secret directories, signing-property files, private-key/certificate formats and generated APK/AAB files. `.env.example` is explicitly preserved. Verification passed for 16 excluded paths and 10 preserved paths, including Gradle Wrapper files, both schema exports, sources and screenshots. No release-artifact exception is currently defined; generated APKs remain ignored.

## New and strengthened regressions

18 new JVM tests and two new instrumentation tests were added. Existing validation and migration assertions were retained or strengthened.

| Test group | Assertions |
|---|---|
| `eightTransientFailuresThenManualRetryResetsBudgetAndSucceeds` | Eight real engine failures exhaust the budget; failed state stays observable; manual retry resets to zero; another transient failure is tolerated before success; operation ID, inspection ID and payload stay unchanged; only one operation exists. |
| Authentication regressions | HTTP 401 preserves the queue and clears the old session; only the same technician's successful reauthentication resets AUTH failures; manual retry cannot bypass sign-in; unrelated failures stay failed; a late 401 cannot clear a new session. |
| Permanent/conflict regressions | HTTP 400 is sent once and stays visible after background runs, batch retry and login recovery; conflict payload/server snapshot are never requeued or discarded. |
| Disappearing-photo regression | Delete a previously validated file after submission; sync records a visible EVIDENCE failure without losing answers or the reference; restore the original bytes and retry successfully using the same operation. |
| Eight photo tests | Missing file, non-file/unreadable path, invalid bytes, marker-only fake JPEG, truncated JPEG, oversized file, valid JPEG/idempotent submission, and preservation of other valid attachments. Rejection checks compare the entire inspection row and assert no queue operation. |
| Migration regressions | Migrate 1→3 without losing a queued inspection; migrate an exhausted version-2 validation failure to version 3 while retaining ID, payload and attempts and assigning its permanent category. |
| Three wording tests | Network availability never produces CONNECTED; offline storage wording is preserved; demo wording is independent of connectivity. |
| `exhaustedOperationRemainsVisibleInSyncCenter` | Existing Sync Center shows FAILED, its eight-attempt error and a working inspection-detail action. |
| `manualRetryOfExhaustedOperation` | Real emulator/Room/WorkManager/Ktor/PostgreSQL path: a persisted exhausted state is reset manually, then synced on attempt 1 with unchanged ID/payload, one server inspection and a successful idempotent replay. The actual eight-failure progression is covered by the engine test; this device test seeds the exhausted state deliberately. |

## Executed verification

JDK 17.0.7, Gradle 8.9, Android SDK 35, API 34 Pixel 8 Pro emulator and PostgreSQL 16.4 on loopback port 55432. The existing Ktor distribution ran on port 8080. PostgreSQL and emulator tests were executed, not inferred from H2 or JVM results.

Targeted tests ran first:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SyncEngineTest' --tests '*PhotoEvidenceTest' --tests '*ConnectivityMessageTest' --tests '*RepositoryTest.*Migration*' --tests '*RepositoryTest.migration*'
```

Final targeted result: **26 passed, 0 failures/errors/skips**. Early targeted attempts exposed a desktop-only test-fixture import and an uninitialized JPEG decoder sampling value; both were corrected and the checks rerun. No assertions were weakened and no lint rules were disabled.

Then the required broader verification ran:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/fieldflow'
$env:TEST_DATABASE_USER='fieldflow'
$env:TEST_DATABASE_PASSWORD=''
.\gradlew.bat --continue :app:testDebugUnitTest :shared:test --rerun :backend:test --rerun :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

The empty PostgreSQL password applies only to the pre-existing loopback test runtime. Normal Docker setup still requires the configured development password.

| Check | Final result |
|---|---|
| Android JVM | **44 passed**: 20 repository, 13 sync, 8 photo, 3 connectivity; no failures/errors/skips |
| Shared domain | **18 passed**, no failures/errors/skips; forced rerun |
| Backend | **18 passed**, no failures/errors/skips, including PostgreSQL; forced rerun |
| Android debug build | **PASS**, APK generated |
| Android lint | **PASS**, 0 errors and 57 existing dependency/target-version and version-catalog warnings |
| Connected UI suite | **4 passed**, 0 failures/errors, 4 opt-in connected tests skipped in the default invocation |
| Dedicated WorkManager retry test | **1 passed**, executed separately against the running backend/database; 2.076 seconds |

The broader Gradle command completed successfully in **2m 35s**. There were **85 distinct passing tests** across the final suites and separately invoked retry test; the 26 targeted executions are overlapping and are not added again. Three older opt-in journey stages (prepare/outage/restore) were not rerun in this hardening pass. The fourth default-skipped test was executed separately using:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class 'com.harshkatariya.fieldflow.ConnectedJourneyTest#manualRetryOfExhaustedOperation' -e journeyStage retry com.harshkatariya.fieldflow.test/androidx.test.runner.AndroidJUnitRunner
```

Actual stage output:

```text
com.harshkatariya.fieldflow.ConnectedJourneyTest:.
Time: 2.076
OK (1 test)
```

APK: `app/build/outputs/apk/debug/app-debug.apk`, **44,439,588 bytes**.

SHA-256: `ed3a17d1c3f8fa2e9629ad5aca3e650e9540da0974df2ad57ed7b7626e4bb983`.

Gradle reports remain under the existing module `build/reports/` directories. Raw hardening logs are under ignored `.tools/hardening-*.log`.

The temporary Ktor process, PostgreSQL test instance and emulator were stopped after verification.

## Exact project files changed

Modified:

```text
.gitignore
README.md
app/src/main/java/com/harshkatariya/fieldflow/data/Database.kt
app/src/main/java/com/harshkatariya/fieldflow/data/Repository.kt
app/src/main/java/com/harshkatariya/fieldflow/data/SessionStore.kt
app/src/main/java/com/harshkatariya/fieldflow/data/Sync.kt
app/src/main/java/com/harshkatariya/fieldflow/ui/FieldViewModel.kt
app/src/main/java/com/harshkatariya/fieldflow/ui/Screens.kt
app/src/test/java/com/harshkatariya/fieldflow/RepositoryTest.kt
app/src/test/java/com/harshkatariya/fieldflow/SyncEngineTest.kt
app/src/androidTest/java/com/harshkatariya/fieldflow/WorkflowTest.kt
app/src/androidTest/java/com/harshkatariya/fieldflow/ConnectedJourneyTest.kt
docs/architecture.md
docs/testing.md
docs/verification-results.md
docs/screenshots.md
```

Added:

```text
app/schemas/com.harshkatariya.fieldflow.data.FieldDatabase/3.json
app/src/main/java/com/harshkatariya/fieldflow/data/PhotoEvidence.kt
app/src/test/java/com/harshkatariya/fieldflow/PhotoEvidenceTest.kt
app/src/test/java/com/harshkatariya/fieldflow/ConnectivityMessageTest.kt
docs/hardening-results.md
```

Generated APKs/reports and temporary runtime logs changed only under ignored build/temporary directories. Backend/shared source, dependencies, original seed data, schema 2 and screenshot image files were not changed.

## Remaining limitations / manual verification

There are no remaining build or test blockers for these fixes. Physical-device file-access denial behavior was not tested; automated coverage exercises missing files, a non-file/unreadable path and invalid image content. Optional manual reproduction: save a photo in a draft, make its app-private file unavailable, submit and confirm the draft is retained; restore that original file and retry. Do not remove unrelated evidence.

Permanent HTTP validation failures intentionally require correcting the rejected request through an explicit repair workflow; this scoped change does not introduce editing of submitted immutable records. Restoring a missing submitted photo requires the original file; no automatic evidence replacement or deletion is performed.

No new screenshots were captured. Hosted CI, physical QR scanning and real GPS were not rerun. The three previous opt-in connected journey stages retain their earlier historical evidence rather than being presented as newly executed tests.
