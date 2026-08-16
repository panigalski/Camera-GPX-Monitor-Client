# Client 1.10.10 — Transfer / processing event line

Client 1.10.10 adds one short live event line directly below the transfer progress area in **Main Camera App Monitor**.

The Client uses data that Main App 0.5.25 already exposes, so the Main App does not need another revision for this feature:

- `transfers[].phase == COPYING` → `Moving: <file>`
- `transfers[].phase == VERIFYING` → `Verifying: <file>`
- `transfers[].phase == FINALIZING` → `Finalizing: <file>`
- other non-empty transfer phases are converted into a readable one-line label
- when no file copy is active and `monitoring.lastStatus` is `processing:<file>`, the line shows `Processing / generating GPX: <file>`

The active transfer phase has priority over the broader monitoring status. The event line is hidden while idle and is cleared together with the rest of the Main App-derived dashboard data when the camera connection is lost.

This is intentionally a basic operator-facing status. Main App 0.5.25 exposes the pre-transfer pipeline as a single `processing:<file>` status, so the Client does not pretend to distinguish CAMM parsing, GPS validation, densification and GPX writing as separate exact stages.
