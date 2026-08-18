# Labpano GPX Client

Android companion application for the Labpano GPX Extractor Main App. It displays Pilot/Main status, monitors recordings and transfers, collects contingency smartphone GPS, creates per-video backup GPX files, and can manually send those backups back to the camera Output Folder.

**Current release:** 1.10.33 (`versionCode 80`)  
**Package:** `com.labpano.gpxclient`  
**Minimum Android:** 7.0 / API 24  
**Target / compile SDK:** API 33  
**Required matched Main release for the current Output contract:** 0.5.47

## Main functions

- Connects to the Main App dashboard and high-frequency live-status API.
- Displays Pilot recording state, Main monitoring state, full Output Folder path, transfer progress, storage/report health, temperature and diagnostics.
- Displays Camera Fragment Storage information for Stitched, Unstitched and Google Street View modes using Main-provided Camera settings.
- Runs optional **Automatic Backup** smartphone GPS collection independently of the Main connection.
- Recovers delayed per-video backups from the retained daily `PHONE_GPX_BACKUP_dd-MM-yyyy.gpx` archive when the shorter-lived internal timeline has already been pruned.
- Writes one daily phone GPS archive at the selected Backup root.
- Creates one per-video `_backup.gpx` file from phone fixes collected during the complete MP4 interval.
- Densifies short phone-GPS intervals to 250 ms only across gaps `<= 5 s`; it does not bridge larger GPS outages.
- Provides the manual **Send GPX Files** action. The button is enabled when unsent per-video backup GPX files exist, and becomes disabled/grey after all pending files are checksum-verified on the camera.

## Phone Backup Folder layout

```text
BACKUP/
├── PHONE_GPX_BACKUP_dd-MM-yyyy.gpx
└── dd-MM-yyyy/
    ├── <video-base>_backup.gpx
    ├── <video-base>_backup.gpx
    └── ...
```

The daily root GPX is not sent to the camera. Only per-video `_backup.gpx` files are queued by **Send GPX Files**.

With Main App 0.5.47, a sent backup is stored beside its matching recording under:

```text
OUTPUT/dd-MM-yyyy/GOOD|FAILED|ERROR/<video-base>_backup.gpx
```

See [docs/OUTPUT-LAYOUT.md](docs/OUTPUT-LAYOUT.md) for the matched camera-side contract.

## Build

The repository includes the Gradle wrapper and a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

Local prerequisites:

- JDK 17
- Android SDK Platform 33
- Android Build Tools 30.0.3

Run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

`org.json:json` is included as a **test-only** dependency because Android's framework `org.json` classes are stubs in local JVM tests. It is not added to the installed app runtime.

For repository setup and CI details, see [docs/BUILD.md](docs/BUILD.md).

## Repository contents

- `app/` — Android application source, resources, and JVM unit tests
- `gradle/`, `gradlew`, `gradlew.bat` — pinned Gradle wrapper
- `.github/workflows/` — GitHub Actions build workflow
- `docs/` — current build and Output contract documentation
- `CHANGELOG.md` — version history

Generated build output, Android Studio metadata, local SDK configuration, APK/AAB files, and signing material are intentionally excluded by `.gitignore`.
