# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_ledgerScrollAndDetailBaselineProfile_iter004_2026-07-20-05-58-31.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `8`, total JIT method compile duration `9.562000 ms`.
- SQL: maximum `Recomposer:recompose` `26.441000 ms`; maximum `measureAndLayout` `20.342000 ms`.
- SQL: background concurrent copying GC count `1`, total duration `94.138000 ms`.

- Longest main-thread target: `Recomposer:recompose`, `26.441 ms`; the complete interval was Running with no scheduler wait, I/O, or blocked function.
- Maximum `measureAndLayout` was `20.342 ms`; background concurrent copying GC lasted `94.138 ms`.
- App CPU frequency overlap: weighted average `1201.7 MHz`, range `499.2–2035.2 MHz`, app CPU time `1051.169 ms`.
