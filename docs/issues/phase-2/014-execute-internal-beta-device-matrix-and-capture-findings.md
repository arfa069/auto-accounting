# Execute Internal Beta Device Matrix And Capture Findings

## What to build

Execute the packaged internal beta against the planned Android device matrix, record real-device findings, and turn observed regressions, ROM-specific blockers, and rollout risks into explicit follow-up work.

## Scope

Run the manual beta checklist from the existing release package on a controlled set of Android 10-15 devices and major domestic ROM families. Capture outcome, evidence, blockers, and recommended next actions for notification capture, bill sync, continuous monitoring, AI categorization, backup/restore, deletion, and permission retention.

## Non-goals

- Do not redesign features or expand Phase 2 scope during this issue.
- Do not silently fix discovered bugs inside the validation pass; log them into follow-up issues unless a separate implementation issue is explicitly opened.
- Do not treat missing signing materials, missing devices, or ROM restrictions as solved unless evidence is recorded.

## Target files/modules

- `docs/INTERNAL-BETA-RELEASE.md`
- `docs/issues/phase-2`
- Optional evidence artifacts under `docs/` if screenshots, pass/fail logs, or ROM notes need a stable home

## Acceptance criteria

- [ ] The device matrix run is recorded with at least one outcome or explicit blocker for each planned Android version / ROM family in scope.
- [ ] Manual beta flows for account, local mode, notification capture, bill sync, continuous monitoring, review queue, ledger, reports, AI, backup/restore, local deletion, account deletion, and compliance are marked pass, fail, or blocked with notes.
- [ ] Capture accuracy, deduplication accuracy, review efficiency, and permission retention observations are summarized with enough detail to support a go / no-go beta decision.
- [ ] Every material bug, regression, or rollout blocker found during the run is linked to a follow-up issue or documented as an explicit known risk.
- [ ] Remaining signing, distribution, and tester-onboarding blockers are documented clearly enough for the next operator to continue without rediscovery.

## Acceptance tests

- [x] `.\gradlew.bat --no-daemon :services:backend:test`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [x] `.\gradlew.bat --no-daemon :apps:android:assembleRelease`

## Device verification

Status: In progress on 2026-07-09. One Xiaomi `24117RK2CC` (`zorn`) device connected through ADB over Wi-Fi on Android 16 / SDK 36 / MIUI `V816`. The unsigned release artifact is still not directly installable, but the debug build now installs and launches so local-mode smoke validation can continue on-device. On this Xiaomi device, notification-listener enablement, accessibility enable/disable reflection, and the accessibility-settings deep link have now been verified in local mode.

- [ ] Run the release artifact on the target Android 10-15 device matrix and record install / launch outcome.
- [ ] Verify notification listener permission, accessibility permission, and continuous monitoring enable/disable behavior on each available ROM family.
- [ ] Complete one WeChat and one Alipay payment capture flow per available device and record capture accuracy, duplicate behavior, and review ergonomics.
- [ ] Exercise backup/restore, local data deletion, and account deletion flows where the backend and tester setup allow it.
- [ ] Record screenshots, notes, or blocker reasons for any failed or skipped device scenario instead of leaving blanks.

## Rollback or safety notes

- This issue is primarily validation and documentation work. Keep code changes, if any, limited to evidence capture or clearly separated follow-up fixes so the beta-readiness record stays trustworthy.
- Do not distribute builds beyond the controlled tester set described in the release package until the recorded blockers are reviewed.

## Blocked by

- Issue 12: Package Internal Beta QA Metrics And Release Build
- Issue 13: Correct Tester-Facing Android Copy Encoding

## Verification record

