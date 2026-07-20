# Ledger force-stop/background-restarted 01

- Trace: `ledger-force-stop-bg-restarted-01.perfetto-trace`
- Steps: start app, enter ledger, scroll twice, open one entry, return.
- Frame metric for app: 306 frames; 7 missed; 3 app-missed; 4 dropped; p95 7.09 ms; max 101.379 ms.
- Threshold SQL: 303 correlated app frames; 3 over 16.67 ms; 1 over 50 ms; 0 over 700 ms.
- Worst frame: id `127851601`, `499011996467184..499012097846184` ns, 101.379 ms, overrun 88.506 ms, UI time 99.367 ms.
- Worst-frame cause: main-thread Compose recomposition 74.622 ms, including full-screen 1080x2400 PNG decode 56.073 ms; traversal 20.219 ms.
- Worst-frame states: main Running 98.980 ms, D-state I/O 1.516 ms, Runnable 0.170 ms; RenderThread Running 24.646 ms and Sleeping 75.672 ms.
- Main ran mostly on CPU 6 (70.285 ms) and CPU 2 (27.397 ms), both sampled at 2323 MHz; this was CPU work, not scheduler starvation.
- Keystore: 5,909 transactions, 14,451.370 ms cumulative, max 132.235 ms.
- Memory/GC: RSS 108.87..266.17 MiB; 2 concurrent-copying GCs, 138.135 ms total, max 86.364 ms.
- Trace health: power rail packets were empty; no numeric energy result is valid.
