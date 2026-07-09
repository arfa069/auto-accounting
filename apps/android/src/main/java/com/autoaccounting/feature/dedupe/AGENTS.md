# Android 去重指南

## 职责

`DedupeEngine` 对通知采集、账单同步和既有待确认项进行确定性比较，返回合并、疑似重复或无匹配结论。

## 约束

- 只有金额、时间、来源和标题等证据达到高置信度时才能自动合并；弱标题相似只能进入人工复核。
- 金额不同或时间超窗时优先避免误合并。调整阈值必须说明对误报和漏报的影响。
- 合并必须保留双方证据与来源，不能静默删除信息，也不能直接生成已确认账本记录。
- 引擎保持纯 Kotlin、无 Android UI 或数据库依赖；相同输入必须得到相同输出。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.dedupe.DedupeEngineTest"
```

阈值变化至少覆盖精确跨来源匹配、弱匹配、金额不同、时间边界和空字段。
