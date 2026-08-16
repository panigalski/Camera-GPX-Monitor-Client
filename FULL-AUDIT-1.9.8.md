# Labpano GPX Client 1.9.8 — Full Source Audit

Audit date: 2026-08-09
Companion Camera App: 0.5.16

## Scope

Reviewed all 14 production Kotlin files (~4,476 lines), 5 JVM test files, Android manifest/resources, Gradle configuration, foreground-service lifecycle, dashboard parsing/rendering, Wi-Fi connection handling, phone GPS collection, per-video GPX backup, daily phone GPX archive, storage alerts, temperature monitoring and notification behavior.

## Requested changes completed

- Card title is **Pilot One Recording Status**.
- Card position is swapped with **Screen Always On** (Screen Always On now appears first, recording status second).
- Live **RECORDING** state is green.
- Live **NOT RECORDING** state is red.
- When camera data becomes stale, the last state keeps its semantic red/green color but receives an explicit stale-data warning.
- Client remains compatible with dashboard API v3; Camera App 0.5.16 supplies the hardened status signal.

## Audit findings fixed

### Medium — GPS callbacks were doing durable writes on the UI looper
Location callbacks previously arrived on the main looper and each accepted location can append durable timeline data. On long driving sessions or slow storage this could contribute to UI stalls / ANRs.

**Fix:** GPS, network-location and GNSS callbacks now use a dedicated `HandlerThread` (`phone-gps-location`). UI work remains on the Activity thread while GPS persistence is isolated.

### Medium — final daily GPX could lag the internal log at service stop
The selected Backup-folder GPX is synchronized every 30 seconds, so an intentional stop could leave the external GPX a few fixes behind the durable app-private daily CSV.

**Fix:** service shutdown now starts one final best-effort daily GPX synchronization off the main thread. The app-private daily source log remains the recovery source if external storage is temporarily unavailable.

## Existing safeguards confirmed

- Foreground location service is not exported.
- Foreground camera-connection service is not exported.
- Android target SDK is 35 and minimum SDK is 24.
- App backup is disabled.
- Daily phone locations are quality-filtered before being persisted.
- Daily source logs survive normal client restarts and are retained for 14 days.
- Daily GPX writes use temporary-document / read-back verification before replacement.
- Camera GPX backup preserves camera timestamps while replacing coordinates from the phone timeline.
- Camera connection and GPS collection are separate services, limiting failure coupling.
- Prior recursive render / stale alert fixes remain present.

## Residual risks / design constraints

### Medium — local HTTP connection is cleartext
The Client intentionally permits cleartext HTTP because it talks directly to the Pilot One on a local Wi-Fi network. Anyone able to observe that LAN can potentially observe the traffic. A future shared-secret/PIN or HTTPS layer would improve security on untrusted networks.

### Low — per-video backup folder naming retains the older `STATUS_DATE` convention
Daily phone GPX archives use `dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`, while per-video smartphone GPX backups still use the historical `dd-MM-yyyy/STATUS_dd-MM-yyyy/` hierarchy. This is not data loss, but it is a naming inconsistency with newer Camera App output conventions.

### Operational — Android can still terminate the process
Foreground services materially improve survivability, but no ordinary Android app can guarantee execution through force-stop, OS/vendor process killing, battery exhaustion or revoked permissions. The durable daily CSV is therefore important recovery protection.

## Verification performed

- XML resources/manifests parsed successfully.
- No merge-conflict markers found.
- Pure Kotlin daily GPX writer smoke test passed.
- Existing daily GPX JUnit test remains in the project.
- Kotlin syntax scan found no parser-level errors in the modified service source.
- Full Gradle test/build was attempted but could not start because this sandbox cannot resolve `services.gradle.org` to download Gradle 8.9 (`UnknownHostException`).

## Recommended real-device acceptance test

1. Install Client 1.9.8 and Camera App 0.5.16.
2. Connect to the Pilot One and confirm NOT RECORDING is red.
3. Start an MP4 recording and allow one or two dashboard polls; confirm RECORDING becomes green and shows the MP4 name when available.
4. Stop recording; confirm status returns to red NOT RECORDING. With a delivered CLOSE_WRITE this should be prompt; if the vendor omits close events, the growth timeout can take up to about 12 seconds.
5. Run phone GPS backup across at least one hour and stop it; verify `Backup/dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx` contains the final fixes and remains valid XML.
