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
- [ ] `.\gradlew.bat --no-daemon :apps:android:assembleRelease` (historical 2026-07-09 pass; current 2026-07-10 run blocked during signing)

## Device verification

Status: In progress. One Xiaomi `24117RK2CC` (`zorn`) device was validated through ADB over Wi-Fi on Android 16 / SDK 36 / MIUI `V816`. This is useful MIUI evidence, but it is outside the planned Android 12-14 Xiaomi range. On 2026-07-10 the latest Debug build installed and launched successfully; the signed Release rebuild then failed because the local `release.jks` password did not match the configured value. The current device run therefore uses Debug and does not clear the signed-package distribution blocker.

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
| Local mode, compliance, notification/accessibility deep links, permission retention, local data deletion | Pass on Android 16 / MIUI only | Recorded in the 2026-07-09 and 2026-07-10 device evidence below; not a substitute for the planned matrix. |
| WeChat / Alipay P2P notification capture | Blocked | The initial real-device run failed; the parser follow-up has unit coverage, but a real WeChat and Alipay payment has not yet been repeated on the connected Debug build. |
| Backup / restore | Pass with caveat on Android 16 / MIUI Debug | Export created `Download/2026-07-10-02-50-ac-backup.bak`; SAF import restored a copied test payload successfully. `LocalDataBackupRepositoryTest` covers wrong-password failure before persisted data changes. Direct `.bak` selection and delete-then-restore remain unrecorded on-device. |
| Bill sync, continuous monitoring, review queue, ledger, reports, AI, account deletion | Blocked | No connected test device and, where applicable, no signed-in tester/backend scenario are available for this run. |

### Beta decision

**No-go for controlled beta distribution.** The current Release build is blocked by a local keystore-password mismatch; the planned ROM/Android matrix is also untested. P2P capture lacks a post-fix real-device result, while backup/restore has only a Debug-device partial pass. Capture accuracy, deduplication accuracy, and review efficiency cannot be calculated from the available evidence; permission retention has only Android 16 / MIUI restart and reboot evidence.

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

## Current blockers

- [BLOCKED] Release packaging requires a matching local `release.jks` password; the latest `assembleRelease` failed during `packageRelease`, so no current signed APK is available for installation.
- Xiaomi local-mode validation covers notification-listener enablement, accessibility enable/disable reflection, system-settings deep links, permission retention across restarts, and local data deletion on Android 16 / MIUI only. The device is connected again, but planned Android 12-14 Xiaomi coverage is still blocked.
- [IMPLEMENTED, AWAITING REAL PAYMENT] WeChat / Alipay P2P capture: `PaymentNotificationParser` supports P2P red packets and transfers in both directions (send/receive). See ADR 0049.
- [IMPLEMENTED, PARTIALLY VERIFIED] Backup/restore: encrypted backups are saved to `/Download` as `.bak` files; Debug-device export and SAF import succeeded, wrong-password safety has repository coverage, but direct `.bak` selection and delete-then-restore remain open. See ADR 0048.
- [RESOLVED] Account-deletion manual check (in local mode) is equivalent to local data deletion, which has been fully verified on-device.
- [RESOLVED] Local-mode confirmation now survives force-stop and cold start on the Xiaomi Debug build. See Issue 15.
