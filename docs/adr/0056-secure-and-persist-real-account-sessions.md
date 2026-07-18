# Secure and persist real account sessions

The Android account flow will use the Ktor backend instead of a production `FakeAccountRepository`. The backend URL is injected at build time: Debug defaults to the emulator host and may use cleartext HTTP, while Release accepts only an explicitly configured HTTPS URL. A Release build without that URL keeps local mode available and reports the account service as unavailable.

Login, registration, recovery, SMS, session verification, current-session logout, and account-deletion operations use shared stable JSON contracts. Protected routes derive account identity only from `Authorization: Bearer`; form phone numbers and tokens never select the protected account. The backend stores SMS verification codes as keyed HMAC values and sessions as SHA-256 token hashes. The security migration clears old codes and sessions rather than retaining a plaintext compatibility path.

Android stores the phone number and bearer token together under Android Keystore AES-GCM, separately from Room, ledger backup, diagnostics, and UI state restoration. A random persisted installation UUID is used for rate limits and device registration; hardware identifiers are not read.

After restart, a restored session is verified in the background. A network or configuration failure keeps the user in offline-unverified mode with the local ledger available; only an explicit invalid-session response clears the encrypted session and returns to persistent local mode. Cloud writes and account-deletion operations remain paused until verification succeeds. Login state becomes active only after encrypted persistence succeeds, and logout clears local ciphertext only after the backend revokes the current session.

The server is the sole source of truth for account-deletion pending state and deadline. Final deletion first performs idempotent AI-log and cloud-configuration cleanup, then deletes the account, devices, and sessions. Cleanup failure retains the pending account for a later retry. Local ledger deletion remains a separate action and never clears or reallocates the account session.
