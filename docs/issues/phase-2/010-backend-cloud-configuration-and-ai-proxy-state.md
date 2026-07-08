# Persist Backend Cloud Configuration And AI Proxy State

## What to build

Make cloud configuration and AI categorization provider integration durable and provider-ready while preserving the product boundary that the backend does not store the user's full ledger.

## Acceptance criteria

- [ ] Cloud configuration persists AI consent, enhanced AI context preference, feature flags, and device/account settings needed by the app.
- [ ] AI categorization requests are routed through an environment-configured backend provider seam with safe missing-config behavior.
- [ ] AI categorization logs are persisted for internal beta without storing full local ledger data.
- [ ] Android/backend contract tests cover consent/config reads and AI categorization request/response payloads.
- [ ] Secret scanner or equivalent check confirms provider keys are not committed or shipped in client code.

## Blocked by

- Issue 9: Persist Backend Auth SMS And Registered Devices
