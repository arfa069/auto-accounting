# Ledger hot 03b

- Trace: `ledger-hot-03b.perfetto-trace`
- Steps: hot start, enter ledger, scroll twice, open one entry, return.
- Frame metric for app: 201 frames; 3 missed; 3 app-missed; 1 SurfaceFlinger-missed; 0 dropped; p95 8.42 ms; max 38.404 ms.
- Threshold SQL: 218 correlated app frames; 3 over 16.67 ms; 0 over 50 ms; 0 over 700 ms.
- Keystore activity: 0 transactions.
- Trace health: power rail packets were empty; no numeric energy result is valid.
