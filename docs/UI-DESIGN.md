# UI Design Specification

## 1. Product Personality

The app is young, playful, and colorful. It uses a cat companion throughout the experience, including onboarding, review, reports, permissions, and confirmation flows.

The UI can be cute, but sensitive moments must stay clear:
- Money movement.
- Privacy permission.
- AI data upload.
- Account deletion.
- Local data deletion.
- Backup and restore.

## 2. Navigation

Bottom tabs:
- Review.
- Ledger.
- Reports.
- Profile.

The review queue is the primary first tab. Permission status and setup tools live in Profile, with inline prompts where missing setup blocks a workflow.

## 3. Review Queue

First screen:
- Top status summary.
- Bill sync button.
- Pending list.

Top summary:
- Pending total.
- Duplicate suspect count.
- Today's newly captured count.
- Sync action.

List grouping:
- Needs careful review.
- Quick confirm.

Default sorting:
- Duplicate suspect and low-confidence first.
- Then capture time descending.

List item content:
- Amount.
- Merchant/title.
- Suggested category.
- Source.
- Capture reason.
- Confidence state.

Swipe actions:
- Right swipe confirms.
- Left swipe ignores.
- Both show undo Snackbar.

Ignored entries:
- Recoverable from ignored list for 30 days.

## 4. Pending Entry Detail

Layout:
- Parsed result summary first.
- Editable fields next.
- Evidence section collapsed by default.
- Bottom fixed action bar.

Editable fields:
- Amount.
- Time.
- Transaction kind.
- Category.
- Funding account.
- Merchant/title.
- Note.

Evidence section:
- Source.
- Capture time.
- Raw notification or bill text.
- Parsed fields.

Actions:
- Confirm into ledger.
- Ignore.

## 5. Bill Sync Flow

Entry points:
- Review page top action.
- Profile tools entry.

Progress UI:
- Stepwise progress, not silent background work.

Steps:
- Open source.
- Read bills.
- Parse.
- Deduplicate.
- Create pending entries.

The screen must show current user action when needed, such as opening the source app or staying on a bill page.

## 6. Ledger

Top monthly summary:
- Monthly expense.
- Monthly income.
- Net amount.

List:
- Grouped by month.
- Row fields: merchant/title, category, time, amount, source marker.

Search and filtering:
- Search icon.
- Filter panel.
- Filter fields: time, source, category, transaction kind, amount range.

## 7. Reports

First screen:
- Current-month income/expense overview.
- Category share.
- Trend entry.

Category share:
- Illustrated donut chart.
- Category ranking list.
- Values and percentages must be readable without relying only on color.

Category trend:
- Latest 6 months.
- User can select category.
- Use clear labels even with illustrated styling.

## 8. Profile

Top area:
- Login/account state.
- Local mode prompt where relevant.
- Permission health entry.

Groups:
- Account and security.
- Permissions and monitoring.
- AI categorization.
- Backup and export.
- Categorization rules.
- About and compliance.

## 9. Login, Registration, Recovery

Login/register first screen:
- Cat companion.
- Value explanation.
- Phone number input.
- Local mode entry.

Registration:
- Phone number.
- Verification code.
- Set password.

Local mode:
- Entry is visible.
- One-time explanation: local bookkeeping is available, but cloud AI, registered-device configuration, and future sync are unavailable.

Agreement:
- Login, registration, and local mode require agreement to user agreement and privacy policy.

Forgot password:
- Entry at bottom of password input page.
- Flow: phone confirmation, SMS code, new password.

## 10. Account Form Feedback

Tone:
- Friendly and direct.
- Explain what is wrong and how to fix it.
- Do not overuse cat jokes for account, security, privacy, or payment errors.

Placement:
- Field errors appear below the relevant input.
- Global errors use a top or bottom message bar.

Fixed copy:
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

## 11. Permission Center

First screen items:
- Notification listening.
- Bookkeeping result notifications.
- Automatic-bookkeeping accessibility service.
- Automatic capture.
- Cloud AI authorization.
- Background keep-alive / auto-start suggestion.

Each item:
- Status icon.
- Title.
- One-sentence purpose.
- Current status.
- Action button.

Fixed copy:
- Notification listening: "用于识别微信、支付宝的收付款通知，生成待确认账目".
- Bookkeeping result notifications: "用于通知待确认、分类建议、重复合并或识别失败结果；未授权不影响本地采集".
- Automatic-bookkeeping accessibility service: "用于开启自动记账后观察微信、支付宝支付结果和支付记录；微信空节点结果页可在本机瞬时 OCR，图片和 OCR 原文不保存、不上传；不读取聊天或普通消息，不发起付款、转账或退款".
- Automatic capture: "开启后会在支付完成时观察受支持的结果页，必要时在本机瞬时 OCR，并生成待确认记录；可随时关闭".
- Cloud AI: "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文".
- Background keep-alive: "建议允许后台运行，避免通知捕获中断；不同手机设置入口可能不同".

## 12. Visual System

Color semantics:
- Expense: warm color.
- Income: green.
- Risk/permission problem: red.
- Brand primary: lively blue or purple.

Charts:
- Illustrated and playful.
- Must keep labels, totals, and comparisons clear.

Cat companion:
- 2D illustration.
- Light animation.
- Can appear across all flows.
- Must not replace clear consent or warning copy.
