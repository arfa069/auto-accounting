# Auto Accounting PRD

## 1. Product Goal

Build an Android automatic bookkeeping app for domestic Android app stores. The app captures WeChat and Alipay payment activity, turns captured transactions into pending entries, helps users review and confirm them, and keeps local-first named ledger books with reports, backup, account, AI categorization, and compliance materials ready for internal beta.

The first delivery target is a feature-complete internal beta, not a small MVP. It must be usable enough to measure capture accuracy, deduplication accuracy, review efficiency, and permission retention.

## 2. Target Platform

- Platform: Android only.
- Minimum supported version: Android 10 and above.
- Distribution target: domestic Android app stores.
- Mobile stack: Kotlin, Jetpack Compose, Room.
- Backend stack: Kotlin, Ktor, PostgreSQL.

## 3. Core Decisions

- Observe WeChat and Alipay through notification listening and opt-in accessibility reading of payment-result and payment-record pages.
- Automatic capture is the primary flow after explicit opt-in; user-started bill import (`补录账单`) remains a limited backfill and fallback path.
- Selecting a source for one manual-import session automatically authorizes local OCR for that session. The current fallback is limited to supported visible pages with no usable accessibility text and does not broaden automatic OCR. Screenshots are never persisted or uploaded; raw OCR text stays outside the ledger and may enter only the separately enabled encrypted diagnostic store within an accepted payment surface or active manual-import session.
- All automatically captured transactions first become pending entries.
- Ledger books are local-first in Room and can be explicitly bound to the signed-in account for automatic multi-device synchronization.
- One persisted current ledger book receives manual entries and confirmed pending entries. Categories and funding accounts remain shared across all local ledger books.
- Signing out preserves the on-device ledger and pauses sync. Switching to a different account requires confirmation and atomically replaces only the formal synchronized scope after the previous account outbox is empty.
- The backend supports account, registered device, cloud configuration, account-scoped ledger sync, AI categorization proxy, and AI categorization logs.
- Cloud AI categorization is opt-in. Local rules run first.
- AI categorization uploads minimal fields by default; users may opt into enhanced context.
- AI categorization logs are retained during internal beta and must be revisited before public store submission.
- Transaction, bill, merchant, amount, category, and funding-account data are treated as sensitive personal information.
- Sensitive diagnostic logs are a separate user-controlled, on-device troubleshooting capability: Debug defaults on, Release defaults off with informed opt-in, screenshots are excluded, authentication secrets are always redacted, and no diagnostic data is uploaded.

## 4. Users And Jobs

Primary user:
- Android user who pays mainly through WeChat and Alipay.
- Wants automatic bookkeeping but still wants control before entries enter the ledger.
- Is willing to grant sensitive permissions if the app clearly explains why.

Core jobs:
- Capture payment activity automatically or through manual bill import.
- Review pending entries quickly.
- Correct amount, time, transaction kind, category, funding account, merchant/title, and note.
- Build categorization rules from repeated corrections.
- Create and switch local ledger books, manage shared funding accounts, and view the current ledger book and reports.
- Export CSV and create encrypted backups.
- Control, inspect, clear, and passphrase-export sensitive diagnostic logs when troubleshooting is needed.
- Manage permissions, account, AI consent, and privacy controls.

## 5. Functional Scope

### 5.1 Capture

Sources:
- WeChat notifications.
- Alipay notifications.
- WeChat payment-result and payment-record pages after automatic capture is enabled.
- Alipay payment-result and payment-record pages after automatic capture is enabled.
- User-started accessibility bill import from the currently visible WeChat or Alipay bill surface.

Capture output:
- Pending entries only.
- Capture reason: notification capture, manual bill import, duplicate merge, or related reason.
- Manual bill import reads only the currently visible supported page. It does not auto-scroll, paginate, or promise a complete history scan.
- The Review Queue entry opens one app-level import flow; Automatic Bookkeeping contains only automatic-capture state and settings.
- The flow checks accessibility grant and live service connection separately before source selection or app launch.
- After source launch, the user has 90 seconds to enter and remain on a bill, transaction-detail, or payment-result page. Timeout affects only the matching session while it is still waiting.
- The accessibility processor and `ReviewQueuePersistence` are the only manual-import write path; UI observes the Room Flow and never creates pending entries a second time.
- Manual WeChat OCR creates a candidate only when `当前状态` and `支付成功` form the same field relationship and one unambiguous amount is present. Any of `确认支付`, `立即支付`, `收银台`, `支付密码`, `待支付`, `处理中`, `支付失败`, or `已取消` rejects the page.
- When present, normalized output includes payment method, product/receipt note, product title, merchant/payee, status, transaction time, transaction order id, and merchant order id.
- Confidence state: high confidence, needs review, duplicate suspect.

