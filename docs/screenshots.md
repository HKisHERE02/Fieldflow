# Screenshot capture plan

The saved screenshots document the initial build. Their CONNECTED banner predates the reliability fix; the current app says NETWORK AVAILABLE and does not infer backend reachability. No screenshots were fabricated or regenerated for that wording change.

`screenshots/dashboard.png` and `screenshots/dashboard-dark.png` were captured from the actual connected app on a Pixel 8 Pro API 34 emulator on October 30, 2026 (1080 × 2400 pixels, 480 dpi / 360 dp width). They show fictional assigned work and one retained version conflict after the connected journey test. Both were visually inspected. Other views below remain a capture plan until images are added.

Capture the actual debug application on an API 34+ emulator or device at a consistent phone resolution. Use only fictional demo data. Capture these states:

1. Technician dashboard with the local demo banner.
2. Work-order directory showing priority and due dates.
3. Equipment detail for FF-001.
4. A partly completed checklist with autosaved responses.
5. Inspection review with a defect and captured photo.
6. Sync Center pending offline, then synced connected state.
7. Conflict detail retaining local answers.
8. Dark theme dashboard.

Save captured images under `docs/screenshots/`, name them descriptively and note the device/API and capture date. Do not include tokens or passwords in screenshots. Verification status and any captured files are recorded in `docs/testing.md`.
