# Client 1.10.4 — App Sounds resume audit

This revision is based on Client 1.10.3. Runtime UI/layout and the lean/stable build configuration are otherwise unchanged.

## Root cause fixed

The temperature warning uses a persistent armed/cooldown state. If a temperature warning had already played, then App Sounds was muted while the camera remained above the threshold, the alert state stayed disarmed. Unmuting only changed the sound preference, so the still-active high-temperature condition could remain silent.

On unmute, 1.10.4 now resets the temperature warning state to armed with no cooldown and immediately requests a fresh Main App dashboard poll. If the fresh Pilot One temperature is still above the configured warning threshold, the normal temperature warning is allowed to play again. Muting still stops an active warning immediately.

## All sound paths audited

1. Temperature warning (`battery_temp_combined.mp3`)
   - Muted: no sound and muted polling does not consume a newly armed warning.
   - Unmute: rearm + fresh dashboard poll; an actively high temperature can sound immediately.
   - Normal hysteresis/cooldown resumes after that alert.

2. Temperature test sound
   - Reads App Sounds state at button press.
   - Muted: blocked with the existing toast.
   - Unmuted: plays normally.

3. GOOD / FAILED / ERROR report sounds
   - Muted report arrivals remain silent and are intentionally marked into the current report baseline.
   - They are not replayed as a backlog on unmute.
   - The next genuinely new GOOD/FAILED/ERROR report after unmute plays normally.

4. MP4 storage-write notification sound
   - Muted: uses the silent Android notification channel (or no sound on pre-O Android).
   - Unmuted: uses the audible channel. Android 7.x now explicitly requests the default notification sound.
   - `setOnlyAlertOnce(false)` is used for the fixed notification ID, so each genuinely new deduplicated MP4 write fault may alert even if the previous fault notification is still visible.
   - Historical write errors are not replayed merely because App Sounds is unmuted.

5. Background connection notification
   - This is a low-importance ongoing status notification, not an app warning sound. It remains unchanged.

## Deliberate behavior

Unmuting replays only an *ongoing temperature danger condition*. It does not replay historical GOOD/FAILED/ERROR or MP4-write events that happened while muted. This prevents a burst of stale sounds after the user turns sounds back on.