Transaction kinds:
- Cover all transaction kinds exposed by WeChat and Alipay bill pages where practical.
- Examples include expense, income, refund, transfer, red packet, repayment, investment movement, fees, and source-specific events.

### 5.2 Review Queue

Homepage:
- The review queue is the first tab and primary workflow.
- The header shows the target ledger-book name and an ignored-records action.
- One summary card shows pending total, duplicate suspect count, and today's pending count.
- A separate full-width `补录账单` card opens the shared manual-import flow.
- All pending entries appear in a single "待确认记录" list.
- Sorting: duplicate suspect and low-confidence entries first, then capture time descending.

List item fields:
- Amount.
- Merchant/title.
- Suggested category.
- Source.
- Capture reason.
- Confidence state.

Actions:
- Swipe right to confirm.
- Swipe left to ignore.
- A swipe resolves only after the row moves about 40% of its width; shorter slow or fast swipes settle without an action.
- Both actions show undo Snackbar.
- Ignored entries remain recoverable for 30 days.
- Opening an entry shows parse result first, editable fields second, and folded evidence third.

Detail page:
- Editable fields: amount, time, transaction kind, category, funding account, merchant/title, note.
- Evidence section is collapsed by default and can show raw text, source, capture time, and parsed fields.
- The page identifies the current ledger book that will receive the entry; confirmation captures that ledger-book ID before the asynchronous write starts.
- Bottom fixed action bar: confirm into ledger, ignore.

### 5.3 Ledger

Ledger tab:
- The title shows the current ledger-book name.
- Monthly grouped transaction list.
- Top monthly summary shows monthly expense, monthly income, and net amount.
- The centered add action in the home bottom navigation opens the manual-entry form.
- The review queue does not expose manual ledger-entry creation.
- The overflow menu lists Ledger Management, Funding Accounts, and Recently Deleted in that order.

Ledger row:
- Merchant/title.
- Category.
- Time.
- Amount.
- Source marker.

Ledger management:
- Users can create multiple named local ledger books. Names are trimmed, non-empty, and unique.
- Existing single-ledger installations migrate into a fixed default ledger book named "默认账本".
- The current ledger-book selection persists across process restarts. Creating a ledger book selects it immediately; selecting another ledger book returns to its ledger list.
- Users can delete only an empty ledger book that is not the final remaining ledger book. Active and recently deleted entries both make a ledger book non-empty.
- Deleting the current empty ledger book atomically selects the earliest-created remaining ledger book.
- Users cannot rename a ledger book or move an existing entry between ledger books in this version.
- Users can create a manual entry directly in the current ledger book without passing through the review queue.
- Manual entry requires flow direction, transaction kind, amount, and transaction time; the first version supports CNY only.
- Merchant/title, category, funding account, note, and payment source are optional; an omitted category uses the uncategorized category.
- The funding-account selector lists reusable existing accounts and offers inline creation of a named account with an optional payment source.
- A separate Funding Accounts page lists shared accounts and supports create, edit, and delete without opening a ledger-entry form.
- Funding-account names are trimmed and must be non-empty. The same normalized name cannot repeat within the same payment source, while equal names under different payment sources remain valid.
- Editing a funding account preserves its identity and creation time and does not rewrite the payment source stored on historical entries.
- A funding account can be deleted only when no active or recently deleted ledger entry, pending entry, or ignored entry references it. A blocked deletion identifies the reference counts by record type.
- Automatic confirmation preserves an existing funding-account ID when present; otherwise it may match an existing account by exact normalized name and payment source, but never creates an account automatically.
- Creating, selecting, or managing a funding account does not introduce account balances or reconciliation.
- The form can select existing categories or the uncategorized category; creating, editing, or deleting categories is outside this ledger CRUD scope.
- Users can edit flow direction, transaction kind, amount, transaction time, merchant/title, category, funding account, note, and payment source on both manual and automatically captured ledger entries.
- Amounts are stored as positive minor-unit values; flow direction independently determines whether an entry is an inflow, outflow, or neutral for reports.
- Amount input must be greater than zero, use at most two decimal places, and fit safely in the stored minor-unit integer; users do not enter a sign.
- Transaction time cannot be later than the current device time; future planned transactions are outside the ledger CRUD scope.
- Editing an automatically captured entry does not overwrite its original capture source, entry origin, original pending-entry reference, first confirmation time, or capture evidence.
- Entry origin, lifecycle timestamps, original capture source, original pending-entry ID, and capture evidence remain persisted but are ledger-entry debug metadata: Debug builds show them in the corresponding entry detail, while Release builds do not compose that section.
- Editing replaces the current user-visible fields and records the last-modified time; the first version does not keep per-edit versions or provide history rollback.
- Ledger entries retain their creation or first-confirmation time in addition to the last-modified time.
- Ledger display, search, and reports use the corrected payment source; Debug provenance views use the original capture source.
- Tapping a ledger row opens a read-only detail view; editing starts only after an explicit edit action.
- Manual creation and ledger-entry editing share the same transaction form while keeping their entry points and save actions distinct.
- Creation and editing keep changes in the form until the user explicitly saves; saving validates and writes the complete entry as one operation.
- Leaving a dirty form requires the user to choose between discarding changes and continuing editing.
- Manual creation and ledger-entry editing do not run duplicate detection; valid entries are saved directly even when similar ledger entries already exist.
- Deleting an entry removes it from the current ledger book's active list, search, CSV, and reports while keeping it recoverable in that ledger book for 30 days.
- The Recently Deleted page is scoped to the current ledger book and shows deletion time and remaining retention days.
- Recently deleted entries can be restored or permanently deleted; permanent deletion requires explicit confirmation and cannot be undone.
- Entries remaining in recently deleted for 30 days are permanently removed automatically.
- Deleting from the detail screen first confirms that the entry will remain recoverable for 30 days, then returns to the ledger and offers an immediate undo action.

