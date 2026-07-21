# Chain of evidence

- Trace: `CriticalUserJourneysBenchmark_ledgerScrollAndDetail_iter000_2026-07-20-23-26-41.perfetto-trace`.
- App UI thread: utid 15, tid 30604.
- UI thread state totals: Running 805.435 ms; Runnable 54.350 ms; uninterruptible sleep 0.018 ms.
- Long overlapping UI slices: `Choreographer#doFrame` 91.274 ms; `Recomposer:recompose` 47.023 ms; `AndroidOwner:measureAndLayout` 33.404 ms; `Record View#draw()` 43.366 ms.
- RenderThread (utid 13386) had `DrawFrames` 25.654 ms and 23.936 ms, with matching full-display `Drawing` slices of 24.979 ms and 23.594 ms.
- Trace import reports one `power_rail_empty_packet`; the `android_jank` metric is unavailable in the current trace processor.
