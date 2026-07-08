# Persist Backend Auth SMS And Registered Devices

## What to build

Move backend account registration, login, SMS verification, password recovery, token verification, and registered device state onto durable PostgreSQL-backed storage with safe provider boundaries.

## Acceptance criteria

- [ ] Users, password credentials, SMS verification codes/limits, sessions/tokens, and registered devices are persisted through migrations.
- [ ] Registration, login, account recovery, and token-protected routes work after backend restart.
- [ ] SMS sending uses an environment-configured provider seam and fails safely when provider configuration is missing.
- [ ] Login failure, SMS expiry, retry limits, and lockout behavior match the PRD without leaking whether a phone number exists.
- [ ] Backend integration tests cover database persistence, auth flows, SMS limits, and token verification.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
