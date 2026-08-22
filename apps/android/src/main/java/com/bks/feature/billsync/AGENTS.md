# Android 通用自动记账指南

## 职责

本目录负责基于 `assists-base` 的通用无障碍窗口读取、交易候选解析和待确认队列交接。

- `BillSyncAccessibilityService` 只作为 Android 无障碍服务入口，直接继承 `AssistsService`；除非有明确的应用级生命周期副作用，不重复覆写基类已经实现的回调。
- 应用级采集协调器通过 `AssistsService.listeners` 接收事件，负责开关状态、窗口读取、重复抑制、解析和调用 Processor。
- Service 不创建 Room、Repository 或 Processor，不直接读写数据库，也不承担业务流程编排。
- `BillPageParser` 只做纯文本识别；`BillSyncCaptureProcessor` 只负责把已识别结果交给 Review Queue 持久化。

## 开发说明

- [assists-base API 概览](https://my.feishu.cn/wiki/DuKXw8yQIip0gVkyVeQcuuGanYe)
- [assists-base API 示例教程](https://my.feishu.cn/wiki/PpTswRPZciJUEIkSubHcWWo2nrS)

## 运行不变量

- Manifest 声明不等于用户已授权。持久化开关只表示用户意图；权限或服务断开只改变运行状态，不反写关闭。
- 采集协调器只被动读取事件对应窗口中可见、非密码、非可编辑的语义文字；排除 BKS 自身，不截图、不点击、不滚动、不启动其他应用。
- 采集协调器接收全部无障碍事件，并立即从 Assists `AllWindows` 读取事件对应窗口；不缓存或延迟读取节点。
- 不等待页面稳定，也不查询候选或历史记录。同一包名和窗口 ID 成功创建后，本次成功页停留期间只落库一次；同一窗口出现未识别页面后释放，允许下一笔交易再次识别。
- **采集**：广度优先遍历，最多 512 个节点、24 层、16KiB。不可见容器仍遍历子节点；读取 `text`、`contentDescription`、`hintText`、`paneTitle`、`tooltipText` 和 `stateDescription`。
- **准入**：必须有完成状态、货币金额、无冲突方向和交易上下文；取第一个有效金额。拒绝付款发起、密码、待支付、处理中、失败、取消及方向冲突页面。
- **输出**：候选固定为 `OTHER`、`ACCESSIBILITY_AUTO`、`NEEDS_REVIEW`，资金账户为空；确认后才入账。
- **隐私**：原始页面文字不落库、不写文件、不上传。仅在用户授权的 Debug 排错中，通过显式开启的 `BillSyncCapture` 临时写入 logcat；结束后关闭标签，未经用户明确要求不得清空日志。

## 排错顺序

用同一次复现定位断点：

- **无日志**：检查 Debug 标签、进程、Service 绑定和 Listener 注册。
- **有 `event`，无 `capture`**：检查开关状态或同步读取异常。
- **`selected` 错误**：检查 `AllWindows` 是否包含事件窗口；事件包与当前活动窗口不同是正常情况。
- **`collected` 缺字段**：用 AssistsX AIS Node Viewer 检查节点数、深度和字段；`des` 即 `contentDescription`。仓库只保留精简回归样例。
- **`recognized=false`**：确认采集文字完整后，再检查 `BillPageParser` 并补 JVM 测试。
- **`created=1`，界面无记录**：检查 Review Queue 持久化和 Compose 状态，不再修改窗口读取。

没有对应阶段的日志证据，不修改去重、采集上限、解析规则或 Pipeline。Parser 测试通过不代表真机采集成功。

## Debug 无障碍功能

### 1. 准备

```powershell
adb devices -l
adb -s <serial> install -r apps/android/build/outputs/apk/debug/android-debug.apk
adb -s <serial> shell dumpsys accessibility
```

- 服务必须同时出现在 Enabled services 和 Bound services：`com.bks.debug/com.bks.feature.billsync.BillSyncAccessibilityService`。
- Binding services 和 Crashed services 不得有异常；重装 APK 后重新检查。
- Xiaomi/MIUI 禁止使用 `uiautomator dump`，改用 `dumpsys accessibility`、`dumpsys window` 和专用 logcat。

### 2. 开启并读取专用日志

```powershell
adb -s <serial> shell setprop log.tag.BillSyncCapture DEBUG
# 复现：在目标完成页面停留至少 3～5 秒
adb -s <serial> logcat -d -v time -s BillSyncCapture:D '*:S'
```

日志阶段应按顺序出现：

1. `event`：收到窗口事件；包名为空是合法输入。
2. `capture enabled=true`：用户开关仍开启，开始立即读取。
3. `roots all=... selected=...`：Assists 返回的全部根和最终选择的事件窗口。
4. `collected` 与 `page n/m`：实际活动包、窗口、行数、字符数和完整采集文字。
5. `processed recognized=... created=...`：解析和待确认持久化结果。

**注意：页面日志含敏感交易文字。展示或保存前先过滤无关包并脱敏；不得提交 logcat、AssistsX JSON 或真机临时证据。**

### 3. 关闭诊断

```powershell
adb -s <serial> shell setprop log.tag.BillSyncCapture INFO
```

**注意：日志实现应长期保留在代码中；排错结束只关闭运行时标签，不再反复删除和重写日志代码。**

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.*"
.\gradlew.bat :apps:android:detekt :apps:android:assembleDebug
git diff --check
.\gradlew.bat --stop
```

测试至少覆盖：Service 注册壳、Listener 注册与注销、`typeAllMask`、事件窗口读取、窗口级成功页抑制、BKS 自身过滤、节点数量/深度/文字上限和连接状态；Processor 测试证明持久化层不执行候选或历史去重。真机结论必须附 `event → capture → roots → collected → processed` 的同一次复现证据。
