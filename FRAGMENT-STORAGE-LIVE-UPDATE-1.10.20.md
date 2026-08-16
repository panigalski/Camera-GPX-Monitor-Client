# Fragment Storage Live Update — Client 1.10.20

Paired with Main App 0.5.35.

- Parses the Main App's new monotonic `fragmentStorage.revision` field.
- A newer revision replaces the displayed setting even if Pilot One's wall-clock `updatedAt` moved backwards because of GPS/NTP time correction.
- A transient `Unavailable` response still cannot erase a concrete Camera value.
- No transfer state is used to decide `Pilot One Recording Status`; rolling copies remain independent from the Camera capture indicator.