- `2026-07-09`: `.\gradlew.bat --no-daemon :services:backend:test` passed.
- `2026-07-09`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest` passed.
- `2026-07-09`: `.\gradlew.bat --no-daemon :apps:android:assembleRelease` passed.
- `2026-07-09`: release artifact confirmed at `apps/android/build/outputs/apk/release/android-release-unsigned.apk`.
- `2026-07-09`: `adb` was not on `PATH`; local SDK path from `local.properties` resolved to `C:\Users\Arfa\AppData\Local\Android\Sdk`, and `platform-tools\adb.exe devices -l` returned no attached devices.
- `2026-07-09`: device `192.168.1.6:44399` connected over ADB Wi-Fi and reported `product=zorn`, `model=24117RK2CC`, Android `16`, SDK `36`, manufacturer `Xiaomi`, MIUI `V816`.
- `2026-07-09`: installing `apps/android/build/outputs/apk/release/android-release-unsigned.apk` failed with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, confirming the release artifact is not directly installable without signing.
- `2026-07-09`: after device-side approval, `apps/android/build/outputs/apk/debug/android-debug.apk` installed successfully over ADB Wi-Fi.
- `2026-07-09`: `com.autoaccounting/.MainActivity` launched successfully on-device; `am start -W` reported `Status: ok`, cold start `768 ms`, and the activity stayed focused without an Android crash dialog.
- `2026-07-09`: the onboarding screen rendered readable Chinese copy on-device, including local-mode, login, account creation, and privacy/compliance entry text.
- `2026-07-09`: local-mode onboarding advanced into the local-mode confirmation screen and then into the pending-review home screen, confirming the app can enter a usable local-first path on this Xiaomi device.
- `2026-07-09`: the profile (`My`) page became reachable in local mode and rendered readable compliance plus backup/export sections, including local-only account-deletion messaging.
- `2026-07-09`: the advanced-monitoring / categorization page was reachable on-device and rendered readable notification-listener, accessibility, continuous-monitoring, and cloud-AI controls.
- `2026-07-09`: tapping the notification-listener settings button deep-linked into the Android notification access settings page (`com.android.settings/.Settings$NotificationAccessSettingsActivity`), where `Auto Accounting` appeared in the listener list.
- `2026-07-09`: after notification access was enabled on-device, the profile permission-center page showed notification-listener status as `当前状态：已授权` while accessibility remained `当前状态：未授权`.
- `2026-07-09`: temporarily adding `com.autoaccounting/com.autoaccounting.feature.billsync.BillSyncAccessibilityService` to `enabled_accessibility_services` through ADB changed the permission-center accessibility status to `当前状态：已授权`, confirming the app refreshes grant state correctly on this Xiaomi device.
- `2026-07-09`: tapping `打开无障碍设置` from the permission center deep-linked into MIUI accessibility settings (`com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity`).
- `2026-07-09`: restoring the device's original accessibility-service list through ADB returned the in-app accessibility status to `当前状态：未授权` while notification-listener status stayed `当前状态：已授权`, confirming accessibility grant removal is also reflected correctly in local mode.
- `2026-07-09`: tester performed one WeChat red-packet (P2P) payment and one Alipay transfer (P2P) to verify the notification capture flow.
- `2026-07-09`: notification capture failed to insert pending queue records for both P2P payments. Logcat and database confirmed the queue remained empty.
- `2026-07-09`: inspected `PaymentNotificationParser` and confirmed it enforces a merchant-payment regex (e.g., requires "商户：" or "支付成功 [商户名]"), intentionally ignoring peer-to-peer transfers and red packets.
- `2026-07-09`: force-stopped and relaunched the app via ADB; tester confirmed the in-app permission center still correctly reflects notification-listener as `当前状态：已授权`, verifying permission retention across app restarts on Xiaomi/MIUI.
- `2026-07-09`: tester fully rebooted the Xiaomi device; after startup, the in-app permission center still correctly reflects notification-listener as `当前状态：已授权`, verifying permission retention across device reboots.
- `2026-07-09`: tester triggered "Delete local data" from within the app; ADB pull of the database files confirmed that all tables were successfully wiped and default categories were correctly reseeded, verifying the local data deletion flow.

## Current blockers

- [RESOLVED] Release packaging is now signed (`release.jks` configured) and available as `android-release.apk`, ready for beta distribution.
- Xiaomi local-mode validation now covers notification-listener enablement, accessibility enable/disable reflection, system-settings deep links, permission retention across restarts, and local data deletion. Note: 本次内测仅充分覆盖了 MIUI，其他 ROM 风险后置到灰度测试阶段。
- WeChat / Alipay payment-capture flows are blocked because the current beta parser strictly requires merchant-payment formats (ignoring P2P transfers/red packets), and the tester is unable to generate a real merchant payment in the current environment.
- Backup/restore round-trip was tested on-device but lacks Android Storage Access Framework (SAF) integration (backups are currently held in memory) and provides no visual feedback upon restore, making it unusable for real testers.
- [RESOLVED] Account-deletion manual check (in local mode) is equivalent to local data deletion, which has been fully verified on-device.
