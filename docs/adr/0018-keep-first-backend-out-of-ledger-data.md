# Keep first backend out of ledger data

The first backend will handle accounts, registered devices, and non-ledger configuration, but will not store or sync ledger data. This supports the chosen full account system while preserving local-first ledger storage and delaying the privacy, conflict-resolution, and operational burden of cloud ledger data.
