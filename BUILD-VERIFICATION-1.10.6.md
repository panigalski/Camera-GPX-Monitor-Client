# Build verification — Client 1.10.6

## Scope

Client 1.10.6 changes only startup focus / software-keyboard behavior plus version/docs metadata.

## Checks performed

- AndroidManifest.xml parsed successfully as XML.
- `MainActivity` root page is `focusableInTouchMode` and requests focus before the IP `AutoCompleteTextView` is created.
- MainActivity declares `android:windowSoftInputMode="stateAlwaysHidden|adjustResize"`.
- The IP field remains normally clickable/focusable and retains its existing click/focus dropdown handlers.
- Version verified as versionCode 53 / versionName 1.10.6.
- Lean/stable Gradle toolchain is unchanged from 1.10.5.
- Source ZIP integrity checked with `unzip -t`.

## Full Gradle build

A complete Gradle Android build was not claimed in this environment. The Gradle wrapper may require network access to `services.gradle.org`, which is unavailable in this sandbox.
