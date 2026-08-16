# Build / Verification — Client App 1.10.12

- Version: 1.10.12 / versionCode 59.
- Lean/stable toolchain unchanged: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 33, Build Tools 30.0.3, Java/Kotlin bytecode 1.8; run Gradle with JDK 17.
- Focused Client live-state / recording-display Kotlin harness passed.
- `DashboardClient` compiled against lightweight JSON test stubs, including `/api/v1/live-status` parsing and HTTP-status handling.
- Changed Android-dependent Kotlin files produced no parser/syntax errors under `kotlinc`; unresolved Android symbols are expected in this sandbox because no Android `android.jar` is installed.
- XML parse validation passed.
- Source contract audit passed.
- ZIP integrity passed after packaging.
- Full Gradle build unavailable in this sandbox because `services.gradle.org` is not reachable.
