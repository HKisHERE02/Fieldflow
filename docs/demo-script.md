# Demonstration and offline-to-online verification

## Local demonstration

1. Install and open the debug APK. Choose **Continue as Alex Morgan**.
2. Inspect the dashboard, work directory and equipment search (`FF-001`).
3. Open the first HVAC work order; start an inspection.
4. Select PASS for housing, YES for operation, enter `24` for temperature, PASS for safety label; add comments if useful.
5. Report a defect with at least five characters, select severity and save. Open review and attach a defect photograph. Allow camera only when requested.
6. Optionally attach a location. Denying permission or unavailable GPS must not block the core workflow.
7. Review and confirm submission. Sync Center shows PENDING. Demo mode intentionally does not upload.
8. Force-stop the app and relaunch. The record, responses, defects, photographs and pending operation remain.

## Connected end-to-end demonstration

1. Start PostgreSQL/Ktor with the documented environment. Sign in as `alex@fieldflow.example` using `DEMO_PASSWORD`. This is a separate database from local demo.
2. Wait for assigned work to download. Open `wo-1` and start an inspection.
3. Stop the backend or disable the emulator network. Continue answering, add a defect/photo and submit.
4. Confirm PENDING, then run `adb shell am force-stop com.harshkatariya.fieldflow`.
5. Reopen. Verify every answer and evidence file is present offline.
6. Restore the backend/network and tap **Sync now / Retry failed**. Wait for SYNCED.
7. Inspect `/inspections` and `/audit-events` using the same authenticated session, or query PostgreSQL. There must be one inspection and one successful server audit for the operation.
8. Replay the exact original serialized operation at `/sync/operations`. It returns the same receipt without another mutation.
9. Send a new operation ID with the same inspection ID and stale expected version. Verify HTTP 409 and a server snapshot. The app's opt-in connected journey test exercises this through WorkManager and confirms local answers remain.
10. In Sync Center, inspect the conflict, copy local work into a new draft and review. The original record and conflicting operation remain accessible.

## QR codes

Encode plain `FF-001`, `asset-1`, or `fieldflow://asset/asset-1` in a QR code. Scan it with the device camera and confirm the rooftop air handler opens once. Try an unknown value and permission denial; manual entry must remain usable. No remote model download is required.

## Hardware checklist

- Camera: preview, permission denial, capture, navigate away and reopen, process restart, photo visible offline.
- Required evidence: Safety template cannot submit until a photo is attached to its safety-label question.
- Location: grant approximate or precise access; test disabled location, 15-second timeout and denied permission.
- Accessibility: TalkBack, large font, 360 dp phone width, light/dark appearance and touch targets.
- Network: airplane mode, server timeout, invalid token, restart during upload, repeated operation replay.

See `docs/testing.md` for the exact automated commands and execution evidence. A written procedure is not a claim that a hardware case was executed.
