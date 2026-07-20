# Profile/automatic-bookkeeping force-stop/background-restarted 01

- Trace: `profile-auto-force-stop-bg-restarted-01.perfetto-trace`
- Steps: start app, open Profile, open Automatic Bookkeeping, scroll permission/background settings, return.
- Frame metric for app: 298 frames; 5 missed; 4 app-missed; 1 dropped; p95 8.65 ms; max 91.281 ms.
- Threshold SQL: 297 correlated app frames; 3 over 16.67 ms; 2 over 50 ms; 0 over 700 ms.
- Two big frames: id `127918466` at `499298442955877`, 91.281 ms; id `127919186` at `499299260663845`, 87.265 ms.
- Root cause: the two transitions decoded separate 1080x2400 PNGs on the main thread for 60.894 ms and 55.496 ms.
- Keystore: 5,891 transactions, 14,456.476 ms cumulative, max 74.311 ms.
- Memory/GC: RSS 110.84..273.87 MiB; 2 concurrent-copying GCs, 127.508 ms total, max 82.595 ms.
- Trace health: power rail packets were empty; no numeric energy result is valid.
