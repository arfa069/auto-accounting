# Store ledger locally while reserving cloud sync

The app will store ledger data locally on the Android device as the primary source of truth, while keeping the product and data model open to future cloud sync. This avoids introducing account, server, and cross-device privacy risk in the first version, but prevents the design from assuming that ledger data can only ever live on one phone.
