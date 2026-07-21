# Chain of evidence
- `android_startup` reports startup `137`, package `com.autoaccounting.benchmark`, type `cold`, and intent-to-first-frame duration `314.718073 ms`.
- The app odex compilation filter is `speed-profile`. `android_startup` reports pre-first-frame Running `63.826877 ms`, Runnable `3.243390 ms`, uninterruptible I/O sleep `26.761818 ms`, and interruptible sleep `164.354738 ms`.
- The largest main-thread `D` state is `3.010104 ms`; all reported `D` states have `io_wait=1` and no recorded `blocked_function` or `waker_utid`.
- Main-thread startup slices include `activityResume 104.996718 ms`, `clientTransactionExecuted 120.585937 ms`, `bindApplication 50.139896 ms`, and `OpenDexFilesFromOat 12.735156 ms`.
- `android_startup` marks `installd running during launch`; non-main Binder transactions from `skip_verifyclas` and `SmartArtManager` to `system_server` are `21.314532 ms` and `20.256823 ms`, while the longest app-main Binder is `6.605416 ms`.
- Trace health reports one `power_rail_empty_packet` import error.
