# Android 账号功能指南

## 职责

本目录维护登录、注册、找回密码、本地模式、账号 Session 和账号注销 UI 状态。网络行为通过 `AccountRepository` 边界完成。

## 约束

- `SignedOut`、`LocalMode` 与 `SignedIn` 的转换必须显式；失败请求不得残留旧 token 或伪造登录成功。
- 密码、验证码和 token 不得写入日志、测试截图、持久化 UI 状态或错误文案。错误信息应稳定但不能暴露账号是否存在等敏感细节。
- 账号注销保留七天冷静期和取消路径。账号注销与本机账本删除是两个独立动作，不得互相隐式触发。
- `FakeAccountRepository` 只能作为替身或演示边界；接入真实流程时保留接口注入和失败测试。
- 修改请求/响应字段时同步检查 Backend 路由和共享契约。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"
```

至少覆盖校验失败、Repository 失败、Session 切换、注销申请及取消。
