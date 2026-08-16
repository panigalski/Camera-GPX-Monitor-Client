# Build / verification — Client 1.10.0

## Focused checks passed

- `MainAppStatusFormatter.kt` and `Models.kt` compile and run with Kotlin language/API level 1.7.
- Focused runtime checks verify:
  - an active transfer produces `MP4 OUTPUT ACTIVE` with live percentage;
  - a persisted camera-side storage-write failure remains visible after reconnect semantics;
  - an idle healthy state includes the latest GOOD report so successful work updates the combined card.
- Source inspection confirms the standalone `MP4 Storage Write Status` card was removed and both report health and MP4 output state render inside one card.
- The visible status uses `dashboard.storageWriteAlerts`; the background notification service still uses `StorageAlertPolicy` to suppress historical notification replay.
- Client build profile changed to AGP 7.4.2 / Gradle 7.6.4 / Kotlin 1.7.22 / compile+target SDK 33 / Build Tools 30.0.3 / JDK 17.
- Standard `gradle-wrapper.jar` is included.
- Manifest/resource XML and GitHub workflow syntax were checked.

## Full Gradle build

A complete Android Gradle build cannot be claimed in this sandbox because the Gradle 7.6.4 distribution, Android SDK packages and Maven artifacts are not locally cached and outbound dependency downloads are unavailable. Use the included GitHub Actions workflow or a networked Android Studio installation for `testDebugUnitTest assembleDebug`.

A wrapper startup check was attempted in this environment. The included wrapper JAR started correctly and requested `gradle-7.6.4-bin.zip`; the attempt then failed with `UnknownHostException: services.gradle.org`, confirming the remaining blocker is network access rather than a missing wrapper bootstrap file.
