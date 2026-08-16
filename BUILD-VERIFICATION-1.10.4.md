# Client 1.10.4 — build / sound verification

## Scope

This revision is based on Client 1.10.3 and changes only sound-resume behavior, MP4 write-notification alerting, version/docs, and the focused temperature policy test. The lean/stable Gradle configuration is unchanged.

## Verification performed

- `versionCode=51`, `versionName=1.10.4` verified.
- Kotlin source structural balance checked for MainActivity, CameraConnectionService, and TemperatureAlertSettings.
- All packaged raw sound resources enumerated and every direct sound call site audited.
- Focused Kotlin executable test passed for the temperature latch/unmute semantics:
  - previously disarmed + still hot => no duplicate warning;
  - explicit unmute reset (`armed=true`, `lastAlertAt=0`) + still hot => warning is eligible again.
- Verified unmute calls `TemperatureAlertSettings.resetAlertState(...)` and requests an immediate fresh dashboard poll through the active CameraConnectionService.
- Verified mute still stops active MainActivity MediaPlayer sounds and the active service temperature MediaPlayer.
- Verified MP4 write notifications use the silent channel while muted and audible channel while unmuted.
- Verified pre-Android-8 MP4 write notifications explicitly request `Notification.DEFAULT_SOUND` when unmuted.
- Verified fixed-ID MP4 write notifications use `setOnlyAlertOnce(false)`, so a genuinely new deduplicated fault can alert even when the previous notification is still visible.
- Verified GOOD/FAILED/ERROR report baselines continue to advance while muted, so historical report sounds are not replayed on unmute; subsequent new report entries remain eligible for sound.
- ZIP integrity checked after packaging.

## Full Gradle build

Attempted:

`./gradlew test --offline`

The Gradle wrapper still needs the Gradle 7.6.4 distribution. This sandbox cannot resolve `services.gradle.org`, so the wrapper fails with `UnknownHostException`. Therefore a complete Android Gradle build/APK build is not claimed here.
