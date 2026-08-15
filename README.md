# Labpano GPX Client

Android companion app for the Labpano GPX Extractor running on a Pilot One camera.

**Current version:** 1.10.20  

The Main Camera App Monitor reads the dedicated live `outputFolder` value and refreshes it during normal dashboard polling.
**Minimum Android:** 7.0 (API 24)  
**Target SDK:** 33


## 1.10.20 Fragment Storage live updates

Paired with Main App 0.5.35. The Client applies a monotonic Fragment Storage revision from `/efs/video.properties`, so Camera UI changes such as 4 GB -> 6 GB are not lost to wall-clock ordering.


## 1.10.19 Camera-APK-grounded Fragment Storage

Paired with Main 0.5.34. The displayed Fragment Storage value now comes from the stock Camera 5.18.11 `/efs/video.properties` source when readable, with an explicit permission diagnostic otherwise; rolling transfer no longer depends on reading the setting.


## 1.10.18 Fragment Storage diagnostics

- Paired with Main 0.5.33. A concrete Fragment Storage setting is monotonic across live/full dashboard polling: a later transient `Unavailable` response does not erase an already-known `4 GB`, `4 GB (observed)`, time limit, or `Off (Unlimited)` value.
- `Fragment Storage:` now includes a short Main App diagnostic when no value is known, helping distinguish a Camera-control timeout/refusal from a parsing issue.
- Rolling MP4 transfer remains independent from `Pilot One Recording Status`; an older finalized fragment can show processing/move progress while the current fragment remains `Recording`.

## 1.10.15 monotonic recording status

- Main 0.5.30 Camera lifecycle `generation` is authoritative for `Pilot One Recording Status`; transfer rows no longer change an explicit Camera `Recording`/`Ready` state.
- Live and full-dashboard responses are merged monotonically so an older response cannot overwrite a newer Camera completion or erase newer transfer state.
- Once a generation has reached an accepted `Ready`, same-generation stale data cannot switch it back to `Recording`; a real new recording is accepted as the next generation.
- Older completed files may display transfer progress while a newer Camera recording remains `Recording`.

## 1.10.14 recording-status compatibility

- Keeps `Recording` visible for legacy Main 0.5.27/0.5.28 inference-only `pilot-camera-write-idle`, `pilot-camera-file-close`, and `pilot-camera-imu-close` states while Camera ownership is still marked as finalizing.
- Explicit Camera completion (`addFile`) and an active transfer for the same video still render `Ready`.
- A new recording remains `Recording` while an older completed video is being transferred.

## Main features

- Manual Connect/Disconnect control with remembered successful camera addresses.
- Foreground camera-connection service for screen-off and background use. A failed Main App dashboard poll ends the camera session immediately: the Client returns to CONNECT and clears camera-derived values instead of retaining stale data or silently reconnecting.
- Camera battery, internal storage, optional external storage, transfer progress and reports.
- GOOD, FAILED and ERROR report alerts using the supplied MP3 sounds.
- Pilot One `thermal_zone0` device-temperature monitoring through the Camera App API, including background alerts and a user-configurable warning threshold (73 °C default).
- Opt-in smartphone contingency GPS collection in a foreground location service that is independent of Main App connection state.
- Backup GPX files preserve every timestamp from the Camera App GPX and replace coordinates only.
- Backup GPX files are saved under `dd-MM-yyyy/STATUS_dd-MM-yyyy/` in the selected folder.
- Complete per-day phone GPS archive in the selected Backup folder: `dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`.
- Compact **Main Camera App Monitor** showing live Monitoring ON/OFF, the Main App OUTPUT folder, active purple transfer progress bars with a single activity + video filename line directly underneath, plus a short pre-transfer processing/GPX-generation line when applicable.
- One-line **Pilot One Recording Status** (`Recording` red / `Ready` blue), a persistent **App Sounds** mute control, and simplified Current / Warning / Return temperature display.
- With Main App 0.5.27+, recording/Monitoring/OUTPUT/transfer state uses a lightweight 250 ms live-status channel; Main App 0.5.30 adds per-file recording ownership plus a Camera lifecycle generation. Client 1.10.18 merges that realtime state monotonically and keeps explicit Camera recording state independent of transfer rows; legacy 0.5.27/0.5.28 compatibility remains for inference-only file-close/IMU-close states. Heavier storage, report, thermal and Bluetooth/GPS data remain on the 3-second full dashboard.
- Separate **Pilot One Bluetooth / GPS** sub-screen for camera-side Bluetooth connection/RSSI, active location source/mock state and system GNSS satellite/C/N0 diagnostics.
- Optional Screen Always On toggle.

