# Client 1.10.7 — Connection-loss reset

## New policy

The Client no longer keeps a camera session in `RECONNECTING` after a failed Main App dashboard poll.

On the first confirmed dashboard fetch failure:

1. `CameraConnectionService` marks the session `DISCONNECTED` and `requested=false`.
2. `ClientSessionState` is cleared immediately so no camera dashboard survives in process memory.
3. Temperature playback and camera storage-alert notifications are stopped/cleared.
4. Automatic Smartphone GPS Backup is stopped so its separate camera HTTP polling cannot silently reconnect while the main Client shows `CONNECT`; the selected Backup folder and already collected phone GPS/archive data are preserved.
5. The foreground camera connection service stops; no automatic retry is scheduled.
6. While MainActivity is visible, its 1-second service-state sync detects the terminal disconnect, changes the button to `Connect`, and calls `clearCameraData()`.

The visible reset covers Main App-derived recording, monitoring, output folder, active transfers, internal/external camera storage, camera battery/temperature, and report counts.

The saved IP address/history and phone-local settings (backup folder, local GPS data, App Sounds, Screen Always On, temperature thresholds) are not erased.

## Report screen

If `ReportActivity` detects the connection failure before the background service does, it also disconnects the shared camera session, clears the visible report list, and stops retrying.
