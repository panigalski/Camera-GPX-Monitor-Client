# Current Output Folder contract

Main App 0.5.43 uses a **date-first** classified output structure:

```text
OUTPUT/
├── GOOD.TXT
├── FAILED.TXT
├── ERROR.TXT
└── dd-MM-yyyy/
    ├── GOOD/
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   ├── <video>_backup.gpx
    │   └── <video-base>_ GOOD.txt
    ├── FAILED/
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   ├── <video>_backup.gpx
    │   └── <video-base>_ FAILED.txt
    └── ERROR/
        ├── <video>.mp4
        ├── <video>.gpx             # when extraction produced a valid GPX
        ├── <video>_backup.gpx      # when supplied by Client Automatic Backup
        └── <video-base>_ ERROR.txt
```

## Classification

- **GOOD:** largest consecutive real CAMM gap is at most 5.000 seconds.
- **FAILED:** largest consecutive real CAMM gap is greater than 5.000 seconds.
- **ERROR:** extraction, validation or permanent processing failure.

Classification is determined before GPX densification. Interpolation cannot convert a recording with a real gap over 5 seconds into GOOD.

## Reports

- `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` at the Output root are cumulative reports.
- Every committed recording gets its own `<video-base>_ <STATUS>.txt` report beside the media.
- If extraction fails before a valid Camera GPX exists, ERROR does not create a fake `.gpx` file.

## Client backup upload

Client 1.10.32 sends only per-video `_backup.gpx` files. Main resolves each upload to the matching `OUTPUT/dd-MM-yyyy/<STATUS>/` directory and verifies the stored file before acknowledging success.
