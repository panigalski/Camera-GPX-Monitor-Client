# Labpano GPX Client 1.8.5 hardening notes

Version 1.8.5 applies the priority fixes from the 1.8.4 static source review.

## Address and session state

The user-facing saved address remains under `server_address`. The foreground backup
service now uses the independent `backup_server_address` key. Clearing an active backup
session therefore no longer erases the last successful camera address.

`ClientSessionState` holds only process-local camera/dashboard state. It survives a normal
Activity recreation but does not automatically reconnect after process death or a fresh
launch.

## Network scheduling

Dashboard and report polling use one scheduled request at a time. A successful request
schedules the next normal refresh. A failure schedules an exponentially delayed retry.
Callbacks carry lifecycle/generation guards so an obsolete result cannot change a newer
connection or a destroyed Activity.

The client accepts the Camera App dashboard API version 2 and pending-GPX API version 3,
while rejecting a future version above 3 when the endpoint supplies `apiVersion`.

## GPS timeline

Timeline file access is serialized by a `ReentrantReadWriteLock`. The location buffer is an
`ArrayDeque`; old entries are removed from the head in constant time. Durable pruning is
performed into a temporary file and swapped under the write lock.

Location fixes are rejected when coordinates/timestamps are invalid, reported accuracy is
worse than 100 m, or movement implies an implausible speed beyond the configured safety
threshold. Network fixes are briefly suppressed after an accepted GPS fix.

## GPX processing

The queue item download is size checked and capped. Every timestamped `<trkpt>` must match
a smartphone fix within the allowed window. A partial match is an error, preventing an
output file from silently mixing camera and phone coordinates.

SAF output is transactional: write a temporary document, read it back, verify byte count
and SHA-256, then replace/preserve the previous file. If the document provider cannot
rename the existing file, the client keeps it and saves under a collision-safe suffix.

## Retry isolation

Retry metadata is persisted per camera queue item. A failed item receives an exponential
backoff while later eligible items can continue. Processed and retry histories are bounded
to prevent unlimited preference growth.

## Build verification

The project was packaged after ZIP integrity, required-file and Kotlin parser checks. The
parser check necessarily reports unresolved Android references outside an Android SDK, but
reported no Kotlin syntax/unterminated-token errors. A full Gradle build was not available
because the Gradle 8.7 distribution and Android SDK were not present locally.