Search and filters:
- Search icon.
- Filter panel with time, source, category, transaction kind, and amount range.

### 5.4 Reports

Reports first screen:
- All report queries use the current ledger book only.
- Define `x` as the latest month in the current ledger book that contains at least one reportable inflow or outflow entry; neutral entries do not establish `x`.
- Show the income and expense overview for month `x`.
- Panel A shows the expense category share for month `x`.
- Panel B shows total expense and total income for the inclusive seven-month window `[x-3, x+3]`.
- Inflow entries contribute to income, outflow entries contribute to expense, and neutral entries are excluded from income, expense, and net totals.
- Reports aggregate CNY entries only; multi-currency entry and exchange-rate conversion are outside the first-version scope.

Charts and empty states:
- Panel A uses a hand-drawn-style donut rendered with Compose `Canvas`. Rank expense categories by amount, show the first four, and combine any remaining categories into "其他"; the legend shows category names and percentages, the center shows total expense, and the read-only ranking shows category amounts so the result does not rely on color alone.
- Panel B uses a fixed seven-month cash-flow table with month, total-expense, and total-income columns. Missing months inside `[x-3, x+3]` use zero values, and the table does not provide category selection.
- If the current ledger book has no reportable inflow or outflow entries, do not invent `x` or render zero-filled report content; show a report-level no-data state.
- If month `x` contains income but no expense, keep the income overview and cash-flow table, show expense as zero, and replace Panel A's donut with a month-specific no-expense state instead of falling back to another month.
- Illustrated donut styling is allowed, but values and comparisons must stay immediately readable.

### 5.5 Categorization

Rules:
- Local categorization rules run before AI.
- A new installation starts with visible, editable local rules for common high-confidence categories; these records are stored in the same local rules table as user-created rules.
- Users can save a correction as a rule when confirming a pending entry.
- The app asks before saving a correction as a durable rule.
- Rule management page includes rule list and simple rule form:
  merchant, title keyword, source, transaction kind -> category.

AI:
- Disabled by default.
- Requires login and explicit AI categorization consent.
- Uses minimal fields by default.
- Enhanced AI context is optional and can be enabled only after AI categorization consent.
- Turning off AI categorization revokes enhanced AI context; turning AI back on starts from the minimal-field default and requires a new enhanced-context choice.
- Local mode keeps local categorization rules available, but shows cloud AI as login-required rather than offering an enable control.
- Primary UI shows the category only, not whether it came from a rule, AI, manual input, or no source.

