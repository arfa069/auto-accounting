# 修复后生产包 Trace 证据链

- Trace：`aa-batch4-production-patched-20260720-01.perfetto-trace`。
- 时间范围：`614932735011631..614947500882511` ns；总时长 `14.765871 s`。
- 进程：强停前 PID `23406`；强停后暂时无进程；4 秒后 PID `23966` 恢复。新进程 upid `1042`，start_ts `614935188576005` ns。
- 安装状态：同签名 Release 覆盖安装成功；安装前后 `ceDataInode=1648470`、`deDataInode=1833109`；通知权限和 NotificationListener 授权保持。
- Keystore Binder：13 次同步事务，累计 client wall `38.840574 ms`，最大单次 `10.091250 ms`；旧生产基线为 4,766 次、`12,108.493266 ms`。
- 全部 app Binder：88 次，累计 client wall `86.763595 ms`，最大 `10.346354 ms`。
- GC：0 次；旧生产基线为 6 次、`304.907916 ms`。
- 历史读取：未发现 `readAll`、`readLatest`、历史 `decode` 或历史加载 slice；诊断切片为类加载、`recordNow` 和 `DiagnosticEventCodec`，与生命周期事件写入一致。
- 主线程：Running `92.437336 ms`，Runnable `15.192140 ms`，D-state `6.007868 ms`；`bindApplication` `62.638281 ms`；`PaymentNotificationListenerService.serviceCreate` `3.230521 ms`。
- 内存：RSS `39.914062..113.625000 MiB`，anon RSS 峰值 `27.609375 MiB`，file RSS 峰值 `84.171875 MiB`，swap 峰值 `77.359375 MiB`。
- Trace 健康：存在 1 个 `power_rail_empty_packet` 导入错误，未获得有效 power rail 能耗值。
