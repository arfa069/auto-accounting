# Android 性能审计归档

本目录保存 2026-07-19 至 2026-07-20 Android 系统性能审计的可版本化材料。

- [完整审计报告](./performance-audit.md)
- [测试源码与配置索引](./TEST-SOURCES.md)
- [`evidence/`](./evidence/)：177 份 Markdown（Perfetto SQL/Trace 分析 scratchpad 与证据说明）、12 份 Macrobenchmark JSON 和 1 份 Perfetto 配置，共 190 个脱敏证据文件。

## 原始二进制证据

截至 2026-07-20 批次 7 验证时，原始目录共有 593 个文件、2,866,498,273 bytes。其中 322 个 Perfetto Trace、APK 和截图共 2,856,154,511 bytes，未复制进仓库，原因如下：

- Perfetto Trace 和截图可能包含真实设备、通知或用户界面数据，不符合 `docs/AGENTS.md` 的脱敏要求；
- APK、Trace 和截图体积过大，不适合作为普通 Git 文档提交；
- 报告中的结论均有对应的脱敏 scratchpad 或 Benchmark JSON，可在需要时从本机原始目录重新核验。

本目录不会复制生产凭据、签名材料、完整交易样本或未脱敏日志。测试源码继续保留在所属 Android/Benchmark 模块，避免文档副本与可执行源码产生漂移。