## Temperature monitoring

The Camera App reads the Pilot One device temperature from `/sys/class/thermal/thermal_zone0/temp`, matching Labpano's original `CpuTemperatureReader`. The Client App receives that value through the dashboard API while its foreground camera-connection service is active.

Open **Temperature Alert Settings** from the dashboard to set the warning threshold. The default is 73 °C. The warning sound plays once when the measured temperature rises above the threshold, rearms after the camera cools 3 °C below the threshold, and uses a 10-minute cooldown to avoid rapid repeat alerts. Monitoring continues while the phone screen is off or another app is in the foreground, provided the camera connection service remains active.

## Build on GitHub

The repository contains one workflow: `.github/workflows/build-apk.yml`.
It installs Java 17, Android SDK Platform 33 and Build Tools 30.0.3, runs JVM unit tests, builds the debug APK and uploads it as a workflow artifact.

See [GITHUB-UPLOAD-AND-BUILD.md](GITHUB-UPLOAD-AND-BUILD.md).

## Local build

Install JDK 17, Android SDK Platform 33 and Build Tools 30.0.3, then run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is created under `app/build/outputs/apk/debug/`.

## Lean / stable build profile

Client 1.10.18 uses Android Gradle Plugin 7.4.2, Gradle 7.6.4 and Kotlin 1.7.22. It compiles/targets API 33 because the client uses Android 13 notification permission APIs, while reusing Build Tools 30.0.3 so the Main and Client projects can share more of the local Android build-tool installation. Gradle daemon, caching, parallel execution, VFS watching and Kotlin incremental compilation are enabled.

## Operation

1. Connect the smartphone and Pilot One to the same local network.
2. Start Wi-Fi file access in the Camera App.
3. Select a smartphone backup folder.
4. Press **Start Automatic Backup** to begin contingency phone GPS collection; a camera connection is not required.
5. Enter the displayed camera address and press **Connect** whenever Main App data is needed. If Automatic Backup is already running, camera-GPX matching attaches automatically while connected.

Automatic Backup is OFF on every fresh app process. Once you press Start Automatic Backup, smartphone GPS collection continues independently of camera Connect/Disconnect state until you press Stop Automatic Backup or the location service is otherwise stopped. The camera connection and GPS collection use separate foreground services.

## GitHub Actions runtime update

- `actions/checkout@v6` (Node.js 24)
- `actions/setup-java@v5` (Node.js 24)
- `actions/upload-artifact@v7` (Node.js 24)
- Java remains Temurin 17 for the Android/Gradle build.
- GitHub-hosted runners satisfy the required Actions runner version.
## 1.9.3

- Added a purple temperature threshold slider with 0.1 °C steps; the slider and numeric entry stay synchronized in both directions.
- Added MP4 Storage Write Status to the dashboard.
- Added high-priority background notifications for new Camera App MP4 write/verification failures on internal or external storage.
- Added support for the Camera App's additive `storageWriteAlerts` dashboard field while retaining dashboard API v3 compatibility.

## 1.9.4 storage-alert stability
- Storage-write notifications no longer execute on the camera polling thread.
- Multiple storage failures are summarized into one bounded notification.
- Notification delivery failures cannot stall or disconnect the client.
## Client 1.9.5 startup stability

Version 1.9.5 fixes an ANR/stale-notification path observed after MP4 storage-write alerts. Notification IPC and stale-notification cleanup no longer block startup or the camera polling thread, historical write alerts are not replayed on a new manual connection, and the background camera service no longer auto-resurrects after process death. See `STARTUP-STABILITY-1.9.5.md`.

### Client 1.9.6 stability fix
The Client uses a non-recursive dashboard render path. Background dashboard updates are published through a monotonic process-local revision, unchanged dashboards do not trigger redundant UI rebuilds, and the MP4 write-status card contains only problems first detected during the current manual camera connection. Legacy storage-error notifications created by Client 1.9.3 are cleaned up on startup/connect.



## 1.9.7 recording status and daily phone GPX

- The dashboard shows whether the Pilot camera is currently recording based on the Camera App's MP4 activity status.
- While Automatic Backup is enabled, the phone keeps a complete quality-filtered daily GPS track in the selected Backup folder as `dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`.
- The daily GPX is updated about every 30 seconds and verified after writing.
"# Camera-GPX-Monitor-Client" 
