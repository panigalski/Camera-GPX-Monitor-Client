# Client 1.10.14 — Pilot Recording Status Compatibility

Client 1.10.14 is paired with Main 0.5.29 and also protects users who temporarily run Main 0.5.27/0.5.28 during an upgrade.

- `pilot-camera-write-idle`, `pilot-camera-file-close`, and `pilot-camera-imu-close` are treated as legacy inference-only stop states while Main still reports Camera ownership (`finalizing=true`). The UI remains **Recording**.
- `pilot-camera-add-file` remains an explicit completion state and renders **Ready**.
- A transfer for the exact same video suppresses a stale recording indication.
- A new recording is not suppressed just because an older completed video is being copied or verified.

With Main 0.5.29, the Client should normally receive `pilot-camera-broadcast` while capture is active and Ready only after matching Camera completion.

- Temporary Camera writer aliases (`.part` / `.tmp`) are normalized to the same video identity as the finalized `.mp4` / `.sti` name when suppressing stale same-file Recording during transfer.
