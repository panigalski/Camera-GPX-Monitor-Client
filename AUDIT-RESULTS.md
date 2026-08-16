# Final Release Audit — Client App 1.10.26

Audit target: `com.labpano.gpxclient`, versionCode **73**, versionName **1.10.26**.

Release delta: Output Folder full-path rendering was added after the 1.10.25 core audit. It is presentation-only: the existing full `Dashboard.outputFolder` value is rendered below its label in an unlimited, non-ellipsized, non-horizontal-scrolling TextView.

## Release conclusion

No additional source-level API mismatch or known release-blocking defect was found after the fixes below and the final static/focused-runtime checks. This does not replace a final install/run acceptance test on the target phone paired with Main App 0.5.40 and a Pilot One running Camera 5.18.11.

## Release-blocking issues corrected in this audit

- Full and live Main-App responses are ordered primarily with `generatedElapsedRealtime` plus `processInstanceId`, not Pilot wall clock. GPS/NTP/system clock rollback therefore cannot make a valid newer Fragment Storage/recording snapshot look stale.
- A new opaque Main-App process ID defines a new truth epoch even after a Pilot reboot resets device uptime and Main counters. Delayed responses from the retired process are rejected.
- A new Main process may replace an old concrete Fragment Storage value with a fresh unavailable baseline while it re-establishes Camera settings; stale values are not preserved across process epochs.
- Within the same known Main process, lower Camera lifecycle generations cannot be accepted merely because a wall-clock timestamp is newer.
- Poll transport timestamps are normalized out of stored UI state. A 4 Hz live poll that changes only transport metadata does not increment the dashboard revision or force a UI render.
- The manual Connect path continues to call `/api/v1/dashboard?syncCameraSettings=1`, so Main App 0.5.40 performs the synchronous Camera-settings refresh before the first rendered dashboard.
- Fragment Storage rendering remains truthful: a proven family shows its exact value; an unprovable Camera idle mode shows `Recording Type: Unknown` plus the concrete Stitched / Unstitched / Google Street View values instead of retaining a stale selection.
- Restored `gradle/wrapper/gradle-wrapper.jar` so the source archive is bootstrappable.

## Checks completed

- Current `Models`, `DashboardClient`, `ClientSessionState`, and `FragmentStorageDisplayPolicy` compiled with JSON API stubs: PASS.
- Focused executable Client regression harness: PASS (`CLIENT_FINAL_AUDIT_OK`). It covers 6→8 GB live update, Pilot wall-clock rollback, stale same-process response rejection, Main/Pilot reboot process epoch replacement, delayed retired-process rejection, and no UI revision churn from transport timestamps alone.
- Updated `ClientSessionStateTest` compiles with JUnit API stubs: PASS.
- Stitched / Unstitched / Google Street View / Unlimited / time-based Fragment Storage display tests are retained in source.
- Manifest/resource XML parse: PASS.
- GitHub workflow YAML parse: PASS.
- Manifest component-source lookup: PASS.
- App resource-reference scan: PASS.
- Duplicate Kotlin source-unit scan: PASS.
- Merge-marker and unfinished-task-marker scan: PASS.
- No APK/AAB/build directory/keystore/local.properties is packaged: PASS.
- Gradle wrapper JAR contains `org.gradle.wrapper.GradleWrapperMain`: PASS.

## Build-system state

- Android Gradle Plugin: 7.4.2
- Kotlin Android plugin: 1.7.22
- Gradle distribution: 7.6.4 with distribution SHA-256 configured
- compileSdk / targetSdk / minSdk: 33 / 33 / 24
- Java/Kotlin bytecode target: 1.8
- GitHub workflow uses Java 17 and runs `testDebugUnitTest assembleDebug`.

The local audit environment cannot download the Gradle distribution/Android artifacts, so a complete Android unit-test + APK assembly was not executed locally. The restored wrapper starts and reaches the configured Gradle 7.6.4 distribution download before network/DNS access blocks it.

## Required final device acceptance

Before freezing the binaries, install Client 1.10.26 with Main 0.5.40 and verify: first Connect immediately reflects Camera Fragment Storage; 4/6/8/10 GB changes update while connected; each recording family renders correctly when Main can prove it; Unknown mode shows all three per-mode values; disconnect clears old state; reconnect/restart/reboot cannot resurrect stale values; and normal transfer/temperature/GPS/report screens still function.
