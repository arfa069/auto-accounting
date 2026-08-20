# ADR 指南

## 用途

ADR 记录长期有效、影响架构或产品边界的决定及其理由，不记录普通实现步骤、任务进度或测试日志。

## 编写规则

- 使用下一个未占用的四位编号和小写连字符文件名，例如 `0063-describe-decision.md`。
- 标题直接描述决定；正文说明背景、选择、理由、主要影响和必要的替代方案。
- 已接受 ADR 是历史记录，不通过改写来隐藏旧决定。决定被替代时新增 ADR，并互相链接说明替代关系。
- 决策必须与 `PRD.md`、`ARCHITECTURE.md`、`COMPLIANCE.md` 和 `CONTEXT.md` 的术语一致；冲突时明确指出需同步更新的基线文档。
- 不在 ADR 中写凭据、生产地址、真实用户数据或未经脱敏的日志。

## 验证

新增前检查编号：

```powershell
Get-ChildItem docs/adr -File | Sort-Object Name | Select-Object -Last 5 -ExpandProperty Name
```

随后检查相对链接和 `git diff --check -- docs/adr/`。
