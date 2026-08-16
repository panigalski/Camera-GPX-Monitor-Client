# Client 1.10.0 — lean / stable build configuration

The runtime client feature set is unchanged by the toolchain reduction. The client still needs a newer compile SDK than the Pilot camera app because it directly references Android 13's `POST_NOTIFICATIONS` permission API.

## Build profile

- Android Gradle Plugin: **7.4.2**
- Gradle: **7.6.4**
- Kotlin: **1.7.22**
- Gradle JDK: **17**
- `compileSdk`: **33**
- `targetSdk`: **33**
- `minSdk`: **24**
- Build Tools: **30.0.3**
- Java/Kotlin bytecode target: **1.8**

The Main App 0.5.23 uses the same AGP / Gradle / Kotlin / JDK / Build Tools versions, so Android Studio can share those downloads between both projects. Only the Android platform differs: Main compiles against API 28, while Client compiles against API 33.

## Local performance settings

`gradle.properties` enables:

- Gradle daemon
- Gradle build cache
- parallel project execution
- Gradle VFS watching
- Kotlin incremental compilation

Configuration cache is intentionally not enabled because this project prioritizes predictable Android plugin behavior over a small extra configuration-time improvement.

## Android Studio

Use **Gradle JDK 17**. Install Android SDK Platform 33 and Build Tools 30.0.3 once. After the first successful sync, Gradle and Android Studio should reuse their global caches for both this client and the lean Main App project.
