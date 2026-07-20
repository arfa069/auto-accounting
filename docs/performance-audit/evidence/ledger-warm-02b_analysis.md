# Ledger warm 02b

- Trace: `ledger-warm-02b.perfetto-trace`
- Steps: warm start, enter ledger, scroll twice, open one entry, return.
- Frame metric for app: 221 frames; 4 missed; 2 app-missed; 1 SurfaceFlinger-missed; 1 dropped; p95 6.87 ms; max 91.29 ms.
- Threshold SQL: 224 correlated app frames; 3 over 16.67 ms; 2 over 50 ms; 0 over 700 ms.
- Keystore activity: 5 transactions, 16.488 ms cumulative, max 13.037 ms.
- Trace health: power rail packets were empty; no numeric energy result is valid.
