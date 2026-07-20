# Verified evidence

- Trace import reported `power_rail_empty_packet: 1`; no power conclusion is available from this trace.
- The built-in `android_jank` metric was unavailable in this trace_processor version.
- The target process main thread is `utid=18`.
- Main-thread `Choreographer#doFrame 148637607` starts at `655489948701993 ns` and lasts `62.306406 ms`.
- Nested slices in that frame: `Recomposer:recompose` `28.447656 ms`, `Compose:recompose` `22.626094 ms`, `AndroidOwner:measureAndLayout` `25.262552 ms`, `draw-VRI[MainActivity]` `33.633073 ms`, and `Record View#draw()` `33.049688 ms`.
- The frame overlaps main-thread states: `Running 61.961 ms`, `S 0.287 ms`, `R 0.059 ms`, with zero I/O-wait rows.
