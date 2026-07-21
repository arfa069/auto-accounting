# Chain of evidence
- `android_startup` reports startup `148`, package `com.autoaccounting.benchmark`, type `cold`, and intent-to-first-frame duration `338.444322 ms`.
- Main thread is `utid=1097`, `tid=28260`. `android_startup` reports pre-first-frame Running `75.415469 ms`, Runnable `6.409582 ms`, uninterruptible I/O sleep `100.048437 ms`, and interruptible sleep `111.125470 ms`.
- Main-thread `D` states include `39.840000 ms`, `20.210469 ms`, and `13.244115 ms`, all with `io_wait=1`; this trace records neither `blocked_function` nor `waker_utid` for them.
- Startup main-thread slices include `bindApplication 49.279428 ms`, `activityResume 32.295261 ms`, and a `17.009219 ms` `relayoutWindow`; the `182.118177 ms` Choreographer slice begins immediately before first-frame completion and extends beyond the startup interval.
- Main-thread Binder transactions longer than 5 ms peak at `18.251406 ms` to `system_server`; no Binder transaction is longer than the recorded I/O wait.
- Trace health reports one `power_rail_empty_packet` import error.
