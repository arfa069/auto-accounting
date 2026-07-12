# 重构“我的”总览与账户管理

## 目标

将“我的”从堆叠所有设置的长页改为可扫描的总览，并交付账户管理这一条完整用户路径：用户可识别当前本地模式或登录状态，并完成登录/注册入口跳转、退出登录或账号注销。

## 范围

- 顶部提供可点击的账号状态卡：本地模式说明账本仅保存在本机；已登录状态显示脱敏手机号和账号状态。
- 总览按固定顺序显示仅含状态摘要和进入箭头的五项入口：账户管理、自动记账、分类规则、数据与备份、合规与隐私。
- 二级页使用带标题和返回动作的完整页面；底部四栏导航保持可用，切换栏目不保留二级页栈。
- 账户管理在本地模式提供登录/注册入口；登录后提供退出登录，并将账号注销置于独立危险区。
- 退出登录只清除当前设备会话，不删除本机账本、备份或云端账号。

## 非目标

- 不新增头像、昵称、签名或其他个人资料模型。
- 不新增注册设备列表、移除设备或占位入口。
- 不迁移其他四个二级页的具体设置内容。

## 目标文件或模块

- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `apps/android/src/main/java/com/autoaccounting/feature/account`
- `apps/android/src/test/java/com/autoaccounting/feature/account`

## 验收标准

- [ ] “我的”总览显示账号状态卡和五项固定顺序入口，顶层不出现开关、系统权限按钮或备份密码输入。
- [ ] 本地模式与已登录状态均可从状态卡进入账户管理，且显示与各自状态一致的操作。
- [ ] 退出登录后本机账本仍存在，重新启动应用后保持本地模式；账号注销与本机数据删除仍为不同操作。
- [ ] 从任一二级页返回只回到“我的”总览；切换底部栏目后不保留该二级页栈。

## 验收测试

- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.profile.ProfileScreenTest"`
- [ ] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"`
- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [x] `./gradlew.bat --no-daemon :apps:android:assembleDebug`

## 手工验证

1. 分别以本地模式和已登录状态进入“我的”，确认状态卡、入口顺序和账户管理操作正确。
2. 从账户管理进入并返回，切换到底部其他栏目后再回到“我的”，确认导航状态符合预期。
3. 在受控测试账号上退出登录，确认本机账本未被删除。

## 回滚或安全说明

- 退出登录不得复用账号注销逻辑，不得删除本地账本或备份。
- 不在日志中输出手机号、令牌或账本内容。

## 验证记录

- 规划阶段：尚未实现或执行验收。
- `2026-07-12`：新增 `ProfileScreenTest`，覆盖账号状态卡、五项总览入口和已登录账户管理中的退出登录/账号注销边界；专项测试、Android 全量单测与 Debug APK 构建均通过。手工设备验证尚未执行。

## 依赖

- 无：可立即开始。
