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

Home bottom-navigation destinations:
- Review.
- Ledger.
- Reports.
- Profile.

The four destination illustrations straddle the navigation surface's top edge and use a surface-colored outline. Use 52 dp artwork with 15 sp labels. A concave center cutout holds the existing purple circular add button with one soft shadow; the add button is not a fifth selectable destination. Destination screens hide the bottom navigation and expose an explicit return-to-home action.

The review queue is the first destination in navigation order. Permission status and setup tools live in Profile, with inline prompts where missing setup blocks a workflow.

## 3. Review Queue

First screen:
- Header with Home, `待确认`, ignored records, and `确认后记入「<当前账本名>」`.
- One status summary card.
- A separate full-width `补录账单` action card.
- One pending-record list.

Top summary:
- Pending total.
- Duplicate suspect count.
- Today's pending count.

Pending list:
- A single `待确认记录` group contains every pending entry.
- Confidence state remains visible on each row.

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
- Resolve only after the row moves about 40% of its width; shorter slow or fast swipes settle without an action.
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
- Above the confirm action, show "确认后记入「<当前账本名>」".
- Confirm into ledger.
- Ignore.

## 5. Manual Bill Import Flow

Entry points:
- Review Queue `补录账单` card.
- Automatic Bookkeeping `补录账单` action.
- Both entries open one app-level `ManualBillImportHost`; source selection and progress are not duplicated inside either screen.

Preflight:
- Check accessibility permission first. If missing, explain the limited purpose and show Settings.
- Check the live accessibility-service connection separately. If disconnected, do not launch a source; show Recheck, Settings, and Close.
- Source-app launch failure becomes an explicit retryable result.
- WeChat import offers an unchecked, per-session local-OCR consent for currently visible history-bill detail pages with no readable accessibility nodes. State that screenshots and raw OCR text are not saved or uploaded.

Progress UI:
- Stepwise progress, not silent background work.
- State clearly that each run reads only the currently visible page and does not auto-scroll, paginate, or scan all history.
- After opening WeChat or Alipay, ask the user to enter a bill, transaction-detail, or payment-result page, remain briefly, and return after recognition.
- Waiting expires after 90 seconds only for the matching session still awaiting a bill page.
- The authorized manual path covers WeChat history-bill details without depending on a mini-program or wallet Activity class; automatic OCR keeps its narrower Activity allowlist.
- OCR requires the exact field relationship `当前状态: 支付成功` (same line or adjacent key/value lines) plus one unambiguous amount. `确认支付`, `立即支付`, `收银台`, `支付密码`, `待支付`, `处理中`, `支付失败`, and `已取消` reject the page even if the positive pair is also visible.

Steps:
- Open source.
- Read bills.
- Parse.
- Deduplicate.
- Create pending entries.

Completion:
- Show added and deduplicated counts.
- Primary action: `查看待确认`.
- Secondary action: Close.
- If automatic bookkeeping is off, offer `开启自动记账` as an additional secondary action.
- UI displays progress only. `BillSyncCaptureProcessor` and `ReviewQueuePersistence` write Room; the Review Queue refreshes from Room Flow.

## 6. Ledger

Header:
- Use the current ledger-book name as the screen title.
- The overflow menu lists Ledger Management, Funding Accounts, and Recently Deleted in that order.

Top monthly summary:
- Monthly expense.
- Monthly income.
- Net amount.

Primary action:
- The raised center action in the home bottom navigation opens the manual-entry form.
- The ledger screen does not duplicate this action with a local floating button.
- Manual entry is not duplicated on the review-queue screen.

List:
- Grouped by month.
- Row fields: merchant/title, category, time, amount, source marker.
- Tapping a row opens a read-only ledger-entry detail screen rather than editing immediately.
- The list, summary, search, and filters use the current ledger book only.

Ledger management:
- List every ledger book with its name, entry count, and current indicator.
- Selecting a ledger book persists the selection and returns to that book's ledger.
- New ledger-book creation trims the name, rejects blank or duplicate names, and selects the new book immediately.
- Delete requires confirmation. A book with active or recently deleted entries, or the last remaining book, shows a specific blocked-deletion reason instead of deleting.
- This version does not expose ledger-book rename or move-entry actions.

Funding accounts:
- List all funding accounts shared across ledger books and provide create, edit, and delete actions.
- The form contains a required name and an optional WeChat or Alipay payment source.
- Reject an equal normalized name within the same payment source; allow equal names under different payment sources.
- Editing preserves the existing account identity and does not rewrite historical ledger-entry payment sources.
- Delete requires confirmation. When references exist, keep the account and show counts for active/deleted ledger entries, pending entries, and ignored entries.
- Keep the inline New Funding Account action in the ledger-entry form and apply the same normalization and duplicate rules.

Ledger-entry detail:
- Show the current transaction fields first.
- Show flow direction separately from transaction kind and amount.
- Display currency as CNY without offering a currency selector in the first version.
- Amount input accepts a positive value with at most two decimal places; flow direction controls the displayed sign.
- The date-time picker does not allow a value later than the current device time.
- The funding-account selector lists existing accounts and ends with a New Funding Account action.
- Inline funding-account creation requires a name, allows payment source to remain empty, and selects the new account immediately.
- The category selector uses existing categories and Uncategorized without an inline category-creation action.
- In Debug builds only, show entry origin, creation or first-confirmation time, last-modified time, original capture source, original pending-entry ID, and capture evidence in a separate debug-metadata section when available.
- In Release builds, do not compose or leave an entry point for that debug-metadata section; current transaction fields, edit, and delete remain available.
- Editing begins through an explicit edit action.
- Manual creation and editing reuse the same transaction form.
- Form changes remain local until the user taps Save.
- Navigating back with unsaved changes opens a choice to discard changes or continue editing.
- The overflow menu contains Delete; confirmation explains that the entry moves to Recently Deleted for 30 days.
- After deletion, return to the ledger and show a "Moved to Recently Deleted" Snackbar with Undo.

