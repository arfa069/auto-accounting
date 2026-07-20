# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_ledgerScrollAndDetail_iter009_2026-07-20-05-57-39.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `131`, total JIT method compile duration `166.745000 ms`.
- SQL: maximum `Recomposer:recompose` `60.540000 ms`; maximum `measureAndLayout` `113.504000 ms`.
- SQL: background concurrent copying GC count `1`, total duration `218.803000 ms`.

- Longest main-thread target: `AndroidOwner:measureAndLayout`, `113.504 ms`; state overlap was Running `111.412 ms`, Sleeping `1.970 ms`, Runnable `0.122 ms`, with no D-state or blocked function.
- Longest recompose was `60.540 ms`; a background concurrent copying GC lasted `218.803 ms`; a DefaultExecutor ClassLinker lock-contention slice lasted `122.463 ms`.
- App CPU frequency overlap: weighted average `1332.0 MHz`, range `499.2–2035.2 MHz`, app CPU time `1823.754 ms`.
