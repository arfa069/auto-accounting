# Android 待确认队列指南

## 职责

本目录维护待确认、已忽略、确认候选、撤销事件及其 Room 映射，是采集进入账本前的核心状态边界。

## 状态不变量

- 确认只移动目标待确认项并生成对应账本候选；忽略项必须在保留期内可恢复。
- 撤销只恢复最近一次可撤销动作，且同标题记录也必须通过稳定 ID 区分。
- 编辑失败不得部分保存其他字段；金额始终以最小货币单位整数解析和保存。
- 高置信度重复项可以合并证据；弱匹配只能标记为待人工判断，不能自动删除候选。
- `ReviewQueueEntry` 与 Room Entity 的双向映射必须保留来源、置信度、证据、资金账户和建议分类。
- reducer 保持纯函数；持久化层负责把状态转换完整写入数据库，UI 不得自行模拟成功。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.review.*"
```

状态或映射变化必须同时运行 reducer、Persistence 和 Screen 测试。
