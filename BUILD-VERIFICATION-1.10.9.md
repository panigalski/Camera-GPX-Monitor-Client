# Build verification — Client 1.10.9

Baseline: Client 1.10.8 Contingency GPS / Lean Stable.

## Static verification completed

PASS:

- versionName `1.10.9`, versionCode `56`;
- `Dashboard` has an optional `deviceDiagnostics` field, preserving old-Main compatibility;
- `DashboardClient` parses Bluetooth, location-source and GNSS diagnostics;
- old dashboards without `deviceDiagnostics` continue to parse with `deviceDiagnostics == null`;
- `PilotDiagnosticsActivity` is declared and reachable from `PILOT ONE BLUETOOTH / GPS DETAILS`;
- Bluetooth screen renders connected device, transport and RSSI/unavailable reason;
- GPS source screen renders provider, mock state, fix age and accuracy;
- GPS signal screen renders satellite counts, C/N0, constellations and TTFF;
- external/mock fixes are explicitly separated from Pilot One system-GNSS signal data;
- the diagnostics screen reads `ClientSessionState`, so the existing connection-loss reset clears it automatically;
- AndroidManifest XML parses successfully;
- Kotlin parser-oriented checks found no syntax errors in changed Kotlin/test sources;
- root Gradle/Kotlin/wrapper configuration is byte-for-byte unchanged from 1.10.8; only the app version metadata changed in `app/build.gradle.kts`.

## Full Gradle build

A complete Gradle build could not be run in this sandbox. `./gradlew --version` correctly attempts Gradle 7.6.4 but fails before Gradle starts because outbound DNS to `services.gradle.org` is unavailable:

`java.net.UnknownHostException: services.gradle.org`

Therefore this verification does not claim that an APK was assembled here.