### 5.6 Account

Account model:
- Full account system is included in the first version.
- Users can skip login and use local mode.
- Cloud-linked capabilities require a backend-verified Session; a restored but offline-unverified Session keeps local bookkeeping available and pauses cloud writes and account deletion.
- Android uses a random persisted installation UUID and does not read hardware identifiers.
- The backend uses an internal account ID. Each account can have one username, one email, one phone number, one shared password credential, and one WeChat identity; local Room ledger books never change ownership or sync to the account.

Login method:
- Username, email, or phone number + the account's shared password.
- WeChat OpenSDK OAuth, when a public AppID is configured; the entry is hidden otherwise.
- SMS or email verification for account recovery.

Registration:
- Username -> set password -> complete, without requesting or submitting a verification code.
- Email or phone number -> SMTP email or SMS verification code -> set password -> complete.
- An unbound WeChat authorization can create a WeChat-only account, or bind an existing password account by password or a dedicated code sent to a bound phone/email.

Identity management:
- A password account can bind at most one phone number and one email; either becomes an additional login and recovery identifier without changing the primary identifier.
- Identifier conflicts between password accounts are rejected without transfer or merge. A WeChat-only account can explicitly merge an existing password account; merging keeps the current account, accepts only complementary credentials, keeps current cloud configuration values, deduplicates devices, deletes source AI logs and source account Sessions, and never changes the local ledger.
- A WeChat identity can be unlinked only when a password credential and another login identifier remain. Verification uses the shared password or a code sent to the user-selected bound phone/email. Binding, merging and unlinking rotate Sessions.
- WeChat nickname and HTTPS avatar URL refresh after successful authorization; an unavailable or unsafe avatar uses the local placeholder.
- The client never stores AppSecret, SMTP credentials, WeChat tokens, OpenID, UnionID or raw provider responses. Android Session v3 is Keystore-encrypted and contains only the business Session, primary identifier, identifier list and display fields; v1/v2 can be restored and upgraded.

Password:
- 8-32 characters.
- Must include uppercase letters, lowercase letters, numbers, and symbols.

Login failure:
- Generic login error: "账号或密码不正确".
- After 5 consecutive failed password attempts across any bound identifier, temporarily lock the shared account credential and prompt phone/email recovery.

Account recovery:
- Entry: bottom of password input page.
- Flow: bound phone/email confirmation -> SMS/email verification code -> set new password.
- Successful recovery revokes older Sessions before issuing the new Session.

Deletion:
- Signing out must first revoke the current backend Session. Only after success may Android clear its encrypted Session and enter persistent local mode; network failure keeps the user signed in for retry.
- Signing out does not remove local ledger books, encrypted backups, or the cloud account.
- Account deletion removes cloud account, registered devices, cloud configuration, and AI categorization logs.
- Local ledger deletion is separate.
- Account deletion uses a 7-day cooling-off period.
- During cooling-off, users can log in and cancel deletion, but cloud AI and device configuration writes are paused.
- Deletion status and deadline come only from the backend. The request requires a second confirmation that names the cloud deletion scope, seven-day cooling-off period, and unchanged local ledger.
- Local data deletion is a separate settings action with backup reminder and typed confirmation phrase "删除本机数据".

### 5.7 SMS Verification

Frontend:
- 60-second resend countdown.
- Resend is disabled during countdown.
- Failure states must give clear reasons.

Backend limits:
- Same phone number: 1 send per 60 seconds, 5 per hour, 10 per 24 hours.
- Same device/IP: 5 per hour, 10 per 24 hours; abnormal requests are rejected.
- Verification code validity: 5 minutes.
- Same code can be tried 3 times; then it is invalidated.

### 5.8 Automatic Bookkeeping

The Automatic Bookkeeping page shows, in order:
- Automatic-bookkeeping state and its enable or disable action.
- Notification listening.
- Automatic-bookkeeping accessibility service.
- Non-blocking background-running, auto-start, battery-optimization, and battery-saver guidance.
- Continuous-monitoring health summary.

The Automatic Bookkeeping overview status is:
- Ready when automatic bookkeeping is enabled, notification listening and accessibility are available, and continuous monitoring is healthy.
- Needs attention when automatic bookkeeping is enabled but a required permission or service is unavailable; it names the specific cause.
- Off when the user has disabled automatic bookkeeping, regardless of retained permissions.

