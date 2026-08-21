# 默认手动账单同步与可选连续监控

> 已于 2026-08-21 被 [ADR 0063](./0063-replace-platform-capture-with-assists-generic-recognition.md) 取代；本文仅保留历史决策。

应用默认使用由用户主动发起的手动账单同步功能。连续监控（Continuous Monitoring）作为可选的高级模式放置在高级设置中，且仅在用户显式开启授权后运行，避免因后台持续观察带来不必要的系统资源消耗与合规风险。
