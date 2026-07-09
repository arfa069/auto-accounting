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
- [x] `.\gradlew.bat --no-daemon :apps:android:assembleRelease` (2026-07-10 local internal-beta signing identity reconfigured; output passed v2 signature verification)

## Device verification

Status: In progress. One Xiaomi `24117RK2CC` (`zorn`) device was validated through ADB over Wi-Fi on Android 16 / SDK 36 / MIUI `V816`. This is useful MIUI evidence, but it is outside the planned Android 12-14 Xiaomi range. On 2026-07-10 the Release rebuild passed signing and v2 verification. After preserving the encrypted device backup and receiving explicit approval to clear app data, the Debug package was uninstalled and the Release package was installed successfully; a forced-stop cold start returned to `com.autoaccounting/.MainActivity`.

- [ ] Run the release artifact on the target Android 10-15 device matrix and record install / launch outcome.
- [ ] Verify notification listener permission, accessibility permission, and continuous monitoring enable/disable behavior on each available ROM family.
- [ ] Complete one WeChat and one Alipay payment capture flow per available device and record capture accuracy, duplicate behavior, and review ergonomics.
- [ ] Exercise backup/restore, local data deletion, and account deletion flows where the backend and tester setup allow it.
- [ ] Record screenshots, notes, or blocker reasons for any failed or skipped device scenario instead of leaving blanks.

### Matrix status on 2026-07-10

| ROM family | Planned Android versions | Status | Evidence or blocker |
| :--- | :--- | :--- | :--- |
| Xiaomi (MIUI / HyperOS) | 12 / 13 / 14 | Blocked | Android 16 / MIUI evidence exists, but no target-version device was available. The prior ADB Wi-Fi endpoint refused connections on 2026-07-10. |
| Huawei (EMUI / HarmonyOS) | 10 / 12 / 15 | Blocked | No controlled device or tester connection is available. |
| OPPO (ColorOS) | 11 / 13 / 14 | Blocked | No controlled device or tester connection is available. |
| vivo (OriginOS) | 12 / 14 / 15 | Blocked | No controlled device or tester connection is available. |
| Pixel / stock Android | 10 / 11 / 15 | Blocked | No controlled device or tester connection is available. |

### Manual-flow status

| Flow | Status | Evidence or blocker |
| :--- | :--- | :--- |
| Local mode, compliance, notification/accessibility deep links, permission retention, local data deletion | Pass with mixed Debug / Release evidence on Android 16 / MIUI only | Permission and local-deletion scenarios were verified on Debug before replacement; the fresh Release install and local-mode cold-start persistence also passed. This is not a substitute for the planned matrix. |
| WeChat / Alipay notification capture | Partial pass on Android 16 / MIUI Release | Alipay transfer notifications can enter the pending queue after enabling MIUI autostart and re-binding notification access. A first real capture parsed the wrong amount because the notification text contained multiple numeric values; the parser now prefers explicit currency markers and rejects ambiguous multi-number text, and the patched Release captured a later `0.01` yuan transfer correctly. Alipay merchant QR / tap-to-pay and WeChat payment flows still need a source path beyond system notifications when the apps keep payment messages inside their in-app message centers. |
| Backup / restore | Pass with caveat on Android 16 / MIUI Debug | Export created `Download/2026-07-10-02-50-ac-backup.bak`; SAF import restored a copied test payload successfully. `LocalDataBackupRepositoryTest` covers wrong-password failure before persisted data changes. Direct `.bak` selection and delete-then-restore remain unrecorded on-device. |
| Bill sync, continuous monitoring, review queue, ledger, reports, AI, account deletion | Blocked | No connected test device and, where applicable, no signed-in tester/backend scenario are available for this run. |

### Beta decision

