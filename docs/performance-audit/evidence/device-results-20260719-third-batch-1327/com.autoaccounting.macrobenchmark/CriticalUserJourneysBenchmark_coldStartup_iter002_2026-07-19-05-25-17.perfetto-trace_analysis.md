# Chain of evidence

- Trace: `CriticalUserJourneysBenchmark_coldStartup_iter002_2026-07-19-05-25-17.perfetto-trace`
- Target package: `com.autoaccounting.benchmark`
- Journey: cold startup, iteration 2 (reported median TTID iteration)

- Macrobenchmark TTID for the five-iteration run: min 438.468 ms, median 455.995 ms, max 525.094 ms.
- `android_startup`: startup id 24, cold, intent received at 543431469901019 ns, first frame at 543431929644092 ns, intent-to-first-frame 459.743 ms.
- Startup main-thread states: Running 236.780 ms, Runnable 6.065 ms, uninterruptible sleep 101.091 ms (I/O 100.095 ms), interruptible sleep 76.157 ms.
- Largest main-thread `ImageDecoder_nDecodeBitmap` slice: 3.797 ms. The 1080x2400 wallpaper `decodeBitmap` slice ran on `DefaultDispatch` utid 12534 for 54.826 ms.