On Android 13 or later, bookkeeping-result notification permission is requested when the user enables automatic bookkeeping; denial does not block capture or persistence and therefore does not make the overview status need attention. Manual bill import remains user-started and is not a standing permission.

The permission and background-settings section shows compact rows with a title, one-sentence purpose, short status, and settings action. Background-running and auto-start state cannot be read reliably across manufacturers, so they remain “please check” guidance and never block automatic bookkeeping.

Compact copy:
- Notification listening: "用于识别微信、支付宝支付通知".
- Automatic-bookkeeping accessibility service: "用于识别支付结果页和支付记录".
- Background running: "避免系统关闭后台导致自动记账失效".
- Auto-start: "允许手机重启后恢复自动记账服务", followed by a short manufacturer-specific path where available.
- Ignore battery optimization: "避免系统休眠导致自动记账中断".
- Disable battery saver: "避免省电策略限制后台自动记账".

### 5.9 Backup And Export

- CSV export covers the current ledger book only, and its file name includes the ledger-book name.
- Encrypted backup export/import covers all ledger books, their entry ownership, shared local data, and the current ledger-book selection.
- Complete app backup must be encrypted before leaving the app sandbox.
- Before importing, validate the selected backup and passphrase without altering local data. After successful validation, require a separate confirmation that the import replaces the current local snapshot before restoring it.
- Importing older supported backups creates the fixed default ledger book and assigns all imported ledger entries to it.
- Clearing local data recreates one empty default ledger book and selects it so the app always has a valid current ledger book.

### 5.10 Sensitive Diagnostic Logs

- “合规与隐私”在 Debug 和 Release 都提供独立的“诊断日志”入口。Debug 默认开启；Release 默认关闭，并在首次开启前说明记录字段、排障用途、10 MB 上限、长期保留、设备内加密、关闭/清空方式和导出风险。
- 允许记录支付相关通知、已判定支付结果/支付记录页、当前补录会话的页面/OCR 文字、解析字段、采集证据和完整异常。普通通知、聊天、无关页面和不支持包名只记录拒绝元数据，不保存可见正文。
- 截图永不保存。密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥、微信 code/票据、OpenID 和 UnionID 等认证秘密或身份标识始终在写入前脱敏；账号流程不写入昵称、头像 URL 或 Provider 正文。
- 日志仅在设备内加密保存，不上传、不进入账本备份或系统备份。每条事件最多 256 KB，每段最多 1 MB，总密文最多 10 MB；超过上限才轮转最旧分段。
- 列表默认只显示元数据并加载最新 1000 条；敏感内容经二次确认后仅在本次页面会话显示，离页或进入后台立即重新遮罩，显示期间窗口启用 `FLAG_SECURE`。
- 关闭只停止新记录并保留历史。清空需二次确认并删除密文和设备密钥；本机数据删除还清除 Release 开启偏好，但已导出到 Downloads 的文件由用户自行管理。
- 导出使用至少 8 位且二次确认的临时口令，生成 `.aadiag` 加密文件。口令不得持久化。

## 6. UI Scope

Main navigation:
- The home bottom navigation exposes four destinations: review queue, ledger, reports, and profile.
- A raised centered add action opens manual ledger-entry creation; it is an action, not a fifth destination.
- Destination screens hide the bottom navigation and expose an explicit return-to-home action.
- Ledger Management and Funding Accounts are secondary pages opened from the ledger overflow menu, not additional bottom-navigation destinations.

Onboarding:
- Progressive onboarding.
- Users are introduced to account setup, payment notification access, result notifications, automatic capture, manual history backfill, and cloud AI only when relevant.

Login first screen:
- Cat companion.
- Value explanation.
- Phone number input.
- Visible local-mode entry.
- Login, registration, and local mode require agreement to user agreement and privacy policy.
- Local mode entry shows a one-time limitation explanation: local bookkeeping works, but cloud AI, device configuration, and future sync are unavailable.

Profile top:
- A compact, clickable account-state card: local mode states that the ledger remains on the device; signed-in mode shows a masked phone number and account state.
- The card opens Account Management; the profile does not add avatar, nickname, signature, or other personal-profile fields.