**No-go for controlled beta distribution.** A current signed Release APK now installs and cold-starts on one Xiaomi Android 16 device, but the planned ROM/Android matrix is untested. Alipay notification capture has a partial real-device pass, but system-notification coverage is not enough for Alipay merchant QR / tap-to-pay or WeChat payment flows observed so far. Backup/restore has only a Debug-device partial pass. Capture accuracy, deduplication accuracy, and review efficiency cannot be calculated from the available evidence; permission retention has only Android 16 / MIUI restart and reboot evidence.

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
- `2026-07-10`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest :apps:android:assembleDebug` passed; the current debug APK was generated at `apps/android/build/outputs/apk/debug/android-debug.apk`.
- `2026-07-10`: the focused `CategorizationRulesScreenTest` passed, including the MediaStore export and SAF import flow exercised through the Compose activity-result launcher.
- `2026-07-10`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.capture.PaymentNotificationParserTest"` passed.
- `2026-07-10`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.settings.LocalDataBackupRepositoryTest"` passed, including wrong-passphrase failure before persisted data changes.
- `2026-07-10`: an earlier Release APK passed `apksigner verify --verbose` with one v2 signer; the latest rebuild failed at signing and no current Release APK remains available.
- `2026-07-10`: the Xiaomi endpoint `192.168.1.6:37145` connected over ADB Wi-Fi; device reported Android 16 / SDK 36 / MIUI `V816`.
- `2026-07-10`: latest `android-debug.apk` installed with `adb install -r`, launched successfully, and rendered readable onboarding/local-mode UI.
- `2026-07-10`: permission center showed notification listener `当前状态：已授权` and accessibility `当前状态：未授权`; notification settings opened `NotificationAccessSettingsActivity`, and accessibility settings opened MIUI `MiuiAccessibilitySettingsActivity`.
- `2026-07-10`: encrypted backup export created `Download/2026-07-10-02-50-ac-backup.bak` (626 bytes) and showed the success Snackbar.
- `2026-07-10`: SAF selected a copied test payload containing the exported backup; import completed with `备份已恢复成功`.
- `2026-07-10`: `:apps:android:assembleRelease` failed during `packageRelease` because `release.jks` could not be opened with the configured password; no current signed Release APK is available for installation.
- `2026-07-10`: after confirming local mode, force-stopping and cold-starting the app returned to the onboarding screen instead of the pending-review queue. This is tracked by Issue 15.
- `2026-07-10`: after implementing Issue 15, the latest Debug build retained local mode across force-stop and cold start on the Xiaomi device; the pending-review queue opened directly.
- `2026-07-10`: a new local internal-beta signing identity was configured; `:apps:android:assembleRelease` passed and `android-release.apk` passed `apksigner verify --verbose` with one v2 signer.
- `2026-07-10`: non-destructive `adb install -r` of the Release APK returned `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the device already has a differently signed Debug package; the existing app and its data were not changed.
- `2026-07-10`: copied `Download/2026-07-10-02-50-ac-backup.bak` from the device to a workspace-external backup and verified the 626-byte size before uninstalling the Debug package with explicit approval.
- `2026-07-10`: `android-release.apk` installed successfully; after force-stop, `am start -W` reported `Status: ok`, `LaunchState: COLD`, and `TotalTime: 230 ms`, with `com.autoaccounting/.MainActivity` as the resumed activity.
- `2026-07-10`: on the Release package, completed local-mode onboarding into `待确认队列`; a later force-stop and cold launch returned directly to `待确认队列` without the onboarding entry.
- `2026-07-10`: after Release replacement and parser calibration, MIUI reported `AutoStartManagerService` rejecting the notification listener until autostart was enabled and notification access was re-bound. After that, `PaymentNotificationListenerService` stayed Live.
- `2026-07-10`: a real Alipay test notification on the signed Release package entered the pending queue; the review screen showed `待确认 1`, `今日新增 1`, `疑似重复 0`, and no capture-failure log was recorded.
- `2026-07-10`: tester confirmed the captured Alipay transfer should have been `0.01` yuan but the pending card showed `5.00` yuan. Root cause: the notification amount parser selected the last numeric token, which can be a balance or unrelated number. `PaymentNotificationParser` now prefers explicit `¥` / `￥` / `元` amounts and rejects multiple unmarked numeric values; `:apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.capture.*"` passed.
- `2026-07-10`: after setting the local release signing alias in the current shell, `:apps:android:assembleRelease` passed again, `android-release.apk` passed v2 signature verification, and `adb install -r` installed the patched Release APK over the existing Release package. `PaymentNotificationListenerService` was still Live after install.
- `2026-07-10`: tester repeated a real Alipay `0.01` yuan transfer on the patched Release package. The listener stayed Live, the review screen changed to `待确认 2` / `今日新增 2`, and the newly captured entry displayed `¥0.01`; the previous incorrect `¥5.00` entry remained as historical test data.
- `2026-07-10`: the previous incorrect `¥5.00` pending entry was ignored on-device. The review screen then showed `待确认 1` / `今日新增 1`, with the remaining Alipay notification-captured entry displaying `¥0.01`; the listener still reported Live and no capture-failure log was present.
- `2026-07-10`: Android notification settings on the Xiaomi device show Alipay `10.8.70.8000` has `POST_NOTIFICATIONS` allowed and enabled channels including `朋友消息提醒` (`社交聊天、红包转账等消息`) and `交易与账号安全通知` (`支付交易、账单及账户安全等消息`). This proves the OS can receive Alipay transaction-class notifications when Alipay posts them, but it does not prove every payment surface posts a system notification.
- `2026-07-10`: Android notification settings show WeChat `8.0.71` has `POST_NOTIFICATIONS` allowed and enabled channels such as `新消息通知` and `其他通知`; no separate payment / wallet / transaction channel was visible from the OS notification-channel list. The tester also observed WeChat red packet, transfer, and QR payment flows did not post transaction details to the system notification shade.
- `2026-07-10`: platform constraint recorded for the capture strategy: Android `NotificationListenerService` only receives notifications after apps post them to the system. Payment messages kept only inside Alipay `消息 -> 消息盒子 -> 支付信息` or WeChat in-app surfaces are outside this notification-capture path and need bill-sync, accessibility, or another explicit integration path.
- `2026-07-10`: created Issue 16 to track the non-notification capture path for Alipay in-app payment messages and WeChat payment surfaces.

## Current blockers

- [RESOLVED] The Xiaomi Debug package was backed up, uninstalled with explicit approval, and replaced by the signed Release APK; cold-start launch passed.
- Xiaomi local-mode validation covers notification-listener enablement, accessibility enable/disable reflection, system-settings deep links, permission retention across restarts, and local data deletion on Android 16 / MIUI only. The device is connected again, but planned Android 12-14 Xiaomi coverage is still blocked.
- [PARTIAL PASS] Alipay real transfer notification capture works on the signed Xiaomi Release package after MIUI autostart and notification-listener rebind, and the patched parser captured a later real `0.01` yuan transfer correctly after the earlier `5.00` misread. Alipay merchant QR / tap-to-pay and WeChat payment flows may not emit usable system notifications; follow-up Issue 16 covers the non-notification capture path. See ADR 0049.
- [IMPLEMENTED, PARTIALLY VERIFIED] Backup/restore: encrypted backups are saved to `/Download` as `.bak` files; Debug-device export and SAF import succeeded, wrong-password safety has repository coverage, but direct `.bak` selection and delete-then-restore remain open. See ADR 0048.
- [RESOLVED] Account-deletion manual check (in local mode) is equivalent to local data deletion, which has been fully verified on-device.
- [RESOLVED] Local-mode confirmation now survives force-stop and cold start on the Xiaomi Debug and Release builds. See Issue 15.
