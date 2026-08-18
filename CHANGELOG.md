# Changelog

## 1.10.31
- Paired with Main App 0.5.43's date-first OUTPUT layout. Manual **Send GPX Files** uploads now resolve on the camera to `OUTPUT/dd-MM-yyyy/GOOD|FAILED|ERROR/`.
- Keeps the phone-side Automatic Backup layout unchanged; only the camera upload destination changed.
- Updated the compatibility message so the new layout requires Main App 0.5.43 or newer.

## 1.10.30
- Paired with Main App 0.5.42: per-video smartphone backups now use Main's full MP4 start/end interval and no longer inherit timestamps, missing intervals or clock jumps from the extracted Camera GPX.
- Added a 250 ms phone-point densifier that preserves every real phone fix and interpolates only across gaps <= 5 seconds; longer phone-GPS outages remain visible.
- Increased the foreground GPS request cadence to 250 ms where supported by the phone/location provider.
- Added support for media-only ERROR queue rows, allowing a phone backup for an ERROR MP4 when its movie interval is still readable.
- Versioned recent backup identities so pre-1.10.30 backups in the retained 14-day phone-GPS window can be rebuilt exactly once after upgrade, while older queue history stays marked processed.
- **Send GPX Files** now carries GOOD/FAILED/ERROR classification and uploads each backup beside its recording under `OUTPUT/<STATUS>/dd-MM-yyyy/`.
- Preserved full pending-queue pagination and the test-only JVM `org.json` dependency used by GitHub unit tests.

## 1.10.29
- Added a manual **Send GPX Files** button to Automatic Smartphone GPS Backup. The button is enabled only when the Client has unsent per-video `_backup.gpx` files and a live Main App connection; it is grey/disabled after all pending files are confirmed copied.
- Every newly generated per-video backup is placed in a durable send queue. A new backup automatically makes the button available again; successful files are removed one-by-one so a partial failure retries only what remains.
- Existing per-video backups in `dd-MM-yyyy/` folders are discovered and offered for sending after upgrade. The daily `PHONE_GPX_BACKUP_dd-MM-yyyy.gpx` root file is intentionally not sent.
- Uploads are checksum-verified end-to-end with SHA-256 and byte size before the Client marks a backup sent. A same-name/same-checksum camera file is idempotent success; a different same-name camera file is never silently overwritten.
- Paired with Main App 0.5.41, which stores each uploaded backup in `OUTPUT/dd-MM-yyyy/<video>_backup.gpx`.

## 1.10.28
- Automatic Backup now writes the global daily phone track at the selected Backup root as `PHONE_GPX_BACKUP_dd-MM-yyyy.gpx`.
- Creates/uses a direct `dd-MM-yyyy/` subfolder for per-video backups and saves each MP4 backup as `<MP4-base-name>_backup.gpx` (for example `260816_102735266_backup.gpx`) with no extra GOOD/FAILED/ERROR directory layer.
- Uses the MP4 filename timestamp for the date folder when available, falling back to the Main App completion timestamp.
- Fixed pending-GPX pagination: the Client now reads every page exposed by Main App 0.5.40 instead of only the first page, so newer MP4s cannot be skipped after the queue grows.
- If exact replacement of every Camera GPX timestamp is impossible but phone fixes exist during the video interval, the Client now saves those actual phone fixes as a direct-track fallback instead of omitting that MP4 backup entirely.
- Added unit coverage for backup naming/date rules and pending-GPX item parsing.

## 1.10.27
- Fixed GitHub/JVM unit-test failures in `DashboardClientTest`: local unit tests now use the real `org.json` implementation (`org.json:json:20231013`) instead of Android's non-functional framework stubs.
- Application/runtime dependencies are unchanged; the JSON-java dependency is `testImplementation` only.

## 1.10.26
- Main Camera App Monitor now shows **Output Folder** on two lines: the label first, then the complete folder path.
- Output Folder is explicitly multi-line, horizontally non-scrolling, non-ellipsized, and selectable so long Android storage paths remain fully visible and can be copied.
- Uses the simple Android line-break strategy with hyphenation disabled so paths wrap naturally without modifying the underlying path value.

