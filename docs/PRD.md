# Auto Accounting PRD

## 1. Product Goal

Build an Android automatic bookkeeping app for domestic Android app stores. The app captures WeChat and Alipay payment activity, turns captured transactions into pending entries, helps users review and confirm them, and keeps a local-first ledger with reports, backup, account, AI categorization, and compliance materials ready for internal beta.

The first delivery target is a feature-complete internal beta, not a small MVP. It must be usable enough to measure capture accuracy, deduplication accuracy, review efficiency, and permission retention.

## 2. Target Platform

- Platform: Android only.
- Minimum supported version: Android 10 and above.
- Distribution target: domestic Android app stores.
- Mobile stack: Kotlin, Jetpack Compose, Room.
- Backend stack: Kotlin, Ktor, PostgreSQL.

## 3. Core Decisions

- Observe WeChat and Alipay through notification listening and accessibility-based bill-page reading.
- Default to user-started bill sync; continuous monitoring is an advanced opt-in mode.
- All automatically captured transactions first become pending entries.
- The ledger is local-first; future cloud sync is reserved but not implemented in the first backend.
- The first backend supports account, registered device, cloud configuration, AI categorization proxy, and AI categorization logs.
- Cloud AI categorization is opt-in. Local rules run first.
- AI categorization uploads minimal fields by default; users may opt into enhanced context.
- AI categorization logs are retained during internal beta and must be revisited before public store submission.
- Transaction, bill, merchant, amount, category, and funding-account data are treated as sensitive personal information.

## 4. Users And Jobs

Primary user:
- Android user who pays mainly through WeChat and Alipay.
- Wants automatic bookkeeping but still wants control before entries enter the ledger.
- Is willing to grant sensitive permissions if the app clearly explains why.

Core jobs:
- Capture payment activity automatically or through manual bill sync.
- Review pending entries quickly.
- Correct amount, time, transaction kind, category, funding account, merchant/title, and note.
- Build categorization rules from repeated corrections.
- View ledger and reports.
- Export CSV and create encrypted backups.
- Manage permissions, account, AI consent, and privacy controls.

## 5. Functional Scope

### 5.1 Capture

Sources:
- WeChat notifications.
- Alipay notifications.
- WeChat bill pages through accessibility bill sync.
- Alipay bill pages through accessibility bill sync.
- Optional continuous monitoring of payment-related pages.

Capture output:
- Pending entries only.
- Capture reason: notification capture, bill sync, duplicate merge, or related reason.
- Confidence state: high confidence, needs review, duplicate suspect.

Transaction kinds:
- Cover all transaction kinds exposed by WeChat and Alipay bill pages where practical.
- Examples include expense, income, refund, transfer, red packet, repayment, investment movement, fees, and source-specific events.

### 5.2 Review Queue

Homepage:
- The review queue is the first tab and primary workflow.
- Top summary shows pending total, duplicate suspect count, today's newly captured count, and a bill sync button.
- List is grouped into "needs careful review" and "quick confirm".
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
- Both actions show undo Snackbar.
- Ignored entries remain recoverable for 30 days.
- Opening an entry shows parse result first, editable fields second, and folded evidence third.

Detail page:
- Editable fields: amount, time, transaction kind, category, funding account, merchant/title, note.
- Evidence section is collapsed by default and can show raw text, source, capture time, and parsed fields.
- Bottom fixed action bar: confirm into ledger, ignore.

### 5.3 Ledger

Ledger tab:
- Monthly grouped transaction list.
- Top monthly summary shows monthly expense, monthly income, and net amount.

Ledger row:
- Merchant/title.
- Category.
- Time.
- Amount.
- Source marker.

Search and filters:
- Search icon.
- Filter panel with time, source, category, transaction kind, and amount range.

### 5.4 Reports

Reports first screen:
- Current-month income and expense overview.
- Category share.
- Trend entry point.

Charts:
- Category share uses illustrated donut chart plus category ranking.
- Category trend shows the latest 6 months and allows category selection.
- Illustrated chart style is allowed, but values and comparisons must stay immediately readable.

### 5.5 Categorization

Rules:
- Local categorization rules run before AI.
- Users can save a correction as a rule when confirming a pending entry.
- The app asks before saving a correction as a durable rule.
- Rule management page includes rule list and simple rule form:
  merchant, title keyword, source, transaction kind -> category.

AI:
- Disabled by default.
- Requires login and explicit AI categorization consent.
- Uses minimal fields by default.
- Enhanced AI context is optional.
- Primary UI shows the category only, not whether it came from a rule, AI, manual input, or no source.

