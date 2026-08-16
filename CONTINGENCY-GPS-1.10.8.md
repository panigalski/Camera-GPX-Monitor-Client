# Contingency smartphone GPS — Client 1.10.8

## Goal
Smartphone GPS is an independent contingency source. Once Automatic Smartphone GPS Backup is started, phone location collection must not depend on whether the Client is connected to the Main App.

## Behavior
- Start Automatic Backup works while the camera is disconnected.
- Main App connection loss does not stop `BackupGpsService` and does not set `backup_enabled=false`.
- Manual Disconnect also leaves `BackupGpsService` running.
- With no camera address, the service continues GPS/GNSS collection, internal timeline writes, daily timeline writes, and periodic SAF synchronization of `dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`.
- Camera-specific pending-GPX polling is skipped while disconnected. It is optional work and is not allowed to terminate the contingency GPS recorder.
- If the Client connects/reconnects while backup is active, the live camera address is attached to the running GPS service automatically. Camera-GPX matching then resumes.
- Pressing Stop Automatic Backup remains the explicit user control that stops phone GPS collection.

## Data retained
The existing quality filter remains unchanged. The daily archive still contains all accepted phone fixes, including UTC timestamp, latitude/longitude, altitude when available, accuracy, provider, speed and bearing metadata. Existing internal retention and daily SAF archive behavior are unchanged.
