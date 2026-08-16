# Client 1.9.4 - storage alert stability fix

The MP4 storage-write notification path was decoupled from the single camera polling executor.

Changes:
- Android notification delivery runs on a separate executor and cannot block camera dashboard polling.
- Storage alert IDs are marked seen before notification dispatch, preventing repeated replay after a notification-service fault.
- Multiple new failures in one poll are summarized into one notification.
- A single notification ID replaces the previous storage alert instead of accumulating many SystemUI entries.
- Notification fields and BigText payloads are bounded to avoid oversized Binder notification payloads.
- Android 13+ notification permission is checked before posting.
- Notification exceptions are isolated from connection/retry state.

The MP4 storage status card in MainActivity still displays the complete latest dashboard alert data.
