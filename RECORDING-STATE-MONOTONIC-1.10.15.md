# Client 1.10.15 — Monotonic Pilot Recording State

## Remaining problem in 1.10.14

Transfer progress and Camera recording display were still cross-coupled. A stale or filesystem-derived `Recording` response could be suppressed while the same filename was transferring, then become visible again when the transfer row disappeared. That produced the observed `Recording` / `Ready` flashes after Stop.

## 1.10.15 behavior

- Main 0.5.30 supplies `cameraRecording.generation`.
- For generation-based Camera state, transfer rows do not participate in recording display at all.
- Within one lifecycle generation, once a newer Ready/completion event is accepted, an older Recording event cannot replace it.
- A newer generation always wins, so the next real recording appears immediately.
- Full-dashboard and `/live-status` responses are merged using Main-App `generatedAt`; older full responses retain their slow diagnostics/report values but cannot rewind newer live recording/Monitoring/OUTPUT/transfer state.
- Legacy Main versions (generation 0) retain the existing compatibility policy.
