# Chain of Evidence

- Target package: `com.autoaccounting.benchmark`.
- Device: Xiaomi `24117RK2CC`, Android API 36, serial `2a9ea4bd`.
- Flow: open Profile, then Automatic Bookkeeping settings.
- Iteration 2: 74 measured frames, maximum frame CPU duration 232.791250 ms, maximum frame overrun 227.148284 ms, 45 frames with positive overrun, no frame over 700 ms.
- `android_frame_timeline_metric` reports 83 app frames, 8 missed frames, 2 missed-app frames, 1 missed-SurfaceFlinger frame, and 6 dropped frames for `com.autoaccounting.benchmark`.
- App frame duration: maximum 232.790677 ms, P95 19.145917 ms, P99 178.814725 ms.
- App jank types include 2 `App Deadline Missed`, 75 `Buffer Stuffing`, 1 `Display HAL`, 6 `Dropped Frame`, and 37 `Unknown Jank` entries.
- Trace import reports `power_rail_empty_packet: 1`; rail-level power data is unavailable in this trace.

## Precise SQL evidence

- Maximum `android_frames` row: frame id `131968772`, interval `[538915003578939, 538915236369616)` ns, duration `232.790677 ms`, UI UTID `6`, RenderThread UTID `7480`.
- UI thread: Running `226.306461 ms`, Sleeping `6.423592 ms`, Runnable `0.060624 ms`; no D-state. RenderThread is mostly waiting (`207.738542 ms`) and runs `24.971301 ms`, so the UI thread is the critical path.
- Dominant UI chain: `Recomposer:recompose` `161.760885 ms`, `Compose:recompose` `139.714740 ms`, `ImageDecoder_nDecodeBitmap` `111.382083 ms`, `Decoding 1080x2400 bitmap` `111.182239 ms`, then traversal/draw `46.130625 ms` and measure/layout `37.744843 ms`.
- A second independent frame, id `131968417`, lasts `161.769687 ms` at `[538914249339825, 538914411109512)` ns. Its UI thread runs `161.253854 ms`; the frame contains another 1080x2400 decode of `72.415000 ms`, plus five 256x256 image decodes around `1.8..2.2 ms` each. This confirms repeated first-use image work rather than a single outlier.
- Corrected frequency join for the worst frame: UI runs `226.306461 ms` on CPU 5 at weighted `1,336,997 kHz` (`1,190,400..2,035,200 kHz`); RenderThread runs `24.971301 ms` on CPU 6 at `2,035,200 kHz`. Runnable delay remains negligible.
- Main-thread Binder is negligible: maximum Binder/AIDL slice `0.270573 ms`, synchronous `binder transaction` `0.237917 ms`.
- No app GC slice occurs in the trace. Bitmap memory rises from `30.255001 MiB` to `41.642696 MiB` (about `11.388 MiB`); sampled RSS is `209.250000 MiB`, with anonymous RSS peaking at `98.015625 MiB`.
- Background work includes baseline JIT compilations (largest `3.664792 ms`) and an `8.228385 ms` GPU-completion wait, but the UI-thread Running time and decode slices identify the principal cause.