## 1.10.25
- Final-release audit hardening paired with Main App 0.5.40.
- Orders live/full snapshots with Main process identity and device elapsed time so Pilot GPS/NTP wall-clock changes cannot freeze or roll back Camera/Fragment Storage state.
- Correctly treats Main-App/Pilot restart as a new truth epoch and rejects delayed responses from the retired process.
- Prevents transport-only poll timestamps from incrementing the UI dashboard revision, eliminating unnecessary high-frequency re-renders.
- Preserves truthful Fragment Storage presentation: exact selected-family value when proven, otherwise Unknown recording type plus all concrete Stitched / Unstitched / Google Street View values.
- Added regression coverage for wall-clock rollback, process reboot, delayed old responses and transport-only polling.
- Restored the Gradle wrapper bootstrap JAR and refreshed final-release audit/build documentation.

## 1.10.24
- Parses Main App 0.5.39 Camera mode provenance (`modeSource`, `modeUpdatedAt`).
- When a recording family is provable, `Recording Type` and `Fragment Storage` show that mode's live Camera selection.
- When Camera 5.18.11 does not expose a safe current idle mode, the Client no longer keeps a stale selected value; it shows `Recording Type: Unknown` and the current Stitched / Unstitched / Street View Fragment Storage values together.

## 1.10.23
- Manual **Connect** now requests `/api/v1/dashboard?syncCameraSettings=1`, pairing with Main App 0.5.38's synchronous Camera Fragment Storage refresh.
- The first rendered Main Camera App Monitor card therefore uses the freshly-read Camera Fragment Storage data immediately on connection.
- Background dashboard/live-status polling remains unchanged and does not force extra `/efs` reads. The query parameter is backward compatible with older Main Apps, which safely ignore it.

## 1.10.22
- Main Camera App Monitor now shows the selected Camera recording family as a dedicated `Recording Type:` row: **Stitched**, **Unstitched**, **Google Street View**, or **Time Lapse**.
- `Fragment Storage:` continues to show the exact selected limit for that recording family, preferring structured `sizeGb`/`durationMinutes` values and preserving legacy raw/display compatibility.
- The recording-type label is driven by the Main App `fragmentStorage.mode` field, so a payload such as `mode=streetView` and `sizeGb=6` renders as `Recording Type: Google Street View` and `Fragment Storage: 6 GB`.

## 1.10.21
- Paired with Main App 0.5.37 and the supplied Camera 5.18.11 Fragment Storage contract.
- Parses and retains the exact Camera raw value plus structured `limitType`, `sizeGb`, and `durationMinutes`; 4/6/8/10 GB values are displayed from the numeric field rather than from presentation text.
- Keeps compatibility with older Main Apps by deriving the same structured limit from legacy raw/display strings when the new fields are absent.
- Uses the Main App device-uptime process marker as a Fragment Storage revision epoch, so a real value after a Main-App restart cannot be rejected because its revision counter restarted at 1.

## 1.10.20
- Paired with Main App 0.5.35's Camera-APK-grounded Divider restart handling.
- Fragment Storage now accepts the Main App's monotonic setting revision, so a Camera UI change such as 4 GB -> 6 GB updates even if Pilot's wall-clock timestamp moves backwards.
- Preserves the existing rule that a transient `Unavailable` response cannot erase a concrete Fragment Storage value.

## 1.10.19
- Paired with Main App 0.5.34, which reads the stock Camera 5.18.11 `/efs/video.properties` Fragment Storage setting when permitted and moves completed Divider fragments independently of the unsupported public option call.
- Preserve existing monotonic Fragment Storage and recording-status behavior.

## 1.10.18
- Paired with Main App 0.5.33 filesystem-proven Fragment Storage rollover.
- A temporary/unavailable Fragment Storage refresh can no longer erase a previously concrete protocol or observed setting; the freshest concrete value is retained across live/full-dashboard merges.
- If Fragment Storage is still unavailable, Main Camera App Monitor now shows the Main App's short diagnostic reason instead of only the word `Unavailable`.
- Preserves the existing monotonic Pilot One Recording Status behavior while an older completed fragment processes/moves during the current fragment.

