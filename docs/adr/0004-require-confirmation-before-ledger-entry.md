# Require confirmation before ledger entry

Automatically captured transactions from notifications or bill sync will become pending entries first, not ledger entries. This protects the ledger from duplicate captures, incomplete merchant names, refunds, transfers, and other ambiguous payment-source events until the user confirms or corrects them.
