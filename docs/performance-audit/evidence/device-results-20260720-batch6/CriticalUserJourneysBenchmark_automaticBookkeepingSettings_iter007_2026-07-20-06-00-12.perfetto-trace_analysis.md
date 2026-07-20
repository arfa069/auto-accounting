# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_automaticBookkeepingSettings_iter007_2026-07-20-06-00-12.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `71`, total JIT method compile duration `76.534000 ms`.
- SQL: maximum `Recomposer:recompose` `54.345000 ms`; maximum `measureAndLayout` `43.496000 ms`.
- SQL: background concurrent copying GC count `0`, total duration `0.000000 ms`.

- Longest main-thread target: `Recomposer:recompose`, `54.345 ms`; the complete interval was Running with no scheduler wait, I/O, or blocked function.
- Maximum `measureAndLayout` was `43.496 ms`; background bitmap decode slices were `67.420 ms` and `60.470 ms`.
- App CPU frequency overlap: weighted average `1584.1 MHz`, range `499.2–2265.6 MHz`, app CPU time `1039.459 ms`.
