# Automatic Backup layout — Client 1.10.28

## Required output layout

For a selected Backup folder and local date 16-08-2026:

```text
<Backup>/
├── PHONE_GPX_BACKUP_16-08-2026.gpx
└── 16-08-2026/
    ├── 260816_102735266_backup.gpx
    ├── 260816_103812401_backup.gpx
    └── ...
```

The global file is rebuilt from all quality-filtered phone GPS fixes collected while the Automatic Backup service is running that day. Per-video files first try to preserve the Camera/Main GPX timestamp structure and replace its coordinates with matching phone GPS fixes. If complete timestamp matching is impossible but phone fixes were actually collected inside the video interval, the Client writes those truthful phone fixes as a direct-track fallback so the MP4 still receives a backup GPX.

## Reliability correction

Main App 0.5.40 exposes `/api/v1/pending-gpx` as a paginated durable queue. Older Client releases requested only one page. Client 1.10.28 follows `nextOffset` until all pages are read, deduplicates queue identities defensively, and therefore continues discovering newly finalized MP4s even after many older queue items exist.

## Date selection

Per-video folder date comes from the Labpano MP4 timestamp embedded in the filename (`yyMMdd_HHmmssSSS`) when present. The Main App `completedAt` timestamp is the fallback. This prevents a video captured before midnight but processed after midnight from being filed under the wrong date.
