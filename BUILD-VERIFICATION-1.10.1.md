# Client 1.10.1 — build / source verification

## Version

- `versionCode = 48`
- `versionName = 1.10.1`

## Lean/stable build profile preserved

Client 1.10.1 keeps the 1.10.0 lean toolchain unchanged:

- Android Gradle Plugin 7.4.2
- Gradle 7.6.4
- Kotlin 1.7.22
- compileSdk / targetSdk 33
- minSdk 24
- Build Tools 30.0.3
- Java/Kotlin target 1.8
- Gradle JDK 17 recommended

The root `build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, and wrapper distribution configuration are byte-for-byte unchanged from Client 1.10.0. The standard `gradle-wrapper.jar` remains included.

## Focused source checks

Passed static checks for:

- one-line `Pilot One Recording Status:` rendering;
- `Recording` red / `Ready` blue state mapping;
- `Main Camera App Monitor` title;
- Monitoring ON green / OFF red rendering;
- OUTPUT folder path sourced from the Main App dashboard report/output destination;
- purple transfer progress tint;
- transfer rows containing only file name + progress bar;
- removal of the separate `Active Output Copies` section;
- removal of the old report/write-error diagnostic formatter from the monitor card;
- persistent app-sound mute preference;
- report-sound mute guard;
- background temperature-warning mute guard;
- temperature-test mute guard;
- dedicated silent MP4-write notification channel while muted;
- thermal source/device names removed from temperature UI;
- XML resource parsing.

Kotlin source delimiter balance was checked for every modified Kotlin file. A parser-oriented `kotlinc` pass reported only expected unresolved Android/app symbols because this sandbox has no Android SDK `android.jar`; it reported no Kotlin syntax/`expecting` errors in the modified files.

## Full Gradle build attempt

`./gradlew --version` was attempted to validate the wrapper. The wrapper correctly requested Gradle 7.6.4 but the sandbox cannot resolve `services.gradle.org`, so the distribution cannot be downloaded here. Therefore a full Android Gradle build / APK build is not claimed in this environment.
