# Wire Continuous Monitoring Service Boundary And Guardrails

## What to build

Make continuous monitoring an advanced opt-in Android service path with clear start/stop controls, persisted enabled state, permission health, and strict payment-surface guardrails.

## Acceptance criteria

- [x] Continuous monitoring can only start after required permission states are healthy and the user explicitly enables it.
- [x] User can disable continuous monitoring at any time, and the service stops cleanly.
- [x] Monitoring observes only payment-related WeChat/Alipay surfaces and never chat, messages, payment initiation, or transfers.
- [x] Background keep-alive/auto-start guidance is visible without pretending to guarantee ROM behavior.
- [x] Tests cover service state transitions, guardrail filtering, permission health mapping, and disabled-state behavior.

## Blocked by

- Issue 6: Wire Real Notification Listener Permission And Capture Path
- Issue 7: Wire User Started Bill Sync Permission And Service Path

## Verification

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests com.autoaccounting.feature.monitoring.ContinuousMonitoringStateTest --tests com.autoaccounting.feature.categorization.CategorizationRulesScreenTest --tests com.autoaccounting.feature.review.ReviewQueueScreenTest --tests com.autoaccounting.feature.billsync.BillSyncSessionTest --tests com.autoaccounting.feature.billsync.BillSyncCaptureProcessorTest`
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests com.autoaccounting.feature.review.ReviewQueuePersistenceTest --tests com.autoaccounting.feature.monitoring.ContinuousMonitoringStateTest --tests com.autoaccounting.feature.categorization.CategorizationRulesScreenTest --tests com.autoaccounting.feature.review.ReviewQueueScreenTest`
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest :apps:android:assembleDebug`

## Device verification

Status: Partial Xiaomi validation completed on 2026-07-13; payment-surface and deny-list scenarios below remain separate acceptance work.

- [ ] Enable notification listener and bill-sync accessibility permissions from the profile permission center.
- [ ] Enable continuous monitoring and confirm payment-history or bill surfaces create/merge pending entries.
- [ ] Open WeChat/Alipay chat, message, payment initiation, and transfer surfaces and confirm no pending entries are created.
- [ ] Disable continuous monitoring and confirm later WeChat/Alipay accessibility events are ignored.
- [x] Review background keep-alive/auto-start guidance on a target domestic ROM without treating it as guaranteed behavior. Xiaomi Android 16 / MIUI `V816` opened the expected application-details and auto-start pages; battery optimization and battery saver also opened their corresponding system settings.

Rebinding finding: ordinary app-process recovery retained accessibility enablement and binding. MIUI force-stop removed the service from `enabled_accessibility_services`, so relaunch alone could not restore it and a real user reauthorization was required. The application no longer treats `BillSyncAccessibilityService.onInterrupt()` as an unbind or cancels its heartbeat there; `onDestroy()` remains the real disconnect boundary. A lifecycle regression test and Xiaomi Release verification confirm the page no longer reports “无障碍服务未连接” after the system reconnects. UIAutomator dump itself temporarily recreates accessibility services on this ROM, so use a normal screenshot for the final UI assertion.
