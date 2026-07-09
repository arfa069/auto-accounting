# ADR 0049: Expand Notification Parser for P2P Transactions

## Status

Accepted

## Context

During internal beta validation (Issue 14), the notification capture pipeline failed to capture WeChat red packets and Alipay peer-to-peer transfers. Investigation confirmed that `PaymentNotificationParser.extractMerchantTitle()` only matches merchant-format notifications (e.g., "商户：xxx"), silently dropping all P2P notifications.

## Decision

Expand `PaymentNotificationParser` to handle P2P (peer-to-peer) notifications in both directions:

- **Incoming**: "收到xxx的红包", "收到xxx的转账", "xxx向你转账" → classified as income, counterparty name extracted.
- **Outgoing**: "发出红包", "红包已发出", "转账给xxx", "向xxx转账" → classified as expense, counterparty or "红包" as title.
- **Fallback**: When a valid payment source + amount + transaction kind are detected but no counterparty can be extracted, use "未知来源" as the merchant title instead of dropping the notification entirely.

The original merchant-payment regex patterns retain highest priority and are not affected.

## Consequences

- The parser becomes more permissive: some non-payment P2P notifications from WeChat/Alipay could potentially be captured. This is acceptable during internal beta as entries go through the review queue before reaching the ledger.
- P2P notification formats vary across app versions and ROM vendors. The regex patterns cover common variants observed on Xiaomi/MIUI but may need calibration after wider device testing.
- Six new test cases cover the expanded scenarios and protect against regressions.
- Later Issue 14 validation found notification capture is only a partial source: Alipay transfer notifications can be captured, but Alipay merchant QR / tap-to-pay and observed WeChat payment flows may keep records inside in-app message or bill surfaces instead of Android notifications. Issue 16 tracks that non-notification capture path.
