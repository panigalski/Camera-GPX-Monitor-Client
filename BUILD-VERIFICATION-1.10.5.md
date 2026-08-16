# Build / Source Verification — Client 1.10.5

- Baseline: 1.10.4 lean/stable.
- Build toolchain unchanged: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compileSdk/targetSdk 33.
- Dashboard model/parser accepts the additive `outputFolder` field.
- Main Camera App Monitor renders `outputFolder` on every dashboard update.
- Older Main Apps remain compatible through `reportHealth.destination` fallback.

## Full Gradle test attempt

`./gradlew test --offline` was attempted. The wrapper tried to obtain Gradle 7.6.4 from `services.gradle.org` and failed with `UnknownHostException` because this sandbox has no outbound DNS/network access. A complete Android Gradle build is therefore not claimed here.
