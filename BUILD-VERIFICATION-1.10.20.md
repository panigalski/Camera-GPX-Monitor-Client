# Build / Verification — Client App 1.10.20

- Version: 1.10.20 / versionCode 67.
- `Models.kt` + `ClientSessionState.kt` compile successfully with the new Fragment Storage revision field.
- Focused merge harness passed: a `6 GB` setting with revision 4 replaces `4 GB` revision 3 even when the incoming wall-clock timestamp is older.
- Existing rule preserving a concrete value over transient `Unavailable` remains in place.
- Full Android Gradle/APK assembly was not run because the lean source tree has no `gradle-wrapper.jar` and this environment has no Android SDK/system Gradle.
