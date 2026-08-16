# GitHub unit-test fix — Client 1.10.27

`DashboardClientTest` constructs and parses `org.json.JSONObject` instances. Android local unit tests run on the host JVM, where Android framework classes come from a stub Android JAR and are not functional implementations. Without a JVM JSON implementation, each affected test fails immediately with `java.lang.RuntimeException` at the line that constructs `JSONObject`.

The fix is test-only:

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
```

This does not add JSON-java to the APK because the dependency is scoped to local unit tests only. The Android application continues to use the platform `org.json` implementation on-device.
