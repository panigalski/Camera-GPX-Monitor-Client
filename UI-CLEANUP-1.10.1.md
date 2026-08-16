# Client 1.10.1 — UI cleanup

## Pilot One Recording Status

The card is now a single line:

- `Pilot One Recording Status: Recording` — Recording value red.
- `Pilot One Recording Status: Ready` — Ready value blue.
- `Unknown` is shown in neutral gray before a usable camera status is available.

The active MP4 name is intentionally not shown in this section.

## Main Camera App Monitor

The section contains only:

- `Monitoring ON` / `Monitoring OFF` using the live Main App service state.
- `Output Folder: <path>` using the Main App dashboard OUTPUT/report destination.
- One active transfer row per transfer: file name plus a purple determinate progress bar.

Historical MP4 write errors, report-file health, report counts and latest report text are intentionally not rendered in this section. The separate **Active Output Copies** section was removed.

## App Sounds

A persistent **App Sounds** control appears immediately below **Screen Always On**. When muted it suppresses:

- GOOD / FAILED / ERROR report sounds played by MainActivity.
- Pilot One temperature warning sound played by the background camera connection service.
- Temperature settings test sound.
- Audible MP4 write-failure notifications by routing them through a dedicated silent Android notification channel.

Muting also stops an already-playing MainActivity sound and requests the background service to stop an active temperature warning.

## Pilot One Device Temperature

The main screen now displays only:

- `Current Temperature:` — green normally, red above the warning threshold.
- `Warning Temperature:` — black.
- `Return Temperature:` — black.

Thermal source/device names are no longer shown in the main card or Temperature Alert Settings current-temperature display.