## 1.10.16
- Paired with Main App 0.5.31 Fragment Storage-aware rolling transfer behavior.
- Added `Fragment Storage:` to the **Main Camera App Monitor** card and displays the current Camera-provided setting, including `Off (Unlimited)` when segmentation is disabled.
- Parses the additive `fragmentStorage` object from both full dashboard and high-frequency live status, including per-mode Stitched, Street View, Unstitched and Time Lapse values.
- Newer live Fragment Storage data is preserved against an older/slower full-dashboard response, matching the existing monotonic Camera/Monitoring/transfer merge behavior.
- Remains backward compatible with older Main App versions by displaying Fragment Storage as unavailable instead of failing the connection.

## 1.10.15
- Paired with Main App 0.5.30's monotonic Camera lifecycle generation and per-file transfer ownership.
- Explicit Camera recording state is now completely independent from output transfer rows; copying/finalizing an already-completed MP4 cannot toggle Pilot One Recording Status.
- Client session merge rejects older Camera lifecycle generations/timestamps and prevents a completed `Ready` state from reverting to stale `Recording` within the same generation.
- Slow/stale full-dashboard responses cannot overwrite newer `/live-status` recording, Monitoring, OUTPUT-folder or transfer fields.
- A Main App process restart is handled as a new epoch when it presents a lower generation with a newer Camera event timestamp.

## 1.10.14
- Paired with Main App 0.5.29 per-video Camera lifecycle handling.
- Legacy Main 0.5.27/0.5.28 `pilot-camera-file-close` and `pilot-camera-imu-close` finalizing states are now treated like the existing write-idle compatibility state, so they cannot falsely drop an active recording to Ready.
- Explicit Camera `addFile` completion and same-file active transfer still render Ready; a different new recording remains Recording.
- Same-file transfer matching now normalizes Camera `.part` / `.tmp` writer aliases to their finalized video identity.

## 1.10.13
- Paired recording-status stability fix for Main App 0.5.28.
- Added backward-compatible protection against Main App 0.5.27's one-second `pilot-camera-write-idle` false-stop state.
- Strong stop/finalization signals and same-file output transfers still switch the UI to Ready.

## 1.10.12
- Full recording/data-flow audit paired with Main App 0.5.27.
- Added 250 ms polling of the Main App's lightweight `/api/v1/live-status` endpoint while retaining the full dashboard at 3-second cadence for storage, battery/temperature, reports and Bluetooth/GPS diagnostics.
- Main screen now receives an immediate process-local session-update broadcast, removing the previous extra 1-second UI-sync delay for recording/transfer changes.
- Parses the Main App's explicit `cameraRecording.finalizing` state; finalization is always displayed as `Ready`, never `Recording`.
- A same-video active output transfer now suppresses a stale recording indication even when an older Main App source says `pilot-camera-broadcast`; a genuinely new filename remains `Recording`.
- Live payloads merge only recording, Monitoring, OUTPUT folder and transfers, preserving the slower full-dashboard fields.
- Backward compatible with Main App <=0.5.26: HTTP 404 on `/api/v1/live-status` automatically falls back to the legacy 3-second full-dashboard polling instead of dropping the connection.
- Connection-loss reset, contingency smartphone GPS independence, sounds, transfer UI and diagnostics behavior remain intact.

## 1.10.11
- Simplified each active transfer row to one purple progress bar plus one status line directly below it.
- Removed the standalone filename-only line above the progress bar.
- The line below the bar now combines current activity and video filename, for example `Moving: video.mp4`, `Verifying: video.mp4`, or `Finalizing: video.mp4`.
- The separate pre-transfer `Processing / generating GPX: <file>` line remains available when no progress bar exists yet.
- No Main App/API change is required; lean/stable build configuration is unchanged from 1.10.10.

