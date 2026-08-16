# Client 1.10.16 — Fragment Storage Monitor

## Main Camera App Monitor

The Client now shows a dedicated line:

`Fragment Storage: <current Camera setting>`

Main App 0.5.31 supplies the value from Pilot Camera's Fragment Storage options. When the Camera reports an empty segmentation value, the Client displays `Off (Unlimited)`. When Stitched and Street View values differ in the shared Stitched media tree, both are shown so the Client does not hide that ambiguity.

The value is received through both the full dashboard and the high-frequency live-status endpoint. A newer live setting cannot be overwritten by an older full-dashboard response.

Client 1.10.16 remains compatible with older Main App versions; when the additive field is absent, the line displays `Unavailable` rather than failing the connection.

## Recording and transfer behavior

Recording status remains governed by the monotonic Camera lifecycle generation introduced in 1.10.15. Transfer rows still do not participate in the Recording/Ready decision. Therefore, moving a completed fragment while the next fragment is recording does not change `Pilot One Recording Status` away from `Recording`.
