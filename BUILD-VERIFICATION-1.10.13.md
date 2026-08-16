# Build / Verification — Client App 1.10.13

- Version: 1.10.13 / versionCode 60.
- `Models.kt` + `RecordingDisplayPolicy.kt` compile successfully with the local Kotlin compiler.
- Recording-policy harness: PASS.
  - Normal Pilot Camera recording renders Recording.
  - Main App 0.5.27 `pilot-camera-write-idle` compatibility state remains Recording.
  - Strong writer-close / IMU-close finalization states render Ready.
  - Same-file output transfer suppresses stale Recording.
  - A different new recording remains Recording during an older file transfer.
- Full Gradle `testDebugUnitTest assembleDebug` could not run in this sandbox because Gradle 7.6.4 is not cached and outbound DNS/downloads for the Gradle wrapper are unavailable. The project workflow should run the normal full build in a network-enabled environment.
