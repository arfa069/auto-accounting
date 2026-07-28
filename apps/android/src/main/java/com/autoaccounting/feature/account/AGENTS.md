# Android 账号功能指南

## 职责

本目录维护登录、注册、找回密码、本地模式、公开账号 UUID、昵称/头像 Profile、标识补绑/换绑、账号 Session 和账号注销 UI 状态。网络行为通过 `AccountRepository` 边界完成。

## 约束

- `SignedOut`、`LocalMode` 与 `SignedIn` 的转换必须显式；失败请求不得残留旧 token 或伪造登录成功。
- 密码、验证码和 token 不得写入日志、测试截图、持久化 UI 状态或错误文案。错误信息应稳定但不能暴露账号是否存在等敏感细节。
- 用户可见账号 ID 只能来自后端 `accountUuid`；显示时可脱敏、复制时使用完整 UUID，禁止从 Bearer Token 或数据库自增 ID 派生。
- 昵称、头像和标识换绑必须经真实 Repository 持久化，并在重新验证、进程重启和注销冷静期内保持一致；Profile 修改不得覆盖 `deletionState`。
- 相册与相机 URI 的读取、解码、压缩和临时文件清理必须离开主线程。头像上传前限制尺寸与格式，失败时在头像操作附近提供稳定反馈。
- 账号注销保留七天冷静期和取消路径。账号注销与本机账本删除是两个独立动作，不得互相隐式触发。
- 页面内返回与系统返回必须遵循同一层级：找回密码回到登录；登录、注册、本地模式说明和合规材料回到账号入口；从账户管理打开的账号入口回到账户管理。首次启动的账号入口不得伪造账户管理返回目标。
- `FakeAccountRepository` 只能作为替身或演示边界；接入真实流程时保留接口注入和失败测试。
- 修改请求/响应字段时同步检查 Backend 路由和共享契约。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"
```

至少覆盖校验失败、Repository 失败、Session 切换、公开 UUID 展示/复制、Profile 持久化、相机/相册结果、标识换绑、账号页面系统返回层级、注销申请及取消。