Profile overview:
- Shows only a one-line status summary and navigation affordance for each entry. It contains no switches, system-permission buttons, backup passphrase inputs, or other detailed controls.
- Lists entries in this order: Account Management, Automatic Bookkeeping, Categorization Rules, Data and Backup, Compliance and Privacy.
- Each entry opens a full in-app secondary page with a title and back action. Destination and secondary pages hide bottom navigation; back from a Profile secondary page returns to the Profile overview, and back from the Profile overview returns Home.

Profile entries:
- Account Management: local-mode sign-in/register entry; or, when signed in, masked phone number, validating/verified/offline/deletion-pending connection state, server deletion deadline, sign out without deleting local ledger books, and a separately protected account-deletion area. Do not add registered-device UI until its real data and actions are available.
- Account flow back hierarchy: Recovery returns to Login; Login, Registration, Local Mode Explanation, and Compliance Materials return to the account landing page; an account landing page opened from Account Management returns to Account Management. System back and visible back actions must be equivalent.
- Automatic Bookkeeping: state, permissions, and continuous-monitoring health as defined in section 5.8.
- Categorization Rules: local rule management plus the separately explained cloud-AI consent and enhanced-context settings.
- Data and Backup: normal actions for current-ledger CSV export and all-ledger encrypted-backup export/import, followed by a visually isolated destructive Local Data Deletion area that retains its backup reminder and typed confirmation.
- Compliance and Privacy: entry points for the privacy policy, personal-information collection list, third-party-service list, permission explanations, and the separate Diagnostic Logs control. Each document opens separately and is available in local mode; Diagnostic Logs is visible in both builds and follows section 5.10.
- Internal-beta readiness, device matrices, retention checks, and quality metrics are not user settings. They appear only in Debug-build Developer Tools and are absent from release builds and the Profile overview. They must not be conflated with the user-controlled Diagnostic Logs entry.

## 7. Visual And Copy Direction

Visual style:
- Young, colorful, playful.
- Expense uses warm colors.
- Income uses green.
- Risk and permission problems use red.
- Brand primary color uses lively blue or purple.

Character:
- Fixed cat companion throughout the app.
- 2D illustration assets with light animation.
- Character appears in onboarding, review, reports, permissions, confirmation flows, empty states, and store materials.
- Sensitive money, permission, privacy, deletion, and AI upload moments must use clear plain wording even when the character is present.

Copy:
- Cute and character-led overall.
- Form errors are friendly and direct.
- Account, privacy, payment, permission, and irreversible action copy must not hide risk behind cute wording.

Account form error copy:
- Phone format: "请输入 11 位手机号".
- Username format: "用户名需 4-20 位，以字母开头，仅包含字母、数字和下划线".
- Email format: "请输入有效的邮箱地址".
- Password rule: "密码需 8-32 位，包含大小写字母、数字和符号".
- Verification code wrong: "验证码不正确，请重新输入".
- Verification code expired: "验证码已过期，请重新获取".
- Too frequent verification-code request: "获取太频繁，请稍后再试".
- Password/login failed: "账号或密码不正确".
- Temporary account lock: "尝试次数过多，请稍后再试，或使用手机号或邮箱找回密码".
- Network failed: "网络连接失败，请检查后重试".
- Agreement unchecked: "请先阅读并同意用户协议和隐私政策".
- Verification-code send failed: "验证码发送失败，请稍后重试".
- Identifier already registered: "该账号标识已注册，请直接登录".
- Identifier not registered: "该账号标识尚未注册，请先创建账号".
- Password confirmation mismatch: "两次输入的密码不一致".

## 8. Internal Beta Success Metrics

Core metrics:
- Capture accuracy.
- Deduplication accuracy.
- Review efficiency.
- Permission retention.

Supporting metrics:
- Review queue completion rate.
- Manual bill-import completion and failure reasons.
- Automatic payment-result capture success, failure, and dedupe outcomes.
- Swipe confirm/ignore undo rate.
- AI categorization opt-in rate.
- AI suggestion acceptance rate.
- Account registration and local-mode selection rate.
- Crashes and ANR rate.

## 9. Non-Goals For First Internal Beta

- iOS support.
- Real-time cloud ledger sync.
- Full asset, liability, or balance reconciliation.
- Ledger-book renaming, moving existing entries between ledger books, or per-ledger copies of categories and funding accounts.
- Advertising SDKs or marketing tracking.
- Automatic payment, transfer, message sending, or chat reading.
- Public store submission until internal beta behavior and compliance package are reviewed.
