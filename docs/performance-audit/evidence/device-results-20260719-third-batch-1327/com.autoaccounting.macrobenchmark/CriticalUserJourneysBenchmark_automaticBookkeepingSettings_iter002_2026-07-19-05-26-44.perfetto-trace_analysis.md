# Chain of evidence

- Trace: `CriticalUserJourneysBenchmark_automaticBookkeepingSettings_iter002_2026-07-19-05-26-44.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`
- Journey: Profile to automatic bookkeeping, iteration 2 (reported median frame-count iteration)

- Frame Timeline: 79 app frames, 3 missed app frames, 2 dropped frames; app P50/P90/P95/P99 duration 7.216/8.980/14.825/81.615 ms.
- Longest app frame id 134613408: `[543518786249840,543518872911299)` ns, 86.661 ms. UI utid 10 was Running 85.503 ms, Sleeping 1.050 ms, Runnable 0.109 ms, with no D-state.
- Within frame 134613408: `Recomposer:recompose` 47.261 ms, `Compose:recompose` 32.201 ms, traversal 35.103 ms, and `AndroidOwner:measureAndLayout` 27.802 ms.
- Six 256x256 `ImageDecoder_nDecodeBitmap` slices ran on the UI thread in that frame; total 11.670 ms and maximum 2.390 ms.
- The first 1080x2400 wallpaper `decodeBitmap` began at 543518870647288 ns on `DefaultDispatch` utid 7260 and lasted 66.932 ms. Its thread states were Running 66.767 ms, Runnable-preempted 0.128 ms, and Runnable 0.037 ms.
- Second long app frame id 134613766: `[543519479451350,543519559473069)` ns, 80.022 ms. It contains traversal 43.522 ms, measure/layout 33.305 ms, recomposition 30.720 ms, and no wallpaper decode.
- The second wallpaper `decodeBitmap` began at 543519566832496 ns on `DefaultDispatch` utid 7260 and lasted 53.864 ms, after frame 134613766 ended.
- Bitmap Memory samples: 20.617 MiB initially, 31.755 MiB after the first wallpaper, 41.643 MiB after the second wallpaper; no GC slice was recorded.
- Process memory: RSS 198.582-232.496 MiB, maximum anonymous RSS 94.563 MiB, maximum swap 62.363 MiB.
- The UI thread ran the longest frame entirely on CPU 4 at 2,035,200 kHz.
- No target-process D-state was recorded. Global longest target-process slices were the two UI frames and the two background wallpaper decodes; system-wide D-state leaders were display/health/kernel threads, not the target app.
