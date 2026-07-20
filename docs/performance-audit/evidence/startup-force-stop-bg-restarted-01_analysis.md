# Startup force-stop/background-restarted 01

- Trace: `startup-force-stop-bg-restarted-01.perfetto-trace`
- High-level metric: Android startup classified this as warm because the enabled notification-listener service restarted the process after `am force-stop`; first frame 324.684 ms.
- Startup window: `498870408066300..498870732750519` ns.
- Main-thread states in that window: Running 201.627 ms; Sleeping 55.739 ms; Runnable 5.931 ms; D-state I/O 7.697 ms.
- Main-thread critical slices: `Choreographer#doFrame` 220.125 ms; `measure` 161.048 ms; full-screen 1080x2400 PNG decode 38.410 ms.
- Keystore activity across trace: 5,689 app-to-keystore2 Binder transactions, 14,401.619 ms cumulative client wall time, max 96.830 ms; client threads were DefaultDispatch, not main.
- Memory/GC: app RSS 109.01..244.51 MiB; 2 concurrent-copying GCs, 114.984 ms total, max 77.156 ms.
- Trace health: power rail packets were empty; no numeric energy result is valid.
