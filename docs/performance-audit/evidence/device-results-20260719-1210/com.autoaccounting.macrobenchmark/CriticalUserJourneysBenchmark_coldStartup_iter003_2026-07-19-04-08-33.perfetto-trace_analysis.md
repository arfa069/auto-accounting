# Chain of Evidence

- Target package: `com.autoaccounting.benchmark`.
- Device: Xiaomi `24117RK2CC`, Android API 36, serial `2a9ea4bd`.
- Flow: cold startup to the home screen.
- Iteration 3 TTID: 537.460520 ms (maximum of five final runs).
- `android_startup` reports intent received at 538827114836682 ns and first frame at 538827657457463 ns, a 542.620781 ms interval.
- Main-thread state before first frame: Running 262.511820 ms; Runnable 3.769850 ms; uninterruptible sleep 130.327185 ms; interruptible sleep 111.062186 ms.
- The uninterruptible I/O sleep component is 129.720778 ms.
- One main-thread Binder transaction lasted 23.086615 ms, from thread `nting.benchmark` to `system_server` thread `binder:2536_1E`.
- The startup metric flags `Main Thread - Binder transactions blocked` as a warning.
- Trace import reports `power_rail_empty_packet: 1`; rail-level power data is unavailable in this trace.

## Precise SQL evidence

- Perfetto classifies startup id `20` as `cold`; interval `[538827114836682, 538827657457463)` ns, duration `542.620781 ms`.
- Startup milestones from `android_startup`: post-fork `54.774844 ms`, `ActivityThreadMain` `93.623958 ms`, and `bindApplication` `137.657500 ms` after intent receipt. Recorded work includes `ActivityThreadMain` `43.960000 ms`, `bindApplication` `42.132396 ms`, activity start `19.652032 ms`, activity resume `22.333281 ms`, resource loading `7.529583 ms`, and dex open `11.281615 ms`. These metric fields overlap and are not additive.
- Longest main-thread slices before first frame: `Choreographer#doFrame` `309.376458 ms`, `measure` `190.963698 ms`, `Compose:recompose` `48.931927 ms`, and `Decoding 1080x2400 bitmap` `46.258542 ms`.
- Slice id `32901` (`measure`) independently overlaps Running `163.764792 ms`, I/O D-state `27.082291 ms`, and Runnable `0.116615 ms`.
- Slice id `47186` (`Compose:recompose`) independently overlaps Running `40.023230 ms`, I/O D-state `8.888020 ms`, and Runnable `0.020677 ms`.
- Slice id `47320` (1080x2400 decode) independently overlaps Running `37.349845 ms`, I/O D-state `8.888020 ms`, and Runnable `0.020677 ms`.
- Corrected CPU-frequency `SPAN_JOIN` uses clipped, non-overlapping main-thread Running intervals with columns `ts/dur/cpu` only. The main thread ran `260.018122 ms` on CPU 7 at `3,052,800 kHz` and `2.493698 ms` on CPU 3 at weighted `2,956,800 kHz`; CPU starvation or low frequency is not the startup bottleneck.
- The longest main-thread I/O D-state is `[538827170967047, 538827206821265)` ns, `35.854218 ms`. `thread_state.blocked_function` and `thread_state.waker_utid` are null. Raw ftrace shows `sched_waking` at the exact end from `kworker/7:7H` (tid `2405`) and `sched_blocked_reason` with `io_wait=1`, but its `caller=8` value is not symbolized; the exact kernel function cannot be established from this trace.
- The longest main-thread Binder call is `[538827228882463, 538827251969078)` ns, `23.086615 ms`, to system_server tid `7742`. The server executes `AIDL::java::IActivityManager::attachApplicationExt::server` for `22.947604 ms`; the startup metric warning is therefore confirmed.
- No app GC slice is present before or during the measured startup. Bitmap memory rises from `7.479610 MiB` to `20.367306 MiB`; the process-memory metric reports a maximum RSS of `221.938 MiB`.
- The earlier query that fed overlapping nested slices sharing one UTID into `SPAN_JOIN` is invalid and is excluded from all findings; every slice-state result above was queried independently.
