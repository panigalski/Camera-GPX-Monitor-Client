# Build verification — Client 1.10.7

## Scope
Client 1.10.7 changes only connection-loss behavior on top of 1.10.6. The lean/stable Gradle toolchain remains AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 33 and Build Tools 30.0.3.

## Verified source contracts
- `CameraConnectionService` no longer schedules reconnect/backoff after a failed dashboard fetch.
- First dashboard fetch failure writes `requested=false`, `STATE_DISCONNECTED`, clears `ClientSessionState`, stops camera-dependent automatic backup and stops the foreground connection service.
- MainActivity's 1-second state sync treats `DISCONNECTED`/not-requested as terminal, returns the button to Connect, resets report sound baselines and calls `clearCameraData()`.
- `clearCameraData()` resets recording status, monitoring status, output folder, active transfers, camera internal/external storage, battery, current temperature and report buttons/counts.
- ReportActivity clears its visible report list and shared session if it detects the connection failure first.
- Saved IP/history and phone-local preferences/data are preserved.
- Delimiter-aware Kotlin source scan passed for all modified Kotlin files.

## Gradle
`./gradlew test --offline` was attempted. The wrapper correctly requested Gradle 7.6.4 but the sandbox cannot resolve `services.gradle.org`, so a complete Android/Gradle build could not be performed here.
