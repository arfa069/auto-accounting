# Shared API 模块指南

## 作用域

本文件适用于 `shared/api/`。该模块只维护 Android 客户端与 Ktor 后端共同使用的 Kotlin 契约和 JSON 编解码规则，不放置平台 UI、数据库访问、网络调用或业务流程。

生产代码位于 `src/main/kotlin/com/bks/api/`。按契约主题拆分文件，避免建立仅用于重新导出类型的包装层。

## 契约约束

- 数据类使用 `*RequestContract`、`*ResponseContract` 或含义明确的稳定名称。
- JSON 字段名、必填性、默认值和空值语义属于公共接口。重命名、删除或改变类型前，必须检查 Android 与 Backend 的全部调用方。
- 新增可选字段时提供兼容默认值；不要让旧响应因缺少新字段而无故解析失败。
- 编码和解析必须保持对称。涉及 Map 等无序集合时，输出应保持确定性，便于测试和日志比较。
- 不在共享契约中加入凭据、内部数据库字段或不必要的敏感原文。

## 验证

先搜索调用方：

```powershell
rg "ContractName|jsonFieldName" apps/android services/backend shared/api
```

至少运行 `.\gradlew.bat :shared:api:build`。JSON 契约发生变化时，还需运行 Android 和 Backend 中对应的契约、路由或客户端测试；跨模块改动最终运行 `.\gradlew.bat build`。
