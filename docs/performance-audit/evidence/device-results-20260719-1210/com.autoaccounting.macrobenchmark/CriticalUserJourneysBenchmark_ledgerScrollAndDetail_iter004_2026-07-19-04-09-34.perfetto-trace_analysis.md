# Chain of Evidence

- Target package: `com.autoaccounting.benchmark`.
- Device: Xiaomi `24117RK2CC`, Android API 36, serial `2a9ea4bd`.
- Flow: open ledger, scroll seeded entries, open an entry detail.
- Iteration 4: 90 measured frames, maximum frame CPU duration 246.586093 ms, maximum frame overrun 235.738658 ms, 11 frames with positive overrun, no frame over 700 ms.
- `android_frame_timeline_metric` reports 94 app frames, 8 missed frames, 5 missed-app frames, and 3 dropped frames for `com.autoaccounting.benchmark`.
- App frame duration: maximum 247.046875 ms, P95 19.835240 ms, P99 67.597937 ms.
- App jank types include 5 `App Deadline Missed`, 21 `Buffer Stuffing`, and 3 `Dropped Frame` entries.
- Trace import reports `power_rail_empty_packet: 1`; rail-level power data is unavailable in this trace.

## Precise SQL evidence

- Maximum `android_frames` row: frame id `131924567`, interval `[538889286251814, 538889533298689)` ns, duration `247.046875 ms`, UI UTID `13`, RenderThread UTID `6850`.
- UI thread: Running `211.551354 ms`, Sleeping `35.168541 ms`, Runnable `0.285574 ms`, non-I/O D-state `0.041406 ms`. The delay is CPU work, not scheduler starvation or disk I/O.
- RenderThread: Running `60.459218 ms`, Sleeping `185.720939 ms`, Runnable `0.355676 ms`, D-state `0.511042 ms`.
- Dominant UI slices: `Recomposer:recompose` `99.272552 ms`, `Compose:recompose` `70.635156 ms`, traversal/draw about `87 ms`, `AndroidOwner:measureAndLayout` `64.890417 ms`, and `Compose:applyChanges` `24.039166 ms`.
- Dominant RenderThread chain: `DrawFrames` `59.879010 ms`, `flush commands` `54.530156 ms`, `shader_compile` `53.113802 ms`, `cache_miss` `51.645677 ms`, followed by a later `driver_link_program` `22.898073 ms`. This confirms first-use shader work as a second bottleneck after UI recomposition/layout.
- Corrected frequency join: UI ran `186.721094 ms` on CPU 5 at `1,190,400 kHz` and `24.830260 ms` on CPU 3 at weighted `1,907,635 kHz`; RenderThread ran `57.951614 ms` on CPU 5 at weighted `1,103,986 kHz` and `2.507604 ms` on CPU 6 at weighted `901,579 kHz`.
- Main-thread Binder is negligible in this window: the longest Binder/AIDL client slice is `0.345989 ms`, and the synchronous `binder transaction` is `0.305157 ms`.
- Background work includes Compose baseline JIT compilations (largest `9.117604 ms`) and a `12.089270 ms` accessibility query on an app Binder thread. Neither blocks the UI thread in the frame-state evidence, but the JIT activity supports validating the generated Baseline Profile in a later before/after run.
- No app GC slice and no 1080x2400 image decode occur in this worst frame. Bitmap memory stays within `30.852615..31.015469 MiB`; sampled RSS is `207.226562 MiB`.
