# Client 1.10.6 — keyboard startup fix

The Client no longer focuses the IP address input automatically when `MainActivity` opens.

Implementation:

- The main page root is `focusableInTouchMode` and explicitly receives initial focus before the IP field is created.
- `MainActivity` declares `android:windowSoftInputMode="stateAlwaysHidden|adjustResize"` so the software keyboard is not restored/shown automatically during startup.
- The IP `AutoCompleteTextView` remains normally focusable/clickable. Tapping it gives it focus, shows its dropdown, and allows Android to show the software keyboard normally.
- No connection/session, sound, temperature, transfer, backup, or monitoring behavior was changed.
