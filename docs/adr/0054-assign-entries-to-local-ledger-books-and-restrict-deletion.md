# Assign entries to local ledger books and restrict destructive deletion

## Context

The local-first product previously treated confirmed entries as one undivided ledger and exposed funding-account creation only from a transaction form. Adding multiple named ledger books creates two durable risks: an entry can be written to the wrong book if selection changes during an asynchronous action, and deleting a book or shared funding account can orphan retained data.

## Decision

- Every active or soft-deleted ledger entry belongs to exactly one local ledger book through a non-null restricted foreign key.
- The current ledger-book ID is persisted. Manual creation and pending-entry confirmation capture their target ID before starting the write.
- A single-ledger installation migrates to a fixed-ID “默认账本”. Pending entries, ignored entries, deduplication, categories, and funding accounts remain global; active ledger queries, reports, CSV export, and recently deleted entries use the current book.
- Encrypted backup covers all ledger books and the current selection. Legacy supported backups map their entries to “默认账本”.
- The final ledger book cannot be deleted. Any active or soft-deleted entry makes a book non-empty and blocks deletion. Deleting the current empty book selects the earliest-created remaining book in the same transaction.
- Funding accounts are shared across books and preserve identity when edited. Hard deletion is allowed only when active/deleted ledger entries, pending entries, and ignored entries contain no reference; a blocked operation reports reference counts instead of clearing or rewriting those records.

## Consequences

- The app always has a valid ledger-book context and cannot silently redirect an in-flight write after the user switches books.
- Users get separate bookkeeping views without duplicating categories or funding accounts, while encrypted backup still represents one complete local snapshot.
- Ledger-book and funding-account deletion require transactional reference checks and explicit failure states, adding implementation and test complexity in exchange for preventing orphaned or silently altered financial records.
- This decision does not introduce cloud ledger sync, account balances, ledger renaming, or moving existing entries between books.
