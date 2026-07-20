# Verified evidence

- Target package: `com.autoaccounting.benchmark`.
- Flow: ledger scroll followed by opening one entry.
- Validation set: batch 5 node-indication cooled run 3, iteration 1.
- Frame Timeline: 84 frames, 7 app misses, max 192.292 ms, P99 54.339 ms, 0 dropped.
- SQL: `CircleOp` shader compile count 0; max recompose 99.482 ms; max measure/layout 66.655 ms; max remaining shader compile 21.011 ms; GC count 0.
- Vsync periods: 120 Hz for 2489.365 ms and 60 Hz for 799.959 ms.
- The 99.482 ms recompose was entirely Running; app D-state totaled 0.310 ms with a 0.128 ms maximum. The longest app chain was main-thread recompose/applyChanges/measure-layout, not RenderThread `CircleOp`.
