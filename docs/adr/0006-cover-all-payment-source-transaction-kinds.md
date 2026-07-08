# Cover all payment-source transaction kinds

The MVP will attempt to capture every transaction kind exposed by WeChat and Alipay bill pages, not only expense, income, and refunds. This increases parser, deduplication, and review-interface complexity, but prevents the product from silently dropping financially important activity such as transfers, red packets, repayments, investment movements, fees, and other source-specific events.
