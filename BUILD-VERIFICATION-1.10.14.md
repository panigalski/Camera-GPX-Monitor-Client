# Build / Verification — Client App 1.10.14

- Version: 1.10.14 / versionCode 61.
- `Models.kt` + `RecordingDisplayPolicy.kt` compiled successfully with the local Kotlin compiler.
- Recording-display harness: PASS.
  - Legacy `pilot-camera-write-idle` finalizing state remains Recording.
  - Legacy `pilot-camera-file-close` finalizing state remains Recording.
  - Legacy `pilot-camera-imu-close` finalizing state remains Recording.
  - Explicit `pilot-camera-add-file` renders Ready.
  - Same-file active transfer suppresses stale Recording.
  - Temporary Camera aliases such as `video.mp4.part` match the finalized `video.mp4` transfer identity.
  - A different new recording remains Recording during an older-file transfer.
- Updated JUnit source includes the same compatibility cases for the normal project test suite.
- Full Gradle `testDebugUnitTest assembleDebug` could not run in this sandbox because Gradle 7.6.4 is not cached and the Android SDK / outbound dependency download path is unavailable. The included GitHub workflow remains the final full Android build check.
