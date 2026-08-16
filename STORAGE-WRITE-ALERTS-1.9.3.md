# Client 1.9.3 — temperature slider and MP4 write alerts

The Temperature Alert Settings screen now supports both direct numeric entry and a purple slider. The range remains 0–150 °C in 0.1 °C increments and the existing threshold/rearm/cooldown policy is unchanged.

The background camera connection service reads the Camera App's `storageWriteAlerts` dashboard field every normal dashboard poll. New failures create a high-importance Android notification even while the Client App is in the background. The main dashboard also shows the most recent retained MP4 write problem in red, including storage type, video, operation, message and destination.
