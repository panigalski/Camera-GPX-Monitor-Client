# Build verification — Client 1.10.8

## Scope
Client 1.10.8 changes only the relationship between smartphone GPS backup and camera connection state on top of 1.10.7. The lean/stable toolchain remains AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 33, Build Tools 30.0.3 and Java 17.

## Verified source contracts
- Automatic Backup startup no longer requires `ConnectionState.CONNECTED`.
- `BackupGpsService.onCreate()` no longer terminates when `KEY_ACTIVE_CAMERA_ADDRESS` is blank.
- A blank camera address causes only camera pending-GPX polling to be skipped; GPS receiver state and daily phone-GPX collection remain active.
- `CameraConnectionService` connection loss/manual disconnect removes `KEY_ACTIVE_CAMERA_ADDRESS` but does not stop `BackupGpsService` or change `KEY_ENABLED`.
- `MainActivity.disconnect()` does not stop Automatic Backup.
- A successful camera connection updates `KEY_ACTIVE_CAMERA_ADDRESS` when Automatic Backup is active so camera-GPX matching can resume.
- Existing location permission and selected-folder read/write permission checks remain required.
- Version bumped to 1.10.8 / versionCode 55.
- XML parsing, delimiter checks, static behavior assertions and ZIP integrity were run for this package.

## Gradle
A complete Android Gradle build is not claimed in this environment. `./gradlew --version` was attempted; the wrapper correctly requested Gradle 7.6.4, then failed with `UnknownHostException: services.gradle.org` because outbound DNS/network access is unavailable in the sandbox.
