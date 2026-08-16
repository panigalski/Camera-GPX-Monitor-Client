# Client 1.10.0 — combined Monitoring / Reports / MP4 Output status

## Why the old MP4 Storage Write Status appeared not to update

The Main App `storageWriteAlerts` field is intentionally a **failure history**. Successful MP4 writes do not create storage-write alerts. In addition, Client 1.9.9 rendered only failures that its notification policy classified as **new in the current manual connection**. That was correct for notification replay suppression, but it was a poor source for a visible status panel: successful transfers produced no change and a failure that happened before a reconnect disappeared from the card.

## 1.10.0 behavior

The standalone `MP4 Storage Write Status` card has been removed and its information is now inside **Main App Monitoring / Reports / MP4 Output**.

The MP4 portion is driven by three dashboard sources:

1. `transfers` — live `COPYING`, `VERIFYING`, and `FINALIZING` progress, including percentage and bytes.
2. `storageWriteAlerts` — the Main App's full persisted write-problem history, so a reconnect does not hide a camera-side storage problem.
3. `good` / `failed` / `error` reports — when output is idle, the latest completed processing report is shown so successful work also produces a visible state change.

Background notifications are deliberately unchanged: they still use the separate new-alert policy and do not replay old camera-side write failures after reconnecting.
