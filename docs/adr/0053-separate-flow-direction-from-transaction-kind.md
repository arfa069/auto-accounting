# Separate flow direction from transaction kind

Ledger entries will store a positive amount and an independent flow direction of inflow, outflow, or neutral rather than deriving financial direction from transaction kind. This prevents transfers, red packets, repayments, and similar transaction kinds from being forced into incorrect income or expense totals while keeping their business meaning available for filtering and analysis.
