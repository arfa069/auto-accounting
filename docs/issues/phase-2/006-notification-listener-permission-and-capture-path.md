# Wire Real Notification Listener Permission And Capture Path

## What to build

Connect notification listener permission state, settings deep-link, payment notification parsing, deduplication, and pending-entry creation into one device-testable path for WeChat and Alipay payment notifications.

## Acceptance criteria

- [ ] Permission center reflects real Android notification listener access state and provides a settings deep-link.
- [ ] Notification listener ignores unrelated apps/content and only processes payment-source payment notifications.
- [ ] Parsed WeChat/Alipay payment notifications create or merge pending entries through the same capture pipeline used by the review queue.
- [ ] Capture reason, confidence state, source evidence, and duplicate handling are visible in review.
- [ ] Tests cover permission-state mapping, parser behavior, unrelated notification rejection, and pending-entry creation.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
