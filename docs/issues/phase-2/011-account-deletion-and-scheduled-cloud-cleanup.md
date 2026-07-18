# Persist Account Deletion And Scheduled Cloud Cleanup

## What to build

Make account deletion durable end to end: request deletion, enter deletion pending state, pause cloud writes, allow cancellation during the cooling-off period, and execute scheduled deletion of cloud account data.

## Acceptance criteria

- [x] Account deletion requests persist with cooling-off deadline and deletion pending state.
- [x] Users can log in and cancel deletion during the cooling-off period.
- [x] Cloud AI and device/config writes are paused while deletion is pending.
- [x] Scheduled deletion removes account, registered devices, cloud configuration, and AI categorization logs.
- [x] Tests cover request, cancel, write blocking, scheduled execution, cleanup failure retention, retry, and idempotent deletion behavior.

## Verification

- Protected deletion routes use the current Bearer Session; submitted phone numbers cannot select another account.
- A non-pending status returns a successful `pending=false` contract instead of a business error.
- Final deletion first performs idempotent AI-log and cloud-configuration cleanup. The account, devices, and Sessions are deleted only after both cleanups succeed; a failure retains the pending account for the next run.
- `./gradlew.bat --no-daemon :services:backend:test --tests "com.autoaccounting.backend.account.*" --tests "com.autoaccounting.backend.config.*" --tests "com.autoaccounting.backend.ai.*"`

## Blocked by

- Issue 9: Persist Backend Auth SMS And Registered Devices
- Issue 10: Persist Backend Cloud Configuration And AI Proxy State
