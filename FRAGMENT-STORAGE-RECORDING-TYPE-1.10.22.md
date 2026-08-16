# Fragment Storage recording type display — Client 1.10.22

The Main Camera App Monitor card now presents the Camera Fragment Storage state as two explicit rows:

- `Recording Type: Stitched | Unstitched | Google Street View | Time Lapse`
- `Fragment Storage: 4 GB | 6 GB | 8 GB | 10 GB | time limit | Off (Unlimited)`

The type comes from Main App 0.5.37+'s `fragmentStorage.mode` field. The Fragment Storage value continues to prefer the structured `sizeGb` / `durationMinutes` fields, falling back to the canonical display value for compatibility.

Examples:

- `mode=stitched`, `sizeGb=4` -> `Recording Type: Stitched`, `Fragment Storage: 4 GB`
- `mode=unstitched`, `sizeGb=8` -> `Recording Type: Unstitched`, `Fragment Storage: 8 GB`
- `mode=streetView`, `sizeGb=6` -> `Recording Type: Google Street View`, `Fragment Storage: 6 GB`
