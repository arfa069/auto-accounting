# 拆分分类规则与智能分类同意链

## 目标

让本地分类规则保持始终可用，并让云端智能分类、最小字段和增强上下文具有可理解、可撤销的同意链。

## 范围

- 将本地分类规则管理和云端智能分类设置迁移到“分类规则”二级页。
- 本地模式可完整管理本地规则；云端智能分类显示为登录后可用，不提供启用开关。
- 已登录用户默认关闭智能分类；开启后默认只使用最小字段。
- 增强上下文仅在智能分类已同意时可单独开启；关闭智能分类时同时撤销增强上下文，重新开启后回到最小字段默认值。

## 非目标

- 不改变规则匹配优先于 AI 的顺序。
- 不让规则或 AI 直接写入账本；它们仍只提供分类建议。

## 目标文件或模块

- `apps/android/src/main/java/com/autoaccounting/feature/categorization`
- `apps/android/src/main/java/com/autoaccounting/data/local`
- `apps/android/src/test/java/com/autoaccounting/feature/categorization`

## 验收标准

- [ ] 本地模式可查看、创建、编辑和删除本地分类规则，且不会显示可操作的云端 AI 开关。
- [ ] 智能分类关闭时增强上下文已撤销且不可操作；再次开启智能分类时仅最小字段生效。
- [ ] 已登录且未同意 AI 时，页面清楚解释这是可选能力。
- [ ] 设置重启后仍保持同意链的正确状态。

## 验收测试

- [ ] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.categorization.*"`
- [ ] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest`

## 手工验证

1. 在本地模式检查规则管理和登录提示。
2. 使用测试账号开启 AI、开启增强上下文、关闭 AI、重新开启 AI，确认状态按同意链恢复。

## 回滚或安全说明

- 关闭 AI 后不得继续发送增强上下文字段。
- 不在界面、日志或测试证据中展示真实交易文本。

## 验证记录

- 规划阶段：尚未实现或执行验收。

## 依赖

- Issue 18：重构“我的”总览与账户管理。
