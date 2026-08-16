# Client 1.10.9 — Pilot One Bluetooth / GPS sub-screen

Client 1.10.9 adds a `PILOT ONE BLUETOOTH / GPS DETAILS` button to the main screen. It opens a dedicated sub-screen that reads the live Main App dashboard session and refreshes automatically.

## Bluetooth section

Shows:

- Bluetooth ON/OFF,
- number of connected devices,
- connected device name,
- Bluetooth address,
- detected connection transport,
- likely GPS/GNSS marker when appropriate,
- RSSI in dBm plus a simple quality label when Main App has a recent passive RSSI observation,
- an explicit RSSI-unavailable note when Android does not expose the measurement safely.

Multiple connected devices are all shown, with likely GPS/GNSS devices first.

## Camera GPS Source section

Shows:

- current/last Android location source,
- provider,
- mock-location YES/NO,
- last-fix age,
- accuracy,
- inferred external Bluetooth GPS device when a mock fix and a likely GPS/GNSS Bluetooth connection coincide.

A stale last fix is not presented as a current source.

## GPS Signal section

Shows:

- system GNSS receiver Running/Stopped,
- signal sample Current/Stale,
- visible satellites,
- satellites used in fix,
- average and maximum C/N0 in dB-Hz,
- constellation counts,
- used-in-fix constellation counts,
- time to first fix,
- age of the last GNSS update.

If the active location is mock/injected (or otherwise not the Android GPS provider), the screen warns that the satellite/C/N0 data is Pilot One system-GNSS information and may not describe the external receiver supplying the active location.

## Connection behavior

The sub-screen uses `ClientSessionState`. Therefore the existing connection-loss reset behavior is preserved: when the Main App connection is lost, the shared dashboard is cleared and the Bluetooth/GPS sub-screen immediately returns all camera-derived values to `--` / Not connected.

The screen is backward compatible with older Main Apps. If `deviceDiagnostics` is absent, it displays an unsupported-version message without affecting the camera connection.
