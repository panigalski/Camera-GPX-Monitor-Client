# Client 1.9.6 connection stability

The 1.9.5 UI had a recursive update path: `syncConnectionFromBackgroundService()` called `render()`, `render()` updated `ClientSessionState`, then `render()` called `syncConnectionFromBackgroundService()` again. Because the session update advanced the render timestamp, the nested sync treated the same dashboard as new and rendered again. This could continue until the UI became unresponsive or the process crashed.

1.9.6 removes both causes: `render()` is presentation-only and never calls the background-session synchronizer, while `ClientSessionState` exposes a monotonic revision that changes only for meaningful dashboard/session-alert changes.

Storage-write history returned by Camera App 0.5.9 is no longer presented as a current client fault. Only alerts classified as new during the current manual connection are shown. The notification cleanup also enumerates active app notifications and removes the legacy 1.9.3 storage-alert ID range (2600 through 6695) and the storage-alert notification channel entries.

Additional UI load reduction: report sets are rebuilt only when report size/newest-entry fingerprints change, and transfer views are rebuilt only when the transfer list changes.
