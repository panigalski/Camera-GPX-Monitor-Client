# Fragment Storage Size Fix — Client 1.10.21

Client 1.10.21 consumes the structured Fragment Storage contract from Main App 0.5.37.

## Changes

- Stores the exact Main-App `rawValue` and selected recording `mode`.
- Stores `limitType`, `sizeGb`, and `durationMinutes` at both selected-status and per-mode level.
- Displays a size limit from `sizeGb` rather than trusting a presentation string.
- Preserves compatibility with older Main Apps by deriving 4/6/8/10 GB or 10/30 min / 1/2 hour from legacy raw/display values when the structured fields are absent.
- Uses `processStartedElapsedRealtime` as a process epoch before comparing Fragment Storage revisions. This prevents revision 1 from a newly restarted Main App being rejected simply because the Client still has revision 20 from the previous Main-App process.

Expected size path:

`Camera video.storagePart.value = 6gb` → `Main sizeGb = 6` → `Client Fragment Storage: 6 GB`.
