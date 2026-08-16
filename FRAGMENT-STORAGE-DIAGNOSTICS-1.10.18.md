# Fragment Storage Diagnostics — Client 1.10.18

Client 1.10.18 is paired with Main 0.5.33.

- A known Fragment Storage value is retained when a later live/full-dashboard refresh is temporarily unavailable.
- A newer concrete value still replaces an older concrete value.
- If no value has ever been obtained, `Fragment Storage:` includes the short Main App error reason (for example a connection timeout/refusal) rather than hiding all diagnostics behind `Unavailable`.
- An observed Main value such as `4 GB (observed)` is displayed as supplied and is not rewritten as a Camera-provided value.
- Transfer rows remain independent from the monotonic Pilot One Recording Status.
