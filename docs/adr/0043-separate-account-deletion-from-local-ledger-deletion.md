# Separate account deletion from local ledger deletion

Account deletion will remove the cloud account, registered devices, cloud configuration, and AI categorization logs, while local ledger deletion requires separate confirmation on the device. This matches the local-first data model and prevents a cloud-account action from silently destroying the user's on-device bookkeeping history.
