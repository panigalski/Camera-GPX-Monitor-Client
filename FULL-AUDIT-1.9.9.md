# Client App 1.9.9 — deep audit

## Scope

Audited Client 1.9.8 together with Main App 0.5.21/0.5.22, concentrating on dashboard polling, report parsing/rendering, background connection lifecycle, failure/retry behavior, report alerts, report deletion, phone GPS collection, daily phone GPX persistence and compatibility with the additive Main App diagnostics.

## Confirmed report-observability failures fixed

### High — valid empty report and broken report looked identical
Client 1.9.8 only received the report arrays. A missing/unreadable camera TXT therefore rendered exactly like a valid empty report: `GOOD (0)`, `FAILED (0)`, `ERRORS (0)`.

**Fix:** Client 1.9.9 parses the Main App's optional `monitoring` and `reportHealth` objects and adds a **Main App Monitoring / Reports** card. It explicitly displays:

- Monitoring OFF — red;
- report missing/unreadable/unwritable or report I/O error — red with reason/destination;
- monitor failure status — orange;
- Monitoring ON / Reports OK — green with each TXT file size and destination.

### Medium — report screens hid the same failure
Opening GOOD/FAILED/ERROR still looked like a legitimate empty list when camera report storage was unavailable.

**Fix:** each ReportActivity now explains whether Monitoring is OFF or report storage is unhealthy instead of presenting “No entries” as the only state.

### Medium — transient report-read failure could generate false “new report” audio later
If a failed dashboard read temporarily returned empty arrays, a subsequent recovery could look like a set of newly arrived historical records.

**Fix:** report delta/audio processing is suspended while `reportHealth` says report storage/read I/O is unhealthy. Historical entries restored by the next successful poll do not create false new-report sounds.

## Network / connection audit

- Dashboard HTTP uses explicit 5-second connect and 8-second read timeouts.
- Dashboard JSON is capped at 5 MiB client-side.
- Background connection polling runs on a single scheduled executor, not the UI thread, with retry/backoff after failures.
- The background connection service is non-sticky and clears ghost “requested/connected” state if Android destroys it.
- Manual Connect performs the initial fetch; the service avoids an immediate duplicate dashboard fetch and starts its normal 3-second polling cadence afterward.
- Dashboard API version validation rejects a newer incompatible major version, while optional 0.5.22 fields are backward compatible with older Main Apps.

## GPS / backup audit

- Phone GPS/GNSS callbacks run on a dedicated `HandlerThread`; durable daily-log writes do not execute on the UI thread.
- Automatic Smartphone GPS Backup remains opt-in and uses a foreground location service.
- Daily phone track data is held in a durable internal per-day log and periodically rebuilt into `PHONE_GPS_dd-MM-yyyy.gpx` under the selected Backup folder.
- A best-effort final daily GPX sync runs on service stop.
- Daily backup writes use temporary files/documents and verification/checksum logic before replacement where supported by the storage path.
- Existing per-video replacement GPX workflow remains separate from the complete per-day phone GPX archive.

## Remaining risks / design constraints

### Medium — Main App LAN API has no authentication
The Client necessarily connects to the Main App's unauthenticated HTTP service. Use a trusted/private vehicle Wi-Fi network. Authentication should be added to the pair if the camera will join untrusted networks.

### Low — dashboard report counts are a bounded recent window
The Client displays only the entries supplied by the dashboard (currently up to 500 most recent lines per report), not the total lifetime line count in the cumulative camera TXT. The camera files themselves retain all records.

### Low — cleartext HTTP
`usesCleartextTraffic=true` is intentional for direct Pilot LAN communication. It provides no transport confidentiality/integrity against an attacker on the same network.

### Low — high-rate long driving sessions depend on storage health
The daily phone archive is recoverable from internal logs, but a persistently revoked/full Backup destination cannot be repaired until storage access is restored. Existing storage status/error UI should be monitored during long sessions.

## Validation performed

- `Models.kt` + `DashboardClient.kt` compiled against focused `org.json` stubs after the new optional fields were added.
- Source review confirmed all Dashboard constructors/call sites include the new diagnostics models.
- Android manifest parses as valid XML and Kotlin source brace sanity checks passed.
- Existing background connection and GPS services use executors/HandlerThread rather than doing network/location file work on Activity UI callbacks.
- Full Gradle tests/build were attempted but the wrapper cannot download Gradle 8.9 because this sandbox cannot resolve `services.gradle.org`.
