# Build Verification — Client 1.10.11

- Baseline: Client 1.10.10.
- versionCode: 58.
- versionName: 1.10.11.
- Main App API requirements unchanged.
- Lean/stable Gradle configuration unchanged.
- Transfer row statically verified to contain progress bar first and exactly one activity+filename TextView after it.
- Active-transfer global event line is suppressed to avoid duplicate text.
- Pre-transfer GPX-generation event remains available when no transfer exists.
- Transfer formatter focused runtime checks passed.
- XML parsing and ZIP integrity checks passed.
- Full Gradle build was not run because this sandbox cannot retrieve the Gradle 7.6.4 distribution from services.gradle.org.
