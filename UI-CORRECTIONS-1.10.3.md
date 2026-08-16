# Client UI corrections 1.10.3

This revision is built directly from Client 1.10.1. The discarded 1.10.2 changes are not carried forward.

## Pilot One Recording Status

The Main App can expose recording state from either the Pilot Camera broadcast or filesystem fallbacks. Copying/moving a completed MP4 can produce filesystem activity for that same MP4. Client 1.10.3 suppresses only a filesystem-derived `recording=true` state when the same MP4 is present in the active transfer list. A `pilot-camera-broadcast` recording state always remains authoritative.

There is no timed post-transfer suppression.

## App Sounds

The App Sounds card contains its section title and one button only. The explanatory status paragraph has been removed. The button text continues to show the current state (`APP SOUNDS: ON` or `APP SOUNDS: MUTED`).

## Pilot One Device Temperature

The three rows are presented as:

- black `Current Temperature:` label + green current value
- black `Warning Temperature:` label + red warning value
- black `Return Temperature:` label + blue return value

The temperature alert settings button remains unchanged.
