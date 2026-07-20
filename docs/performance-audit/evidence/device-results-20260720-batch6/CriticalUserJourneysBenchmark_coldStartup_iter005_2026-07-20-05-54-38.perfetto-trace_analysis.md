# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_coldStartup_iter005_2026-07-20-05-54-38.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `9`, total JIT method compile duration `4.159000 ms`.
- SQL: maximum `Recomposer:recompose` `2.294000 ms`; maximum `measureAndLayout` `1.694000 ms`.
- SQL: background concurrent copying GC count `0`, total duration `0.000000 ms`.

- `android_startup`: cold startup, `verify/cmdline`; time to initial display `495.019 ms`, intent-to-first-frame `413.009 ms`.
- Main-thread startup states: Running `223.892 ms`, Sleeping `96.165 ms`, D-state I/O `52.423 ms`, D-state non-I/O `2.031 ms`, Runnable `7.694 ms`.
- Startup metric JIT-thread on-CPU time: `0.195 ms`; dex open `23.708 ms`; Choreographer slices `503.000 ms`.
