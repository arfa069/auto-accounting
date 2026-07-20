# Verified evidence

- Trace import reported `power_rail_empty_packet: 1`; no power conclusion is available from this trace.
- The built-in `android_jank` metric was unavailable in this trace_processor version.
- The target process main thread is `utid=11`.
- Main-thread `Choreographer#doFrame 148587146` starts at `655458490398463 ns` and lasts `65.503281 ms`.
- Nested slices in that frame: `Recomposer:recompose` `29.331823 ms`, `Compose:recompose` `22.908542 ms`, `AndroidOwner:measureAndLayout` `26.622448 ms`, `draw-VRI[MainActivity]` `35.936407 ms`, and `Record View#draw()` `35.320989 ms`.
- The frame overlaps main-thread states: `Running 65.176 ms`, `S 0.257 ms`, `R 0.070 ms`, with zero I/O-wait rows.
- Main thread runs on CPU 5 for `65.176 ms` during the frame.
- CPU 5 frequency is `1,190,400 kHz` for the full `65.503 ms` frame interval.
- No target-process JIT or GC slice name matches were recorded in this trace.
- The longest target background slice is `DefaultDispatch/decodeBitmap` `81.226 ms`, beginning after the main frame; the longest RenderThread slices are `DrawFrames 16.636 ms` and `shader_compile 12.725 ms`.
