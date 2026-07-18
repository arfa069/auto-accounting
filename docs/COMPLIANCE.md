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
- Android uses a random app-installation UUID for this purpose and does not read a hardware identifier.
- Notification content from WeChat/Alipay payment notifications: create pending entries; required only after notification permission is enabled.
- Payment-result and payment-record page content from WeChat/Alipay: automatic capture after explicit opt-in, or user-started history backfill.
- Automatic accessibility observations: create pending entries after payment completion; optional and user-controlled. Accessibility nodes are preferred; blank WeChat payment-result surfaces may use transient on-device screenshot OCR on Android 11 or later.
- Ledger and pending-entry data: local bookkeeping; required for core bookkeeping.
- AI categorization payload: cloud AI suggestions; optional.
- Enhanced AI context: improve cloud AI accuracy; optional.
- AI categorization logs: internal beta improvement; optional through AI consent and must be revisited before public submission.
- Internal-beta readiness and quality metrics: locally derived readiness status and aggregate QA inputs shown only in Debug Developer Tools; this is not a separate raw-log store.
- Sensitive diagnostic logs: payment notification/page/OCR text, parsed transaction fields, capture evidence, and complete exceptions for troubleshooting. Debug defaults on; Release defaults off and requires a separate informed opt-in.
- App distribution statistics: store delivery and basic distribution measurement.

Important classification:
- Local transaction processing is required for bookkeeping.
- Cloud AI categorization is optional.

## 4. Permission Boundaries

Notification listening:
- Purpose: identify WeChat and Alipay payment notifications and generate pending entries.
- It must not process unrelated notifications beyond what is necessary to identify payment activity.

Automatic-bookkeeping accessibility service:
- Purpose: observe allowlisted WeChat and Alipay payment-result/payment-record pages after explicit opt-in, and read bill pages during user-started history backfill.
- The privacy policy must state that the app does not read chats or ordinary messages, send messages, or initiate payments, transfers, or refunds.
- For a blank WeChat accessibility surface, the app may take one transient screenshot for bundled on-device OCR. Android 14 or later limits capture to the active app window; Android 11-13 uses the display screenshot API. The image must never be persisted or uploaded. Raw OCR text remains outside the ledger/database; when the user separately enables sensitive diagnostic logs, text from an accepted payment surface or active manual-import session may be retained only in the encrypted local diagnostic store.

Sensitive diagnostic logging:
- The binding design and operator controls are documented in [ADR 0055](./adr/0055-store-opt-in-sensitive-diagnostics-on-device.md) and [DIAGNOSTIC-LOGS.md](./DIAGNOSTIC-LOGS.md).
- Available in Debug and Release. Debug defaults on; Release defaults off and requires an explicit confirmation under Compliance and Privacy.
- Payment-related notification text, accepted payment-page/manual-session text, accepted OCR output, amount, merchant, note, payment account/method, order identifiers, capture evidence, and complete exceptions may be recorded.
- Ordinary notifications, chats, unsupported packages, and unrelated pages record rejection metadata only, never visible text.
- Screenshots are never stored. Authentication secrets such as passwords, verification codes, tokens, cookies, Authorization headers, API keys, backup passphrases, signing keys, and private keys are always redacted before write.
- Events are individually encrypted with Android Keystore AES-256-GCM in `noBackupFilesDir`; 1 MB segments rotate only when total ciphertext exceeds 10 MB. Logs are not uploaded or included in ledger/system backup.
- Closing the switch stops new events without deleting history. Clear removes segments and the Keystore key. Local-data deletion also removes the Release preference, while exported Downloads files remain the user's responsibility.
- Export requires a non-persisted passphrase of at least eight characters, confirmation, and produces an independently encrypted `.aadiag` file.

Automatic capture:
- Explicit opt-in.
- Purpose: observe supported payment-result and payment-record pages and create pending entries.
- Must be closable at any time.
- Notification-listener access is not a prerequisite.

Bookkeeping result notifications:
- Purpose: report pending, categorization, dedupe, or failure outcomes after local processing.
- Denial must not block local capture or persistence.
- Lock-screen public content must omit amount, merchant, counterparty, and raw evidence.

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
- No diagnostic-log provider: sensitive diagnostics remain on-device and are never uploaded by this feature.
- App distribution statistics provider.
- Google ML Kit Chinese Text Recognition bundled model: on-device OCR only; transient payment-screen pixels and recognized text are not sent by the app to a cloud OCR service.

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
- Internal-beta readiness and quality metrics: retained only as ordinary local app state or explicit QA records; they do not create a separate raw-log store.
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
- Enable automatic bookkeeping; on Android 13 or later this action may also request result-notification permission, whose denial does not block local capture or persistence.
- Open system settings for notification listening, accessibility, background running, auto-start, battery optimization, and battery saver. Background reliability suggestions are non-blocking and must not be presented as reliably verified permissions when the operating system does not expose their state.

## 8. Account Deletion And Local Data Deletion

Account deletion:
- Starts a 7-day cooling-off period.
- During cooling-off, user may log in and cancel deletion.
- During cooling-off, cloud AI and device configuration writes are paused.
- After cooling-off, delete cloud account, registered devices, cloud configuration, and AI categorization logs.
- AI logs and cloud configuration are cleaned first; if either cleanup fails, retain the pending account and retry later instead of partially deleting account identity.

Account credentials and Session handling:
- Android encrypts the phone number and bearer token with Android Keystore AES-GCM and keeps them outside ledger backup, diagnostics, logs, and screenshots.
- The backend stores verification codes only as server-secret HMAC values and Session tokens only as SHA-256 hashes. Security migration invalidates legacy temporary credentials rather than keeping plaintext compatibility.
- SMS IP rate limits use the server-observed remote address; client-submitted IP values are ignored.

Local data deletion:
- Separate settings entry.
- Show backup reminder first.
- Require typed confirmation phrase "删除本机数据".
- Delete local ledger and app data from the device.
- Delete diagnostic segments, their Keystore key, and the Release enable preference. Warn that `.aadiag` files already exported to Downloads are not automatically deleted.

## 9. Store Review Risks

High-risk areas:
- Notification listening.
- Accessibility service.
- Automatic accessibility capture.
- Transient accessibility screenshot and local OCR fallback.
- Cloud AI uploads.
- AI categorization logs.
- Account deletion and data deletion.
- Cute companion copy on sensitive screens.

Mitigations:
- Use clear permission copy.
- Provide structured privacy materials.
- Keep automatic capture behind a clear user-controlled switch.
- Restrict local OCR to blank WeChat surfaces on Android 11 or later, require payment-completion plus currency evidence before persistence, and release the screenshot immediately after recognition. OCR text is retained only in the separately enabled encrypted diagnostic store and only within the accepted payment/manual-session boundary.
- Make the diagnostic entry visible in both builds, keep sensitive values masked by default, re-mask on background/exit, apply `FLAG_SECURE` while revealed, and require passphrase-encrypted export.
- Show stepwise bill sync progress.
- Default to local rules and keep cloud AI off.
- Do not include ad or marketing tracking SDKs.
- Keep sensitive screens plain and unambiguous even with the cat companion.
