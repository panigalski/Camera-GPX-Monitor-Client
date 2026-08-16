# Client 1.9.5 — startup / stale notification stability

This release addresses an ANR observed after MP4 storage-write notifications had been generated.

## Changes

- Storage-write notification Binder work stays off both the UI thread and the camera polling thread.
- Active MP4 storage-error notifications from a previous process/package update are cancelled asynchronously when the Client opens.
- The normal background-connection notification is also cancelled when no live in-process connection service exists.
- The automatic GPS-backup notification is cancelled during cold-start cleanup without stopping a service from the Activity UI thread.
- Cold Activity startup no longer calls `stopService()` for foreground services.
- `BackupGpsService.onDestroy()` performs vendor `LocationManager` listener cleanup on a dedicated daemon thread so teardown cannot block Activity startup.
- The Activity no longer re-starts the Camera Connection Service solely because old SharedPreferences say that a connection was requested.
- Camera Connection Service is non-sticky. It keeps the link while it is running in the foreground, including with the screen off/backgrounded, but it does not resurrect stale connection state after Android terminates the process.
- A manual Connect starts a new storage-alert session. Historical Camera App write failures from before that connection are baselined and do not generate old notifications.
- Storage-alert payload parsing is capped at 50 entries with bounded field lengths before the data reaches the UI/notification layer.
- Storage-alert policy is extracted into pure Kotlin and covered by unit tests for stale-history suppression, unseen-alert selection and bounded seen-ID retention.

## Expected behavior after updating from 1.9.3/1.9.4

1. Launching the Client clears an old MP4 write-problem notification without blocking the UI.
2. The app starts in Disconnected state after a genuine cold process start unless a foreground camera service is actually alive in the same process.
3. Press Connect manually to start the background camera link.
4. Existing Camera App storage-write history is not replayed as new Android notifications.
5. A new MP4 write failure that occurs after the connection starts generates at most one bounded notification and does not stall camera polling or UI interaction.
