# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_coldStartupBaselineProfile_iter005_2026-07-20-05-53-50.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `7`, total JIT method compile duration `1.615000 ms`.
- SQL: maximum `Recomposer:recompose` `1.335000 ms`; maximum `measureAndLayout` `0.698000 ms`.
- SQL: background concurrent copying GC count `0`, total duration `0.000000 ms`.

- `android_startup`: cold startup, `speed-profile/cmdline`; time to initial display `484.377 ms`, intent-to-first-frame `423.479 ms`.
- Main-thread startup states: Running `205.465 ms`, Sleeping `104.683 ms`, D-state I/O `68.993 ms`, D-state non-I/O `3.757 ms`, Runnable `3.834 ms`.
- Startup metric JIT-thread on-CPU time: `0.186 ms`; dex open `25.302 ms`; Choreographer slices `449.372 ms`.
