# Fragment Storage live fix — Client 1.10.24

Client 1.10.24 consumes the corrected Main App 0.5.39 payload.

When Main App has a strong Camera-derived mode signal, the monitor card shows, for example:

- `Recording Type: Google Street View`
- `Fragment Storage: 8 GB`

If stock Camera 5.18.11 does not expose a safe current idle recording family, the Client deliberately avoids showing a stale/guessed type. It renders:

- `Recording Type: Unknown`
- `Fragment Storage: Stitched: 6 GB • Unstitched: 8 GB • Google Street View: 10 GB`

The per-mode values continue to update from Main App live polling. This is preferable to retaining an old top-level selected value that looks authoritative but is not.
