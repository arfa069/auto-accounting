# 完成：接通 Android 真实账户核心闭环

## 目标

将 Android 登录、注册、找回密码、重启恢复、退出登录和七天注销冷静期接入真实后端，同时收紧服务端 Session、短信验证码和注销身份边界。

## 范围

- 共享账号成功响应、Session/注销状态和稳定错误码契约。
- 受保护接口统一使用 Bearer token，身份不再由表单手机号决定。
- 验证码 HMAC、Session token SHA-256 哈希入库；安全迁移清除存量临时凭据。
- Android 构建时后端地址、Debug 明文边界、`HttpURLConnection` 客户端和随机安装 UUID。
- Android Keystore AES-GCM Session、重启后台验证、离线保留和明确 401 降级。
- 服务端先清理 AI 日志与云配置、再最终删除账号；失败保留并可幂等重试。
- 账户管理页显示脱敏手机号、连接状态、服务端注销截止时间、真实退出和注销确认。

## 非目标

- 不展示注册设备列表、头像或昵称。
- 不接入云账本同步、刷新 token、固定 token 过期或多设备 Session 管理。
- 不修改资金账户、Room 账本 schema、备份格式或 Android 云 AI 客户端。

## 目标文件或模块

- `shared/api/src/main/kotlin/com/autoaccounting/api/AccountContracts.kt`
- `services/backend/src/main/kotlin/com/autoaccounting/backend/account`
- `services/backend/src/main/kotlin/com/autoaccounting/backend/AccountDeletionJob.kt`
- `apps/android/src/main/java/com/autoaccounting/feature/account`
- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `docs/adr/0056-secure-and-persist-real-account-sessions.md`

## 验收标准

- [x] 登录、注册、找回、Session 校验、当前 Session 退出和注销接口使用共享稳定契约。
- [x] 云配置、AI 与注销路由只接受 Bearer 身份，表单手机号/token 不能冒用其他账号。
- [x] 数据库不保存原始验证码或 token；迁移清除旧临时凭据，哈希 Session 可跨服务实例验证。
- [x] Android 只在安全持久化成功后登录；网络失败保留离线 Session，明确 401 才切换本地模式。
- [x] 退出失败不清除本机 Session；账号注销和本机账本删除保持独立。
- [x] 注销清理失败保留账号，后续任务可重试并幂等完成。
- [x] 账户管理页提供加载、防重复、错误、二次确认和服务端截止时间反馈。

## 验收测试

- `./gradlew.bat --no-daemon :shared:api:test`
- `./gradlew.bat --no-daemon :services:backend:test --tests "com.autoaccounting.backend.account.*" --tests "com.autoaccounting.backend.config.*" --tests "com.autoaccounting.backend.ai.*"`
- `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*" --tests "com.autoaccounting.feature.profile.ProfileScreenTest"`
- `./gradlew.bat --no-daemon :services:backend:test`
- `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `./gradlew.bat --no-daemon build`

## 手工验证

1. 配置真实 HTTPS 后端、PostgreSQL 和短信 Provider，在 Debug/Release 包分别完成注册、登录、重启恢复和找回密码。
2. 断网重启确认本机账本和离线登录保留；恢复网络后重新验证连接。
3. 退出登录失败时确认仍保持登录；成功后确认本机账本不变且重启保持本地模式。
4. 申请注销并核对服务端截止时间、七天说明与本机账本不受影响；随后取消注销。

## 回滚或安全说明

- 迁移会主动让存量验证码和 Session 失效，需要重新登录；不得恢复明文兼容列。
- 未配置后端 URL、生产数据库和短信 Provider 时，只能执行注入式自动化协议验证，不能宣称真实短信或真机端到端通过。
- 回滚 Android 联网入口不得回滚数据库哈希迁移或 Bearer 身份边界。

## 验证记录

- `2026-07-18`：共享 API 完整构建、后端全量测试、`SecretScannerTest`、Android 398 项全量单测、Debug/Release APK 构建均通过；本机 Release APK 通过单签名 v2 校验。
- `2026-07-18`：根 `build` 执行到 `:apps:android:lintDebug` 时，被未改动的 `BillSyncAccessibilityService.kt` 中 5 项既有 API 30 `NewApi` Lint 错误阻断；账户相关编译、测试和打包任务已单独通过，排除该既有 Lint 任务后的根构建通过。
- `2026-07-18`：真实短信、生产数据库与真机端到端尚未执行。

## 依赖

- Issue 9：后端账号、短信与注册设备持久化。
- Issue 11：账号注销与定时云端清理。
- Issue 18：我的页与账户管理导航。
