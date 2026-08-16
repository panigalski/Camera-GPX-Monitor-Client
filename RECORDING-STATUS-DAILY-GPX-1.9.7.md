# Client 1.9.7 — Pilot recording status and daily phone GPX

## Pilot recording status
The Client reads the additive `cameraRecording` object from the Camera App dashboard API. The UI displays RECORDING / NOT RECORDING and the MP4 filename when the Camera App has one. If the camera link is interrupted, the previously shown state is marked stale rather than presented as live.

## Daily phone GPX
When Automatic Backup is enabled, every phone location fix accepted by the existing quality filter is also appended to a durable per-day internal source log. About every 30 seconds the Client writes the complete day to the selected Backup tree as:

`<Backup>/dd-MM-yyyy/PHONE_GPS_dd-MM-yyyy.gpx`

The GPX contains timestamp, latitude, longitude, altitude when available, accuracy, provider, speed and bearing. Writes are verified with SHA-256. Internal per-day source logs are kept for 14 days so a later service restart can resynchronize a daily file if needed.
