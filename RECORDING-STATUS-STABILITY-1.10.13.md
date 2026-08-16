# Pilot One Recording Status Stability — Client 1.10.13

## Paired behavior
Client 1.10.13 is paired with Main App 0.5.28 for stable `Pilot One Recording Status:` display.

## Compatibility guard
Main App 0.5.27 could send `recording=false`, `finalizing=true`, `source=pilot-camera-write-idle` after a one-second MP4 write gap. Client 1.10.13 treats only that legacy source as a soft/uncertain stop and keeps displaying `Recording` until a strong stop state arrives.

Strong finalization sources such as video writer close, IMU close, Camera completion, and same-file output transfer still render `Ready` as before.
