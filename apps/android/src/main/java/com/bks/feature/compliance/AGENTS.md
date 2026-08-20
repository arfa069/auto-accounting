# Android 合规材料指南

## 职责

本目录把隐私政策、个人信息清单、第三方服务、权限说明和商店审核说明呈现在应用内，并检查构建依赖是否遗漏披露。

## 约束

- 合规材料必须与 `docs/COMPLIANCE.md`、Manifest、Gradle 依赖和实际数据流一致，不能承诺代码尚未实现的保护。
- 新增权限、SDK、网络服务、采集字段、AI payload、留存或删除行为时，同步更新材料和测试。
- 权限用途文案应说明触发条件、数据范围和关闭方式，不用模糊的“一键优化”等扩大性描述。
- 扫描器只报告潜在遗漏，不在生产日志中输出凭据或完整敏感内容。
- 敏感诊断日志是用户主动开启、设备内加密且本地受控的明确例外；材料必须同时说明 Release 默认关闭、10 MB 上限、清空/导出行为、截图不留存、聊天和无关页面不记录正文及认证秘密始终脱敏。
- 合规文案变更不替代法律审查；不把草案表述为已获监管批准。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.compliance.*"
```

同时复核 `AndroidManifest.xml`、`apps/android/build.gradle.kts` 和 `docs/COMPLIANCE.md`。
