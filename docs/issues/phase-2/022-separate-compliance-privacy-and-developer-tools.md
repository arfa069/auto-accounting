# 拆分合规与隐私并隔离开发者工具

## 目标

让所有用户（包括本地模式）能分别查阅合规材料，同时将内测日志、设备矩阵和质量指标从 Release 用户界面移出。

## 范围

- “合规与隐私”二级页提供隐私政策、个人信息收集清单、第三方服务清单和权限说明四个独立入口。
- 每项打开各自完整内容页，并可在本地模式使用。
- 内测日志、设备矩阵、权限留存和质量指标仅进入 Debug 构建的开发者工具；Release 构建和“我的”总览不显示入口。

## 非目标

- 不改写既有合规材料的法律内容或扩大个人信息收集范围。
- 不通过隐藏手势在 Release 中保留开发者工具入口。

## 目标文件或模块

- `apps/android/src/main/java/com/autoaccounting/feature/compliance`
- `apps/android/src/main/java/com/autoaccounting/feature/beta`
- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `apps/android/src/test/java/com/autoaccounting/feature`

## 验收标准

- [x] 合规与隐私页显示四个独立、可进入的材料入口，并在本地模式可用。
- [x] Release 变体和“我的”总览不显示开发者工具、内测指标、日志或设备矩阵。
- [x] Debug 变体可进入开发者工具，且不需要隐藏手势。
- [x] 合规入口不把商店审核说明当作普通用户内容展示。

## 验收测试

- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.compliance.*"`
- [x] `./gradlew.bat --no-daemon :apps:android:assembleDebug :apps:android:assembleRelease`

## 手工验证

1. 以本地模式打开四项合规材料，确认每项内容独立可读。
2. 分别安装或检查 Debug 与 Release 构建，确认开发者工具只在 Debug 可见。

## 回滚或安全说明

- 开发者工具不得显示真实交易、手机号、令牌或未脱敏日志。
- Release 校验失败时不得以恢复普通用户入口替代修复构建变体控制。

## 验证记录

- 2026-07-13：合规专项、导航回归与完整 Android 单元测试通过；Debug 和 Release 变体均构建通过。
- 2026-07-13：自动化覆盖四个独立材料入口、商店审核说明隔离，以及 Debug/Release 开发者工具可见性。
- 2026-07-13：使用本机已有 `release.jks` 和已恢复的本地签名环境构建 Release；`android-release.apk` 经 `apksigner verify --verbose` 校验为 v2 签名 APK，并通过 `adb install -r` 覆盖安装到 Xiaomi `24117RK2CC`（Android 16，`192.168.1.6:40793`）。
- 2026-07-13：Release 真机中“合规与隐私”页显示四个独立材料入口；隐私政策、个人信息收集清单、第三方服务清单和权限说明均可分别打开并通过系统返回回到合规列表，未显示开发者工具、设备矩阵或商店审核说明。临时以相同发布证书安装 Debug 构建后，开发者工具入口直接可见并可进入内测准备检查和设备矩阵；随后已恢复安装 v2 签名 Release。未查看或导出任何内测日志。

## 依赖

- Issue 18：重构“我的”总览与账户管理。
