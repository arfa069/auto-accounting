# Chain of evidence

- Trace: `CriticalUserJourneysBenchmark_ledgerScrollAndDetail_iter002_2026-07-19-05-26-04.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`
- Journey: ledger scroll and detail, iteration 2 (reported median frame-count iteration)

- Frame Timeline: 92 app frames, 3 missed app frames, 0 dropped frames; P50/P90/P95/P99 duration 4.901/9.031/19.024/49.315 ms.
- Longest app frame id 134551500: `[543479420642407,543479504210376)` ns, 83.568 ms.
- The frame overlaps RenderThread utid 7033 `shader_compile` for 38.279 ms, `cache_miss` for 37.358 ms, `driver_compile_shader` for 16.038 ms, and `driver_link_program` for 14.399 ms.
- No slice matching `*decode*bitmap*` exists in the target process in this trace.
