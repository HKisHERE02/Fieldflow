# Architecture and invariants

## Application layers

One Android app module contains Compose presentation, a Hilt ViewModel, repository, Room database, WorkManager synchronization and platform adapters. A small JVM `shared` module contains wire contracts, validation and deterministic seeds used by Android and Ktor. It prevents validation and identifiers from drifting between platforms. The backend can build without configuring the Android module.

Compose observes immutable StateFlow snapshots derived from Room. Login establishes a session, then a worker downloads records into Room. API responses never directly populate screens. ViewModel mutations are serialized through a command channel so edits preceding review or submission finish first.

Room uses foreign keys between technicians, work orders, assets, templates, inspections, photos and operations. Inspection answers and defects are serialized as one aggregate in the inspection row: they are updated atomically and cannot become detached from the inspection. This intentionally avoids an unnecessary per-answer join layer. Photos have separate durable references, and the queue has a unique index on inspection ID.

Database version 3 retains the 1→2 metadata migration and adds a 2→3 migration for the queue's failure category. Existing operation IDs, payloads and evidence remain intact. Recognizable legacy HTTP 400-series failures are classified as permanent (except 401, 408 and 429); legacy authentication failures require sign-in. Other unclassified legacy failures are eligible for an explicit bounded retry. There is no destructive migration fallback. Seed setup is a transaction guarded by a persistent marker. Separate database files isolate local demo and authenticated data. All read and write operations involving protected inspections filter/validate technician ownership.

## Submission

1. A draft is created or resumed for the assigned work order.
2. Every edit persists before acknowledgment; each actual save adds an audit event.
3. Shared rules enforce choices, numeric bounds, required responses, photo evidence, valid location and defect descriptions. A failed pass/fail question requires a defect.
4. Submit changes the status to SUBMITTED and creates one operation in the same Room transaction.
   Before that transaction, every referenced photo must be a readable regular file of at most 5 MB, contain a complete JPEG and decode successfully. Reads are bounded and run on the I/O dispatcher outside the transaction. The draft, template and attachment references are compared again before committing; rejected evidence leaves answers and draft status untouched and creates no operation. The same check runs immediately before each outstanding photo upload, so a later missing/corrupt file becomes a visible evidence failure. Restore the original file at its saved reference, then retry; no evidence is deleted or silently substituted.
5. The operation contains its UUID, entity ID, expected server version, frozen serialized inspection, creation time, attempts, status, error and optional conflicting server snapshot.
6. Submitted records cannot be edited. Repeated submit calls return the existing operation.

## Synchronization

Unique WorkManager jobs require network connectivity and use exponential backoff starting at 30 seconds. A periodic 15-minute job provides recovery/refresh, while submitting or pressing Sync schedules immediate constrained work. APPEND_OR_REPLACE ensures a new request is not dropped when a previous worker is completing. A process-wide mutex serializes engine runs, including periodic/one-time overlap.

A worker recovers SYNCING records after a process death and replays the same operation ID. It uploads each photo with its own stable ID before the inspection. A lost response is safe: the server acknowledges the same attachment/operation on retry. Transport errors and HTTP 408/429/5xx remain pending until eight attempts. Explicit manual retry resets their attempt budget to zero and updates the inspection's visible status in the same transaction, preserving the operation ID and frozen payload. Evidence and unknown failures require explicit retry rather than an automatic loop. Permanent request failures (including HTTP 400) stay FAILED and are excluded from batch retry and login recovery; correcting rejected immutable data needs explicit request repair, not repeated background submission. Conflicts are never requeued by either path.

HTTP 401 is recorded separately as an authentication failure. Successful sign-in only resumes that technician's authentication failures with a fresh budget; it does not reset unrelated validation/transient failures. Clearing an expired session is conditional on it still being the session used for the failed request, so a late 401 cannot clear a newer login. Failed operations stay in the Sync Center and history. Audit events preserve the failed attempts and manual reset events even though the current retry counter restarts at zero.

The server locks the assigned work-order row inside a database transaction. It verifies technician, asset, template, checklist and uploaded-photo ownership, then checks the durable receipt. Reusing an ID with another payload is rejected. Inspection writes, receipt insertion and audit insertion commit together. The lock also serializes concurrent devices submitting against the same work order.

The backend uses optimistic version checks. A stale expected version produces HTTP 409 with actual version and server snapshot. A conflict receipt is itself durable, so its replay is deterministic. Android preserves the original payload and displays both sides. The user can explicitly copy local answers/defects to a new draft; photos remain on the original, and a new submission requires review. No automatic last-write-wins policy exists.

After outgoing work, the worker downloads a bounded snapshot. Existing drafts, pending/failed operations and conflicts are never overwritten. The default snapshot limits are documented in the API guide; this is not an unbounded enterprise synchronization protocol.

## Sessions, dates and device data

Dates are epoch milliseconds in UTC and are formatted in the device timezone. Opaque server tokens expire seven days after login. Android encrypts the session using a Keystore-generated AES-GCM key; app backup is disabled. Offline access cannot extend expiry. UI expiry checks and repository mutation guards prevent new writes under an expired session.

The banner says NETWORK AVAILABLE when Android reports validated internet access. It makes no assertion about Ktor reachability; actual upload outcomes remain in Sync Center. Offline and local-demo wording still emphasize persistent local storage.

Camera permission is contextual; ML Kit's barcode model is bundled. CameraX, scanner and executor resources are released on navigation. JPEG captures go to app-private files before Room references are committed. Location capture requests one fix with a 15-second timeout, removes its listener on completion/cancellation, and does not request background permission.