## 1.10.10
- Added a single live event line below the Main Camera App Monitor transfer progress area.
- During active copies it translates the Main App transfer phase into short operator-facing text such as `Moving: <file>`, `Verifying: <file>`, and `Finalizing: <file>`.
- Before copying begins, a Main App `processing:<file>` state is shown as `Processing / generating GPX: <file>`.
- The line is hidden when there is no active processing/transfer event and is cleared immediately on camera disconnect.
- No Main App/API change is required; the Client uses the existing `transfers[].phase` and `monitoring.lastStatus` fields.
- Lean/stable Gradle toolchain remains unchanged from 1.10.9.

## 1.10.9
- Added a separate **Pilot One Bluetooth / GPS** diagnostics sub-screen opened from the main Client dashboard.
- Shows all Bluetooth devices reported connected by the Main App, with likely GPS/GNSS devices first, connection transport and RSSI when available.
- Shows the camera's current Android location source, provider, mock-location state, last-fix age and accuracy; an external Bluetooth GPS using a mock-location app is identified when it can be inferred safely.
- Shows system GNSS running/fresh state, visible/used satellite counts, average/max C/N0, constellation counts and time-to-first-fix.
- Clearly separates injected/mock external fixes from Pilot One system-GNSS satellite data so signal information is not misattributed.
- The screen clears automatically with the existing 1.10.7 connection-loss reset because it reads the shared live dashboard session.
- Backward compatible with older Main Apps: the sub-screen reports that diagnostics are unsupported instead of failing the connection.
- Lean/stable Gradle toolchain remains unchanged from 1.10.8.

## 1.10.8
- Decoupled Automatic Smartphone GPS Backup from the Main App connection. Phone GPS collection can now be started with no camera connection and stays active when the camera disconnects or the user presses Disconnect.
- Camera connection loss now removes only the optional camera endpoint from the GPS backup service; it no longer stops the GPS service, disables the backup toggle, or clears collected phone GPS state.
- While disconnected, the backup service continues quality-filtered phone GPS collection, the internal timeline, and the daily `PHONE_GPS_dd-MM-yyyy.gpx` archive.
- When a camera connection is established again while GPS backup is active, the live camera address is attached automatically and finalized camera-GPX backup matching resumes without restarting the GPS toggle.
- The selected backup folder remains required because the daily phone GPX archive is part of the contingency record.
- Lean/stable Gradle toolchain remains unchanged from 1.10.7.

## 1.10.7
- Changed connection-loss behavior to fail closed: the first failed Main App dashboard poll ends the camera session instead of entering automatic RECONNECTING/backoff.
- On connection loss the foreground camera connection service stops, its requested/last-success state is reset, and `ClientSessionState` is cleared immediately.
- The visible Client UI returns to `Connect` on its next 1-second service-state sync and clears all Main App-derived values: recording status, monitoring/output folder, transfers, camera storage, battery, temperature, external storage, and report counts.
- ReportActivity also clears its displayed report entries and resets the shared camera session if it is the first screen to detect the lost connection.
- Automatic Smartphone GPS Backup is stopped on camera connection loss so no separate background camera HTTP polling survives after the main IP session resets; its saved folder and collected local GPS/archive data are preserved.
- Saved IP address/history and other phone-local settings/data remain intact for manual reconnection.
- Lean/stable Gradle toolchain remains unchanged from 1.10.6.

## 1.10.6
- Fixed startup keyboard behavior: the IP address field no longer receives initial focus when the Client opens.
- The software keyboard is explicitly hidden at Activity startup and appears normally when the user taps the IP address field.
- Runtime behavior, connection logic, UI layout and lean/stable build toolchain are otherwise unchanged from 1.10.5.

## 1.10.5
- Main Camera App Monitor reads the dedicated live `outputFolder` dashboard value.
- OUTPUT folder display updates on the next dashboard poll after the Main App selection changes.
- Backward compatible with older Main Apps by falling back to `reportHealth.destination`.

## 1.10.4

