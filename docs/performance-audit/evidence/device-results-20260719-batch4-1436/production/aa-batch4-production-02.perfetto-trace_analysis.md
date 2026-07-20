# Chain of Evidence

- Trace file: `aa-batch4-production-02.perfetto-trace`.
- Trace bounds: `577963951033471..577978859965080` ns; duration `14.908932 s`.
- Package/process rows: `com.autoaccounting` upid `920`, pid `24576`, start `577966242365918` ns; prior process row upid `878`, pid `23915`.
- ADB sequence: pid before trace `23915`; after `am force-stop com.autoaccounting` during the trace `24576`; pid remained `24576` 4 s later and at trace end.
- Binder module query: app upid `920` issued `4766` synchronous Binder transactions to `/system/bin/keystore2`; cumulative client wall time `12108.493266 ms`; maximum single client duration `15.307240 ms`; first transaction `577966338608210` ns; last transaction ended `577978859519924` ns.
- Binder module query: all Binder transactions involving upid `920`: `4863`; cumulative client duration `12166.485146 ms`; maximum `15.788698 ms`.
- Thread-state query for upid `920`: main thread `.autoaccounting` tid `24576` had `Running 72.049010 ms`, `R+ 15.485468 ms`, `R 5.906566 ms`, `D 0.237186 ms`, `S 12523.920932 ms`; `DefaultDispatch` tid `24608` had `Running 515.834966 ms`, `R 49.254034 ms`, `R+ 10.075625 ms`, `D 0.384062 ms`, `S 11968.712871 ms`.
- Slice query: `DefaultDispatch` contained `4768` `binder transaction` slices totaling `12109.505245 ms`, max `15.307240 ms`; main thread contained one `bindApplication` slice of `30.764115 ms` and one `serviceCreate` slice for `PaymentNotificationListenerService` of `1.763177 ms`.
- Slice query: startup path included class loading for `DiagnosticEncryptedStore`/`AndroidDiagnosticLogRepository`, then Keystore Binder slices beginning at `577966337854356` ns; `DiagnosticEncryptedStore.readAll()` and `DiagnosticEventCodec.decode` methods were JIT-compiled during this process start.
- Garbage-collection module query for upid `920`: `6` GC events, total wall `304.907916 ms`, total CPU `221.195156 ms`, maximum `75.188489 ms`.
- Memory module query for upid `920`: RSS rose from `82.179688 MiB` to a peak of `123.515625 MiB` during the captured restart window; anon RSS peaked at `37.058594 MiB`; swap was present in the samples.
- Trace health: one `power_rail_empty_packet` import error; no power-rail energy value was available from this trace.
- Global longest-slice query: the app-specific longest slices were GC `75.188489 ms` and `bindApplication 30.764115 ms`; unrelated system slices included `xiaomi_touch_temp_thread` D-state intervals of about `5.1 s` each.
- Global D-state query: the app main thread had only `0.237186 ms` D-state; the long D-state intervals belonged to Xiaomi/kernel/system components and had no `blocked_function` symbol.
- Independent patched benchmark traces in sibling directory showed no Keystore Binder transactions in all 15 traces and no Android GC events; those traces ran the benchmark APK, not the installed production package.
