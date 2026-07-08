# Persist Account Deletion And Scheduled Cloud Cleanup

## What to build

Make account deletion durable end to end: request deletion, enter deletion pending state, pause cloud writes, allow cancellation during the cooling-off period, and execute scheduled deletion of cloud account data.

## Acceptance criteria

- [ ] Account deletion requests persist with cooling-off deadline and deletion pending state.
- [ ] Users can log in and cancel deletion during the cooling-off period.
- [ ] Cloud AI and device/config writes are paused while deletion is pending.
- [ ] Scheduled deletion removes account, registered devices, cloud configuration, and AI categorization logs.
- [ ] Tests cover request, cancel, write blocking, scheduled execution, and idempotent deletion behavior.

## Blocked by

- Issue 9: Persist Backend Auth SMS And Registered Devices
- Issue 10: Persist Backend Cloud Configuration And AI Proxy State