- Fixed App Sounds unmute while Pilot One is already above the temperature warning threshold: unmute rearms the temperature alert latch/cooldown and requests a fresh dashboard poll, so an actively hot camera warns again.
- Audited every client sound path. GOOD/FAILED/ERROR events remain intentionally non-replaying while muted, but future new events sound normally after unmute.
- Fixed repeated MP4 storage-write notification sounds: later new write faults are allowed to alert even while the previous fixed-ID notification remains visible.
- Fixed Android 7.x unmuted MP4 storage-write notifications to explicitly request the default notification sound.
- Muting still immediately stops any active direct temperature/report sound and keeps MP4 write notifications silent.
- Lean/stable build toolchain remains unchanged from 1.10.3.

## 1.10.3

- Rebuilt directly from the accepted 1.10.1 baseline; discarded 1.10.2 behavior is not included.
- Prevents the completed MP4 being transferred to OUTPUT from briefly changing Pilot One Recording Status to Recording when the Main App signal is only filesystem-derived.
- Pilot Camera broadcast recording state remains authoritative and is not suppressed by an output transfer.
- App Sounds card now shows only its title and the single sound toggle button; the explanatory status text was removed.
- Pilot One Device Temperature now renders black labels with colored values: current green, warning red, return blue.
- Lean/stable Gradle configuration remains unchanged.

## 1.10.1

- Simplified **Pilot One Recording Status** to one line: `Pilot One Recording Status: Recording` (red) or `Pilot One Recording Status: Ready` (blue).
- Replaced the previous combined diagnostics text with a compact **Main Camera App Monitor** section showing only Monitoring ON/OFF, the Main App OUTPUT folder, and active file-transfer progress.
- Active transfer rows now show only the file name plus a purple progress bar; historical write errors and report-file diagnostics are no longer shown in this section.
- Removed the separate **Active Output Copies** section.
- Added a persistent **App Sounds** control directly below **Screen Always On**. Muting suppresses report sounds, temperature warnings, temperature test sound, and uses a silent MP4-write notification channel.
- Simplified **Pilot One Device Temperature** to Current / Warning / Return temperatures and removed thermal-device/source naming.
- Removed the obsolete MainAppStatusFormatter code used by the old monitor/output diagnostic card.
- Preserved the lean/stable build profile: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 33, Build Tools 30.0.3 and Java 17.

## 1.10.0

- Combined the separate **MP4 Storage Write Status** and **Main App Monitoring / Reports** cards into one **Main App Monitoring / Reports / MP4 Output** section.
- Fixed the visible MP4-output status to use the camera dashboard's full persisted `storageWriteAlerts` history instead of only alerts classified as new in the current phone connection.
- The combined section now updates during normal successful output work by showing live `COPYING` / `VERIFYING` / `FINALIZING` transfer progress from the Main App.
- When no transfer is active, the section shows the latest GOOD / FAILED / ERROR processing report so successful completed work produces a visible update too.
- Background notifications continue to alert only on genuinely new write failures; reconnecting does not replay historical failures as new notifications.
- Added a lean/stable client build profile: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 33, Build Tools 30.0.3 and Java 17.
- Added the complete Gradle wrapper JAR and enabled Gradle daemon, build cache, parallel execution, VFS watching and Kotlin incremental compilation.

## 1.9.9

- Added **Main App Monitoring / Reports** diagnostics driven by the Camera App dashboard.
- Clearly distinguishes Monitoring OFF, missing/unreadable report files, report I/O errors and healthy empty reports.
- Shows the report destination and current GOOD/FAILED/ERROR TXT sizes while connected.
- Report screens explain when Monitoring is OFF or report storage is unhealthy instead of presenting a misleading empty list.
- Suppresses false new-report sound alerts during transient report-read failures.
- Dashboard parser remains backward compatible when connected to older Main App versions that do not provide the optional diagnostics fields.
- Deep paired audit performed with Main App 0.5.22.


## 1.9.7

- Added a prominent Pilot Camera Recording Status card driven by the Camera App dashboard.
- Shows RECORDING / NOT RECORDING and the active MP4 name when available; disconnected data is explicitly marked stale.
- Added a complete per-day phone GPS archive in the selected Backup folder: `dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`.
- Daily phone GPX files contain the full quality-filtered phone timeline for that day, including timestamp, latitude/longitude, altitude when available, accuracy, provider, speed and bearing.
- Daily tracks are rebuilt every 30 seconds from durable internal per-day logs, checksum-verified after SAF writes, and can recover after client restarts.
- Internal daily source logs are retained for 14 days as a recovery window.

