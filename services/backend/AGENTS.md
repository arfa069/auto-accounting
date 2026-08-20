# Backend 模块指南

## 作用域与结构

本文件适用于 `services/backend/`。通用规则继承仓库根目录 `AGENTS.md`。

- `account/`、`ai/`、`config/`、`sync/` 分别承载账号、AI 分类、云配置和账户级账本同步能力。
- `*Routes.kt` 负责 HTTP 边界与输入输出；`*Service.kt` 负责业务规则；`*Store.kt` 定义持久化接口；`Jdbc*Store.kt` 实现数据库访问。
- `Application.kt` 负责依赖组装、路由注册、同步服务和后台删除任务；`JdbcMigrations.kt` 提供迁移执行基础。

## 实现约束

- Route 保持轻量，不直接承载业务状态或 SQL；Service 不依赖 Ktor request/response 类型。
- 新增持久化能力时先定义 Store 接口，再提供 JDBC 实现。测试替身通过构造参数或 `Application.module` 注入，不要隐藏在全局单例中。
- 保留 `Application.module` 的可测试注入入口。读取配置时优先传递既有 `env: Map<String, String>`，避免在深层逻辑中重复读取进程环境。
- 数据库变更必须使用递增且不可复用的迁移版本；每个迁移保持事务性，并验证重复启动不会重复应用。
- 账号删除、云写入暂停、AI 日志与同步数据清理或后台任务属于同一生命周期链路；修改其中一处前需检查整条删除流程。
- 不在响应或日志中输出密码、token、短信验证码、数据库凭据及完整交易内容。

## 验证

运行单个测试类：

```powershell
.\gradlew.bat :services:backend:test --tests "com.bks.backend.<package>.<TestClass>"
```

- 同一轮连续改动期间，只运行与当前阶段改动直接相关的测试类或 package，不重复运行 Backend 全量测试；
- 整轮 Backend 改动完成后，运行一次 `.\gradlew.bat :services:backend:test`；
- 涉及应用组装、环境配置或数据库迁移时，必须同时运行相关集成测试和 `SecretScannerTest`。
