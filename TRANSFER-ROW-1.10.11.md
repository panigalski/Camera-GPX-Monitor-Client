# Client 1.10.11 — Transfer Row Simplification

## Requested UI change

When a transfer progress bar is visible, the old filename-only line above it has been removed. The transfer row is now:

```text
[ purple progress bar ]
Moving: video.mp4
```

The same single line changes with the Main App transfer phase, for example `Verifying: video.mp4` and `Finalizing: video.mp4`.

Before a transfer/progress entry exists, the existing `Processing / generating GPX: <file>` line may still appear so GPX-generation activity is not lost. It is hidden as soon as an active transfer row exists, preventing duplicate activity text.

No Main App/API change is required.