Recently deleted:
- Scope the list to the current ledger book.
- Show deletion time and the number of retention days remaining for each entry.
- Each entry offers Restore and Permanently Delete actions.
- Permanent deletion requires a confirmation that clearly states the entry cannot be recovered.
- Entries are removed automatically after 30 days.

Search and filtering:
- Search icon.
- Filter panel.
- Filter fields: time, source, category, transaction kind, amount range.

## 7. Reports

First screen:
- Scope every total, category-share, and cash-flow query to the current ledger book.
- Let `x` be the latest month in the current ledger book with at least one reportable income or expense entry; neutral entries do not establish `x`.
- Show month `x` income and expense totals above the report panels.
- Place Panel A, the month `x` expense-category share, before Panel B, the seven-month cash-flow table.

Panel A — expense category share:
- Render a hand-drawn-style donut with Compose `Canvas`.
- Include outflow entries from month `x` only. Sort categories by expense amount, keep the first four, and combine any remaining categories into "其他".
- Pair the donut with a legend showing category name and one-decimal percentage. Keep the existing ranking as a read-only list showing category name and amount; color is decorative reinforcement, never the only way to identify a segment.
- When month `x` has income but no expense, replace the donut and ranking with "本月暂无支出分类" while keeping the income overview visible.

Panel B — seven-month cash flow:
- Display the inclusive window `[x-3, x+3]` as month, expense, and income columns ordered by month.
- Fill missing months in that window with zero, keep all seven month rows available by scrolling, highlight row `x` with a light-purple background, and use coral for expense and teal for income.
- Do not offer category selection or retain the previous category-specific six-month trend.

Report-level no-data state:
- If the current ledger book has no reportable income or expense entries, there is no `x`; show "当前账本暂无可分析的收支" instead of overview totals or zero-filled report content.
- Neutral entries remain outside income, expense, category-share, and cash-flow calculations.

## 8. Profile

Top area:
- A compact account-state card links to Account Management.
- Local mode reads "Local mode · ledgers stored on this device".
- Signed-in mode shows a masked phone number and account state.
- Do not add avatar, nickname, signature, or other personal-profile fields.

Overview entries, in order:
1. Account Management.
2. Automatic Bookkeeping.
3. Categorization Rules.
4. Data and Backup.
5. Compliance and Privacy.

Each overview row contains one status summary and a navigation affordance only. It never contains switches, permission buttons, or backup-password fields. Selecting a row opens a full secondary page with a title and back action; bottom navigation remains visible, and switching tabs resets the secondary-page stack.

Account Management:
- In local mode, explain the state and offer sign-in or registration.
- When signed in, show masked account state, a normal sign-out action, and a visually separated account-deletion danger area.
- Sign-out keeps all local ledger books, while account deletion follows its separate cooling-off flow.

Automatic Bookkeeping:
- Order sections as state, required permissions, then user-started bill import.
- Overview status is Ready, Needs attention with a specific reason, or Off. Result notifications do not block capture and do not produce Needs attention.
- Keep the accessibility description explicit: it is limited to payment-result and permitted bill pages, and does not read chats or ordinary messages.

Categorization Rules:
- Keep local rule management available in every account state.
- In local mode, describe cloud AI as login-required and do not offer an enable control.
- After AI is enabled, show enhanced context as a separate opt-in. Turning off AI also turns off enhanced context; enabling AI again starts with minimal fields.

Data and Backup:
- Keep current-ledger CSV export and all-ledger encrypted-backup export/import in the normal area; their descriptions must make the different scopes explicit.
- Place Local Data Deletion at the bottom in a distinct danger area, retaining the backup reminder and typed confirmation.
- Import first validates the backup and passphrase without changing local data; only a second explicit confirmation permits replacing the local snapshot.

Compliance and Privacy:
- Show separate rows for Privacy Policy, Personal Information Collection List, Third-Party Service List, and Permission Explanations; each opens its own full document.
- Do not expose store-review materials, logs, device matrices, permission-retention checks, or quality metrics here. These belong to Debug-build Developer Tools only.

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

## 11. Automatic Bookkeeping Permission Section

First screen items:
- Notification listening.
- Automatic-bookkeeping accessibility service.
- Background running, auto-start, battery optimization, and battery-saver suggestions.
- User-started bill import appears after the permission section, not as a permission.

Each item:
- Title.
- One-sentence purpose.
- Short status and settings action.

Fixed copy:
- Notification listening: "用于识别微信、支付宝支付通知".
- Automatic-bookkeeping accessibility service: "用于识别支付结果页和支付记录".
- Automatic capture: "开启后自动识别受支持的支付通知和支付结果页".
- Background running: "避免系统关闭后台导致自动记账失效".
- Auto-start: "允许手机重启后恢复自动记账服务"; a short manufacturer-specific settings path may follow.
- Ignore battery optimization: "避免系统休眠导致自动记账中断".
- Disable battery saver: "避免省电策略限制后台自动记账".
- Bookkeeping result notifications are enabled with automatic bookkeeping and requested when needed on Android 13 or later; denial does not block capture or persistence.
- Background reliability suggestions never block automatic bookkeeping because auto-start and background-run state cannot be read reliably across manufacturers.
- Cloud AI: "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文".

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
