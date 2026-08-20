# 文档目录指南

## 作用域与文档职责

本文件适用于 `docs/`。

- `PRD.md`、`ARCHITECTURE.md`、`UI-DESIGN.md` 与 `COMPLIANCE.md` 描述当前产品和实现基线。
- `DEVELOPMENT-SLICES.md`、`PHASE-2-*.md` 与 `issues/phase-2/` 记录实施计划、风险、验收条件和验证结果。
- `adr/` 保存架构决策记录；`README.md` 是文档入口索引。

代码行为发生变化时，更新描述该行为的现行文档；不要只在计划或 issue 文件中记录已经落地的事实。

## 编辑约束

- 使用 UTF-8 和简洁 Markdown；标题层级连续，内部链接优先使用相对路径。
- 新增顶层文档或文档集合时，同步更新 `docs/README.md`。
- ADR 使用下一个四位编号和小写连字符文件名，例如 `0063-describe-decision.md`。ADR 记录“为什么这样决定”，不要改写旧 ADR 来伪造历史；决策被替代时新增 ADR 并链接原记录。
- Phase 2 issue 文件沿用现有结构：目标、范围、非目标、目标文件、验收标准、测试、手工验证、回滚说明、验证记录和依赖。
- 更新 issue 状态、依赖或验证结果时，同步检查 `issues/phase-2/README.md` 与相关汇总文档，避免索引和正文冲突。
- 文档不得包含真实凭据、个人数据、完整交易样本或未脱敏日志。

## 验证

提交前重新打开包含中文的文件确认编码，检查相对链接和编号，并运行：

```powershell
git diff --check -- docs/
rg -n "TODO|TBD|待补充" docs/
```

`TODO` 类标记可以保留，但必须确认它们是明确的后续事项，而不是遗漏内容。