## 1.9.5

- Fixed startup ANR/stale-notification recovery after MP4 storage-write alerts.
- Removed cold-start service stop/restart work from the Activity main thread.
- Camera background service is now non-sticky and does not resurrect ghost connection state after process death.
- Old MP4/backup notifications are cancelled asynchronously when the app opens.
- Historical storage-write alerts are baselined on each manual connection and are not replayed as new notifications.
- Bounded storage-alert parsing and new pure-Kotlin storage-alert policy tests.

## 1.9.2

- Added a dedicated Pilot One Device Temperature card separate from battery status.
- Added a Temperature Alert Settings window with a persistent user-defined Celsius threshold.
- Moved temperature warning evaluation into the foreground camera-connection service so alerts continue with the screen off or while another app is open.
- Kept the Labpano `thermal_zone0` temperature source supplied by the Camera App dashboard API.
- Preserved the 73 °C default, 3 °C rearm hysteresis and 10-minute repeat-alert cooldown.
- Added a warning-sound test button and temperature-policy JVM tests.

## 1.9.1

- Replaced the nonstandard Java-21-only wrapper with a standard Java-compatible Gradle wrapper.
- Updated the build stack to Android Gradle Plugin 8.7.3, Gradle 8.9 and Java 17.
- Added one audited GitHub Actions debug-APK workflow.
- Added JVM tests for camera-address normalization.
- Fixed unsupported URI schemes being incorrectly converted into HTTP camera addresses.
- Removed obsolete in-Activity dashboard polling and limited UI refresh work to visible activities.
- Made foreground-service startup failures non-crashing and recoverable.
- Ensured Automatic Backup always begins OFF in a fresh app process.
- Reduced queue processing overhead and retained enough processed IDs for the Camera App queue.
- Added bounded wake-lock renewal for the background camera connection.

## 1.9.0

- Added the foreground background-camera connection service.

## 1.8.9

- Added timestamp-preservation verification for backup GPX files and updated the launcher icon.
## 1.9.3

- Added a purple temperature threshold slider with 0.1 °C steps; the slider and numeric entry stay synchronized in both directions.
- Added MP4 Storage Write Status to the dashboard.
- Added high-priority background notifications for new Camera App MP4 write/verification failures on internal or external storage.
- Added support for the Camera App's additive `storageWriteAlerts` dashboard field while retaining dashboard API v3 compatibility.

## 1.9.4 storage-alert stability
- Storage-write notifications no longer execute on the camera polling thread.
- Multiple storage failures are summarized into one bounded notification.
- Notification delivery failures cannot stall or disconnect the client.
## 1.9.6
- Fixed a recursive dashboard render loop that could make the Client lag, ANR, or crash immediately after connecting.
- Dashboard state now uses a monotonic revision and only re-renders when data actually changes.
- Report-delta processing and transfer-view rebuilding are skipped when those sections are unchanged, reducing UI work during temperature-only polls.
- MP4 storage status now shows only write problems detected during the current manual connection; Camera App history is not displayed as a current fault.
- Cleans up legacy Client 1.9.3 storage notifications across the full historical notification-ID range and storage-alert channel.
- Keeps notification cleanup isolated from the camera polling and UI threads.


## 1.9.8

- Renamed the camera state card to **Pilot One Recording Status**.
- Swapped the Pilot One Recording Status and Screen Always On card positions.
- **RECORDING** is green and **NOT RECORDING** is red; stale camera values keep their semantic color while being explicitly labeled stale.
- Moved GPS / GNSS callbacks onto a dedicated background `HandlerThread` so durable phone-GPS writes do not run on the UI thread.
- Added a best-effort final daily GPX sync when Automatic Smartphone GPS Backup stops, while retaining the internal recoverable daily log.
- Full source audit performed together with Camera App 0.5.16.