### 5.6 Account

Account model:
- Full account system is included in the first version.
- Users can skip login and use local mode.
- Cloud-linked capabilities require login.

Login method:
- Phone number + password.
- SMS verification for account recovery.

Registration:
- Phone number -> SMS verification code -> set password -> complete.

Password:
- 8-32 characters.
- Must include uppercase letters, lowercase letters, numbers, and symbols.

Login failure:
- Generic login error: "手机号或密码不正确".
- After 5 consecutive failed password attempts, temporarily lock login and prompt SMS recovery.

Account recovery:
- Entry: bottom of password input page.
- Flow: phone confirmation -> SMS verification code -> set new password.

Deletion:
- Account deletion removes cloud account, registered devices, cloud configuration, and AI categorization logs.
- Local ledger deletion is separate.
- Account deletion uses a 7-day cooling-off period.
- During cooling-off, users can log in and cancel deletion, but cloud AI and device configuration writes are paused.
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

### 5.8 Permissions And Monitoring

Permission center first screen shows:
- Notification listening.
- Accessibility bill sync.
- Continuous monitoring.
- Cloud AI authorization.
- Background keep-alive / auto-start suggestion.

Each permission item shows:
- Status icon.
- Title.
- One-sentence purpose.
- Current state.
- Action button.

Permission copy:
- Notification listening: "用于识别微信、支付宝的收付款通知，生成待确认账目".
- Accessibility bill sync: "用于手动账单同步，或在你开启连续监控后观察微信、支付宝账单和支付记录；不读取聊天、消息，不发起付款或转账".
- Continuous monitoring: "开启后会持续观察支付相关页面，提高自动捕获完整度；可随时关闭".
- Cloud AI: "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文".
- Background keep-alive: "建议允许后台运行，避免通知捕获中断；不同手机设置入口可能不同".

### 5.9 Backup And Export

- CSV export for spreadsheet inspection and external analysis.
- Encrypted backup export/import for migration and recovery.
- Complete app backup must be encrypted before leaving the app sandbox.

## 6. UI Scope

Main navigation:
- Four bottom tabs: review queue, ledger, reports, profile.

Onboarding:
- Progressive onboarding.
- Users are introduced to account setup, notification access, bill sync, continuous monitoring, and cloud AI only when relevant.

Login first screen:
- Cat companion.
- Value explanation.
- Phone number input.
- Visible local-mode entry.
- Login, registration, and local mode require agreement to user agreement and privacy policy.
- Local mode entry shows a one-time limitation explanation: local bookkeeping works, but cloud AI, device configuration, and future sync are unavailable.

Profile top:
- Login/account state.
- Local mode prompt where relevant.
- Permission health entry.

Profile groups:
- Account and security.
- Permissions and monitoring.
- AI categorization.
- Backup and export.
- Categorization rules.
- About and compliance.

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
- Password rule: "密码需 8-32 位，包含大小写字母、数字和符号".
- Verification code wrong: "验证码不正确，请重新输入".
- Verification code expired: "验证码已过期，请重新获取".
- Too frequent verification-code request: "获取太频繁，请稍后再试".
- Password/login failed: "手机号或密码不正确".
- Temporary account lock: "尝试次数过多，请稍后再试，或使用短信找回密码".
- Network failed: "网络连接失败，请检查后重试".
- Agreement unchecked: "请先阅读并同意用户协议和隐私政策".
- SMS send failed: "验证码发送失败，请稍后重试".
- Phone already registered: "该手机号已注册，请直接登录".
- Phone not registered: "该手机号尚未注册，请先创建账号".
- Password confirmation mismatch: "两次输入的密码不一致".

## 8. Internal Beta Success Metrics

Core metrics:
- Capture accuracy.
- Deduplication accuracy.
- Review efficiency.
- Permission retention.

Supporting metrics:
- Review queue completion rate.
- Manual bill sync completion and failure reasons.
- Swipe confirm/ignore undo rate.
- AI categorization opt-in rate.
- AI suggestion acceptance rate.
- Account registration and local-mode selection rate.
- Crashes and ANR rate.

## 9. Non-Goals For First Internal Beta

- iOS support.
- Real-time cloud ledger sync.
- Full asset, liability, or balance reconciliation.
- Advertising SDKs or marketing tracking.
- Automatic payment, transfer, message sending, or chat reading.
- Public store submission until internal beta behavior and compliance package are reviewed.
