# Build / Verification — Client App 1.10.26

- Version: **1.10.26** / versionCode **73**.
- Package: `com.labpano.gpxclient`.
- Build stack: Android Gradle Plugin **7.4.2**, Kotlin **1.7.22**, Gradle **7.6.4**, compileSdk/targetSdk/minSdk **33/33/24**, JVM target **1.8**.
- `gradle/wrapper/gradle-wrapper.jar` is present and contains `GradleWrapperMain`.
- Current core `Models` / `DashboardClient` / `ClientSessionState` / Fragment Storage display compile: **PASS**.
- Executable response-ordering/Fragment Storage regression harness: **PASS** (`CLIENT_FINAL_AUDIT_OK`).
- Updated Client session JUnit source compile: **PASS**.
- Output Folder full-path multi-line rendering/static UI check: **PASS**.
- XML, workflow YAML, manifest components, app resource references, duplicate source units, merge markers and packaging hygiene: **PASS**.
- The configured wrapper starts correctly but this audit environment cannot download Gradle/Android dependencies, so `testDebugUnitTest assembleDebug` must be run in the included online GitHub workflow or another Android build environment.

See `AUDIT-RESULTS.md` for the final release findings and target-device acceptance checklist.
