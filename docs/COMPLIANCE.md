# Compliance And Privacy Draft

This document is a product and engineering compliance draft, not legal advice. Before public submission, legal counsel should review the privacy policy, user agreement, permission descriptions, third-party service list, AI logging policy, and account deletion process.

Reference anchors:
- [Personal Information Protection Law, official database](https://flk.npc.gov.cn/detail2.html?ZmY4MDgxODE3YjY0NzJhMzAxN2I2NTZjYzIwNDAwNDQ%3D=)
- [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Google Play AccessibilityService API policy](https://support.google.com/googleplay/android-developer/answer/10964491), used as a risk reference even though the target is domestic Android stores.

## 1. Compliance Package

The internal beta must include:
- Privacy policy.
- Personal information collection list.
- Third-party service list.
- Permission explanation page.
- Store review notes for notification listening and accessibility.
- Screenshots or screen recording showing permission explanations and bill sync boundaries.

## 2. Data Classification

Sensitive transaction information:
- Transaction amount.
- Merchant/title.
- Bill text.
- Raw notification text.
- Category.
- Transaction kind.
- Funding account.
- Source.
- Time.
- Notes.

Treat these as sensitive personal information because they may reveal financial status, habits, and movement patterns.

## 3. Personal Information Collection List

Suggested rows:

- Phone number: account registration, login, recovery, deletion verification; required for account mode, not required for local mode.
- Password hash: password login; required for account mode.
- Device information: registered-device security, fraud prevention, SMS rate limits, configuration; required for account mode and SMS risk control.
- Notification content from WeChat/Alipay payment notifications: create pending entries; required only after notification permission is enabled.
- Bill page content from WeChat/Alipay: manual bill sync; required only when user starts bill sync.
- Continuous monitoring observations: improve capture completeness; optional advanced mode.
- Ledger and pending-entry data: local bookkeeping; required for core bookkeeping.
- AI categorization payload: cloud AI suggestions; optional.
- Enhanced AI context: improve cloud AI accuracy; optional.
- AI categorization logs: internal beta improvement; optional through AI consent and must be revisited before public submission.
- Crash/log data: stability troubleshooting; internal beta and app quality.
- App distribution statistics: store delivery and basic distribution measurement.

Important classification:
- Local transaction processing is required for bookkeeping.
- Cloud AI categorization is optional.

## 4. Permission Boundaries

Notification listening:
- Purpose: identify WeChat and Alipay payment notifications and generate pending entries.
- It must not process unrelated notifications beyond what is necessary to identify payment activity.

Accessibility bill sync:
- Purpose: read WeChat and Alipay bill pages only during user-started bill sync.
- The privacy policy must state that the app does not read chat content, send messages, initiate payments, or initiate transfers.

Continuous monitoring:
- Advanced opt-in.
- Purpose: observe payment-related pages to improve capture completeness.
- Must be closable at any time.
- Must not be part of the default onboarding path.

Cloud AI:
- Disabled by default.
- Requires explicit user consent.
- Minimal fields by default.
- Enhanced context requires a separate user choice.
- AI logs are retained during internal beta and must be redefined before public store submission.

## 5. Third-Party Service List

First-version third-party categories:
- SMS provider.
- Cloud AI provider.
- Crash/log provider.
- App distribution statistics provider.

Excluded in first version:
- Advertising SDKs.
- Marketing tracking SDKs.

Each third-party entry should include:
- Provider name.
- Service purpose.
- Personal information category.
- Processing method.
- Data retention or deletion policy, if available.
- Link to provider privacy terms.

## 6. Data Retention

Retention must be written by data type:

- Local ledger: retained on device until user deletes entries, deletes local data, uninstalls the app, or restores/replaces data.
- Pending entries: retained until confirmed, ignored, deleted, or expired by product rules.
- Ignored entries: recoverable for 30 days.
- Encrypted backup: controlled by the user after export.
- CSV export: controlled by the user after export and should be described as plain-text.
- Account data: retained while account exists; deleted after account deletion cooling-off completes.
- Registered device and cloud configuration: retained while account exists; deleted after account deletion completes.
- AI categorization logs: retained during internal beta; retention must be revisited before public submission.
- Crash/log data: short-term retention should be defined before implementation.
- SMS verification data: retain only as needed for verification, fraud prevention, audit, and security.

## 7. User Rights And Controls

In-app controls:
- View privacy policy and collection list.
- View third-party service list.
- Enable/disable cloud AI.
- Enable/disable enhanced AI context.
- Clear AI categorization logs where technically available.
- Export CSV.
- Export/import encrypted backup.
- Request account deletion.
- Cancel account deletion during 7-day cooling-off period.
- Delete local data through a separate confirmation flow.

## 8. Account Deletion And Local Data Deletion

Account deletion:
- Starts a 7-day cooling-off period.
- During cooling-off, user may log in and cancel deletion.
- During cooling-off, cloud AI and device configuration writes are paused.
- After cooling-off, delete cloud account, registered devices, cloud configuration, and AI categorization logs.

Local data deletion:
- Separate settings entry.
- Show backup reminder first.
- Require typed confirmation phrase "删除本机数据".
- Delete local ledger and app data from the device.

## 9. Store Review Risks

High-risk areas:
- Notification listening.
- Accessibility service.
- Continuous monitoring.
- Cloud AI uploads.
- AI categorization logs.
- Account deletion and data deletion.
- Cute companion copy on sensitive screens.

Mitigations:
- Use clear permission copy.
- Provide structured privacy materials.
- Keep continuous monitoring in advanced settings.
- Show stepwise bill sync progress.
- Default to local rules and keep cloud AI off.
- Do not include ad or marketing tracking SDKs.
- Keep sensitive screens plain and unambiguous even with the cat companion.
