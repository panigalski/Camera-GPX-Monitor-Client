# Fragment Storage repair — Client 1.10.17

This release is paired with Main App 0.5.32. The JSON schema remains additive/backward compatible: the Client consumes the existing `fragmentStorage` object from live status and full dashboard responses.

The Client displays the Main App's `display` value directly. With a successful Pilot option read this should be the Camera setting such as `4 GB`. If Main App has to identify the setting from a concrete fragment rollover because the Pilot control endpoint is unavailable, the value is explicitly shown as `4 GB (observed)` (or the corresponding time/size setting).

Realtime merge ordering remains monotonic: a newer live Fragment Storage value is not overwritten by a slower older dashboard response. Transfer rows remain presentation-only and never drive the Pilot One Recording/Ready state.
