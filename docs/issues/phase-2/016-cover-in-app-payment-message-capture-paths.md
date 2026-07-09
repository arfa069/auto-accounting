# Cover In-App Payment Message Capture Paths

## What to build

Design and implement a controlled capture path for payment records that WeChat or Alipay keep inside in-app message centers or bill pages instead of posting to the Android notification shade.

## Scope

- Investigate the currently observable Alipay paths such as `消息 -> 消息盒子 -> 支付信息` and bill/detail pages for merchant QR / tap-to-pay payments.
- Investigate the currently observable WeChat paths for red packets, transfers, QR payments, payment messages, and wallet/bill records.
- Prefer the existing user-started bill-sync and continuous-monitoring boundaries before adding new permissions or surfaces.
- Route parsed results through the existing review queue, dedupe, evidence, and confirmation flow.
- Keep all payment records as pending entries; do not auto-confirm or initiate any payment, transfer, refund, or message action.

## Non-goals

- Do not bypass Android permission prompts, app security boundaries, or WeChat/Alipay account protections.
- Do not scrape chats or unrelated messages.
- Do not treat notification capture as complete coverage for payment methods that only appear inside the source app.
- Do not upload raw payment evidence off-device unless the existing explicit AI/context consent path permits the exact payload.

## Target files or modules

- `apps/android/src/main/java/com/autoaccounting/feature/billsync`
- `apps/android/src/main/java/com/autoaccounting/feature/monitoring`
- `apps/android/src/main/java/com/autoaccounting/feature/review`
- `apps/android/src/test/java/com/autoaccounting/feature/billsync`
- `apps/android/src/test/java/com/autoaccounting/feature/monitoring`
- `docs/INTERNAL-BETA-RELEASE.md`
- `docs/issues/phase-2/014-execute-internal-beta-device-matrix-and-capture-findings.md`

## Acceptance criteria

- [ ] Alipay merchant QR / tap-to-pay records visible in the in-app message or bill surface can be captured into the pending queue after explicit user action or opt-in monitoring.
- [ ] WeChat red packet, transfer, and QR payment records visible in supported in-app payment surfaces can be captured into the pending queue after explicit user action or opt-in monitoring.
- [ ] Unsupported, hidden, or account-protected surfaces fail with a clear in-app status and do not create partial or guessed entries.
- [ ] Capture guardrails exclude chats, unrelated messages, payment initiation screens, and transfer-send flows.
- [ ] Dedupe behavior handles overlap between notification capture and in-app bill/message capture without creating duplicate confirmed ledger entries.
- [ ] Tests cover parser samples, unsupported-page failure, guardrail filtering, dedupe handoff, and review queue persistence.

## Acceptance tests

- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.billsync.*"`
- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.monitoring.*"`
- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.review.*"`
- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`

## Manual verification

1. On a controlled Android test device, grant only the permissions needed by the selected user-started sync or monitoring path.
2. Complete one Alipay merchant QR or tap-to-pay transaction that appears in `消息盒子 -> 支付信息` but not the system notification shade.
3. Run the supported capture path and verify exactly one pending entry is created with the correct amount and source.
4. Complete one WeChat payment flow that does not post a usable system notification, then verify the supported in-app path can capture it or records a clear unsupported status.
5. Open unrelated chat/message pages and verify no entries are created.
6. Repeat a transaction already captured from notification and verify dedupe marks or merges it instead of producing a second confirmed ledger item.

## Rollback or safety notes

- Keep any new accessibility or monitoring behavior behind explicit user action or opt-in settings.
- Prefer narrow page/surface allowlists over broad text scanning.
- If app UI structure changes or a source app blocks the observed path, fail closed and keep the issue status partial rather than weakening guardrails.

## Verification record

- `2026-07-10`: Issue 14 real-device validation found that Alipay transfers can post system notifications, but Alipay merchant QR / tap-to-pay and observed WeChat payment flows may keep payment records only inside app message or bill surfaces. This issue tracks the follow-up non-notification capture path.

## Discovered by

- Issue 14: Execute Internal Beta Device Matrix And Capture Findings

## Blocked by

- Issue 7: Wire User Started Bill Sync Permission And Service Path
- Issue 8: Wire Continuous Monitoring Service Boundary And Guardrails
