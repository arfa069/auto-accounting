# Chain of Evidence

- Trace: `CriticalUserJourneysBenchmark_automaticBookkeepingSettingsBaselineProfile_iter006_2026-07-20-05-55-44.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`

- SQL: JIT method compile count `1`, total JIT method compile duration `0.744000 ms`.
- SQL: maximum `Recomposer:recompose` `11.389000 ms`; maximum `measureAndLayout` `17.968000 ms`.
- SQL: background concurrent copying GC count `0`, total duration `0.000000 ms`.

- Longest main-thread target: `AndroidOwner:measureAndLayout`, `17.968 ms`; the complete interval was Running with no scheduler wait, I/O, or blocked function.
- Maximum recompose was `11.389 ms`; background bitmap decode slices were `60.379 ms` and `55.797 ms`.
- A remaining RenderThread `CircleOp → shader_compile` lasted `25.880 ms`; this is independent of ART Baseline Profile compilation.
- App CPU frequency overlap: weighted average `1503.6 MHz`, range `499.2–2265.6 MHz`, app CPU time `662.931 ms`.
