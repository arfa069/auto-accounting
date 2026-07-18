# Persist Backend Auth SMS And Registered Devices

## What to build

Move backend account registration, login, SMS verification, password recovery, token verification, and registered device state onto durable PostgreSQL-backed storage with safe provider boundaries.

## Acceptance criteria

- [x] Users, password credentials, SMS verification codes/limits, sessions/tokens, and registered devices are persisted through migrations.
- [x] Registration, login, account recovery, and token-protected routes work after backend restart.
- [x] SMS sending uses an environment-configured provider seam and fails safely when provider configuration is missing.
- [x] Login failure, SMS expiry, retry limits, and lockout behavior match the PRD without leaking whether a phone number exists.
- [x] Backend integration tests cover database persistence, auth flows, SMS limits, and token verification.
- [x] Verification codes use a server-secret HMAC, Session tokens are stored only as SHA-256 hashes, and the security migration clears legacy temporary credentials.
- [x] Protected routes derive identity from Bearer tokens; password recovery revokes old Sessions and current-session logout revokes only its token.

## Verification

- `.\gradlew.bat --no-daemon :services:backend:test --tests com.autoaccounting.backend.account.AccountServiceTest --tests com.autoaccounting.backend.account.AccountRoutesTest --tests com.autoaccounting.backend.account.AccountPersistenceTest`
- `.\gradlew.bat --no-daemon :services:backend:test`
- `.\gradlew.bat --no-daemon :services:backend:build`
- PostgreSQL production wiring is environment-driven through `AUTO_ACCOUNTING_DATABASE_URL`, `AUTO_ACCOUNTING_DATABASE_USER`, `AUTO_ACCOUNTING_DATABASE_PASSWORD`, `AUTO_ACCOUNTING_AUTH_PEPPER`, `AUTO_ACCOUNTING_SMS_PROVIDER=webhook`, `AUTO_ACCOUNTING_SMS_WEBHOOK_URL`, and `AUTO_ACCOUNTING_SMS_API_KEY`; automated persistence coverage uses H2 in PostgreSQL mode.
- Production account bootstrap fails fast without the database URL or an auth pepper of at least 32 characters; tests inject in-memory stores and a deterministic test hasher explicitly. Cloud configuration and AI writes require a verified Bearer token.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
