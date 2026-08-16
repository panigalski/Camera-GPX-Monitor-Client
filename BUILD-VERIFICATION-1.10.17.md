# Build / verification notes — Client 1.10.17

Date: 2026-08-13

## Targeted checks completed

- Client Fragment Storage data model and `ClientSessionState` compile with local Kotlin/JVM JSON stubs.
- Realtime merge harness passes: a newer live Fragment Storage display value replaces the older value and remains in the shared dashboard session.
- Main App 0.5.32 retains the same `fragmentStorage` payload shape, so no breaking Client parser change is required.

## Full Android build limitation

A full Gradle Android compile could not be executed in this sandbox because the supplied lean project contains `gradle-wrapper.properties` but not `gradle-wrapper.jar`. Running `./gradlew --offline :app:compileDebugKotlin` fails with `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`.

A physical Pilot One + Android Client test is still required to confirm the camera-specific control-service and filesystem behavior.
