# Wire Continuous Monitoring Service Boundary And Guardrails

## What to build

Make continuous monitoring an advanced opt-in Android service path with clear start/stop controls, persisted enabled state, permission health, and strict payment-surface guardrails.

## Acceptance criteria

- [ ] Continuous monitoring can only start after required permission states are healthy and the user explicitly enables it.
- [ ] User can disable continuous monitoring at any time, and the service stops cleanly.
- [ ] Monitoring observes only payment-related WeChat/Alipay surfaces and never chat, messages, payment initiation, or transfers.
- [ ] Background keep-alive/auto-start guidance is visible without pretending to guarantee ROM behavior.
- [ ] Tests cover service state transitions, guardrail filtering, permission health mapping, and disabled-state behavior.

## Blocked by

- Issue 6: Wire Real Notification Listener Permission And Capture Path
- Issue 7: Wire User Started Bill Sync Permission And Service Path
