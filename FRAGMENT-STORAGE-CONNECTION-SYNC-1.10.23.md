# Fragment Storage connection sync — Client 1.10.23

The manual **Connect** flow now uses a dedicated synchronized first dashboard request:

`GET /api/v1/dashboard?syncCameraSettings=1`

Main App 0.5.38+ responds only after it has forced a fresh read of Camera 5.18.11's persisted Fragment Storage properties. The Client renders that returned dashboard immediately, so `Recording Type` and `Fragment Storage` no longer wait for a later background poll to pick up a Camera setting that changed before connection.

After connection, the existing high-frequency live-status and periodic full-dashboard polling behavior is unchanged. Older Main Apps ignore the extra query parameter and remain compatible.
