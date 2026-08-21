# ADR 0063: 使用 Assists 通用识别取代平台专用采集

- Status: Accepted
- Date: 2026-08-21
- Supersedes: [ADR 0001](./0001-observe-wechat-and-alipay-with-notifications-and-accessibility.md)、[ADR 0003](./0003-default-to-manual-bill-sync-with-optional-continuous-monitoring.md)，并替代 [ADR 0055](./0055-store-opt-in-sensitive-diagnostics-on-device.md) 中允许记录采集页面与 OCR 内容的部分

## Context

微信/支付宝专用通知、无障碍规则、手动补录和截图 OCR 形成了多条入口与平台分支，维护成本高，并扩大了权限、诊断和隐私边界。产品当前只需要被动识别第三方应用当前活动窗口中的明确已完成交易，并继续使用既有待确认、去重和分类能力。

## Decision

- Android 只引入 `io.github.ven-coder:assists-base:3.5.5`；不使用 `assists-mp`、`assists-opcv`、截图或 OCR。
- Application 初始化 `AssistsCore`；无障碍服务继承 `AssistsService`，监听三类窗口事件并读取 `ActiveWindow` 根节点。
- 用户意图开关默认关闭。权限和服务连接状态与开关分离；断开不反写用户意图。
- 节点读取仅限可见、非密码、非可编辑文字，排除 BKS，限制 512 节点、24 层和 16 KiB，不操作其他应用。
- 只有完成态、唯一金额、无冲突方向和交易上下文全部满足时才生成候选；付款发起、密码、未完成、失败、取消和冲突页面拒绝。
- 新候选固定为 `OTHER`、`ACCESSIBILITY_AUTO`、`NEEDS_REVIEW`，无资金账户和原始页面文本，只进入待确认。
- 原始页面文字、未命中内容和无关页面信息不进入 Room、诊断日志、备份、同步或上传。
- 保留历史微信/支付宝及旧捕获枚举值以读取旧数据；新识别不再生成这些值。

## Consequences

- 删除平台专用解析、通知入口、手动补录会话、OCR 与截图能力，Review Queue 不再提供补录入口。
- Manifest 不再限制平台包名或声明截图能力；微信包查询仅为微信登录保留。
- Room v12 增加本地 `automatic_bookkeeping_enabled`，备份导入不恢复该开关。
- 旧 ADR、Issue 和研究文档作为历史证据保留；现役行为以 PRD、架构、合规和通用识别规则为准。

## Alternatives Rejected

- 继续维护微信/支付宝适配器：重新引入平台分支和重复规则。
- 使用截图或 OCR 补齐无障碍文本：扩大敏感数据范围和权限风险。
- 自动写入账本：绕过既有待确认安全边界。
