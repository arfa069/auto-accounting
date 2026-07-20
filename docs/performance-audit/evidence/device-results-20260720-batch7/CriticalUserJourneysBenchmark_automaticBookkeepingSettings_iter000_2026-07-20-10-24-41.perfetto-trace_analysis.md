# Verified trace evidence

- Target process `com.autoaccounting.benchmark`: main-thread utid 12.
- Longest main-frame slice: `Choreographer#doFrame 148318199`, ts 647796104623417 ns, dur 110.957865 ms.
- The trace includes `Recomposer:recompose` 50.744896 ms and `AndroidOwner:measureAndLayout` 49.861302 ms.
- Main-thread state over the longest frame: Running 110.593282 ms, S 0.301823 ms, R 0.062760 ms.
- Trace-wide JIT slices: 86, 113.958960 ms; GC slices: 2, 16.397864 ms.
- Trace import health: `power_rail_empty_packet` = 1.
