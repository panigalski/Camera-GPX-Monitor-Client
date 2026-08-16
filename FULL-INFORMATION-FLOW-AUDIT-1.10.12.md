# Full Recording / Information-Flow Audit — Client 1.10.12

Paired Main App: 0.5.27. Baseline Client: 1.10.11.

## Fixes from the audit

- Added lightweight live-status polling every 250 ms for recording state, Monitoring state, OUTPUT folder and active transfers.
- Kept the full dashboard at 3 seconds so reports, storage calculations, temperature and Bluetooth/GPS diagnostics are not needlessly rebuilt four times per second.
- Added immediate in-process Activity notification when shared camera state changes, removing the extra up-to-1-second wait for the generic UI housekeeping timer.
- Parsed `cameraRecording.finalizing`. Finalization is not capture and therefore renders `Ready`.
- Corrected the transfer safeguard ordering: if the same MP4 is already in an output transfer, it cannot simultaneously be displayed as a live recording, even if an older/stale source string is `pilot-camera-broadcast`. A different new video remains eligible for `Recording`.
- Lightweight updates merge into the existing dashboard object, preserving battery, reports, storage, diagnostics and alert history until the next full dashboard refresh.
- Main App <=0.5.26 remains supported: a 404 from `/api/v1/live-status` disables fast polling and falls back to the previous 3-second full-dashboard path without disconnecting.

## Information-flow audit

- HTTP response caching: disabled in `DashboardClient`; Main sends `Cache-Control: no-store`.
- Connection loss: any real live/full request failure follows the existing fail-closed path, resets the button to CONNECT and clears Main-derived values.
- Smartphone contingency GPS: remains independent; camera loss only detaches the optional camera endpoint and does not stop phone GPS collection.
- Report alert sounds/history: still driven by full dashboard data; fast live merges do not manufacture or replay report entries.
- Temperature warning: still uses fresh full-dashboard temperature and the existing mute/unmute rearm behavior.
- Bluetooth/GPS diagnostics: still come from the full dashboard and clear with `ClientSessionState` on disconnect.
- Transfer progress/activity: now benefits from the 250 ms live path and keeps the existing one-line activity + filename UI.

## Focused verification

- Client live-state Kotlin harness: PASS.
- Same-file transfer suppresses stale Recording: PASS.
- Different new Pilot recording remains Recording: PASS.
- `finalizing=true` never renders Recording: PASS.
- Live merge updates only live fields and preserves slower dashboard fields: PASS.
- Wrong-address live update rejected: PASS.
- `DashboardClient` including the new endpoint compiled against test JSON stubs: PASS.
- Kotlin parser checks for Android-dependent changed files: no syntax/parser errors.
- Full Gradle/APK build: not executed in this sandbox because the Gradle distribution cannot be downloaded from `services.gradle.org`. Use JDK 17 locally with Gradle 7.6.4.
