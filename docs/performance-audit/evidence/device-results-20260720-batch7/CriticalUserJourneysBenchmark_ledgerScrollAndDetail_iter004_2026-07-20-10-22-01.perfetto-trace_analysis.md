# Verified trace evidence

- Target process `com.autoaccounting.benchmark`: upid 12; main-thread utid 12.
- Longest main-frame slice: `Choreographer#doFrame 148134206`, ts 647635949924833 ns, dur 329.926041 ms.
- The frame contains `AndroidOwner:measureAndLayout` 188.987031 ms, `Recomposer:recompose` 82.785677 ms, and `Compose:recompose` 58.369323 ms.
- Main-thread state over the frame interval: Running 326.019011 ms, S 3.683280 ms, R 0.223750 ms.
- Trace-wide JIT slices: 140, 179.974790 ms; GC slices: 5, 476.436042 ms.
- Trace import health: `power_rail_empty_packet` = 1.
