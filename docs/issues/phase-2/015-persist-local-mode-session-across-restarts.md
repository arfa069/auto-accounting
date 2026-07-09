# Persist Local-Mode Session Across App Restarts

## What to build

Persist a user's confirmed local-mode session so reopening the app returns directly to the pending-review queue instead of requiring onboarding again.

## Scope

- Persist only the non-sensitive fact that local mode was confirmed.
- Restore `AccountSession.LocalMode` before rendering the account flow on app startup.
- Preserve the existing explicit local-mode confirmation and agreement gate for first-time users.
- Keep account tokens, passwords, verification codes, and phone numbers out of this local session flag.

## Non-goals

- Do not make signed-in session persistence part of this issue.
- Do not change local-data deletion, account deletion, or cloud-auth behavior.
- Do not bypass onboarding for a user who has never confirmed local mode.

## Target files or modules

- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `apps/android/src/main/java/com/autoaccounting/feature/account`
- Existing non-sensitive local preferences persistence boundary, or a narrowly scoped equivalent
- `apps/android/src/test/java/com/autoaccounting/feature/account`

## Acceptance criteria

- [x] After agreeing to and confirming local mode, force-stopping and relaunching the app opens the pending-review queue without showing the onboarding screen.
- [x] A first launch still requires agreement acceptance and explicit local-mode confirmation before entering the queue.
- [x] Local-mode persistence survives process restart without persisting credentials or tokens.
- [x] Existing local ledger, review queue, preferences, and permission behavior remain intact.
- [x] Tests cover first launch, local-mode confirmation, store reconstruction, and restoration after process restart.

## Acceptance tests

- [x] `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"`
- [x] `.\gradlew.bat :apps:android:testDebugUnitTest`

## Manual verification

1. Clear app data on a controlled test device and launch the app.
2. Accept the agreement and confirm local mode.
3. Force-stop the app, launch it again, and verify that the pending-review queue opens directly.
4. Verify that notification-listener status and local data remain available after the restart.

## Rollback or safety notes

- Persist only a boolean or equivalent non-sensitive local-mode marker; never store account credentials in this path.
- A first-launch fallback must remain signed out until the user explicitly chooses local mode or completes account authentication.

## Verification record

- `2026-07-10` before implementation: on Xiaomi `24117RK2CC` (Android 16 / MIUI `V816`), local-mode confirmation followed by `am force-stop` and a cold `MainActivity` launch displayed `继续使用本地模式` instead of `待确认队列`.
- `2026-07-10`: `LocalModeSessionStoreTest` passed, confirming the local-mode marker survives store recreation.
- `2026-07-10`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"` passed.
- `2026-07-10`: `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest :apps:android:assembleDebug` passed.
- `2026-07-10`: after installing the latest Debug APK on the Xiaomi device, confirming local mode showed `待确认队列`; a subsequent force-stop and cold launch also showed `待确认队列` without `继续使用本地模式`.
- `2026-07-10`: after replacing the Debug package with the signed Release APK and completing fresh local-mode onboarding, a force-stop and cold launch also returned directly to `待确认队列`.

## Discovered by

- Issue 14: Execute Internal Beta Device Matrix And Capture Findings
