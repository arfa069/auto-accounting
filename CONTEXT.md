# Auto Accounting

This context describes the user's personal finance records captured from mobile payment activity.

## Language

**Transaction**:
A money movement that should appear in the user's ledger, such as a payment, refund, transfer, or income.
_Avoid_: Bill, message, notification

**Payment Source**:
A user-visible external app or channel assigned to a transaction. It can be corrected after capture, and a manual entry may have no payment source.
_Avoid_: Platform, provider

**Original Capture Source**:
The external app or channel recorded when an automatically captured transaction first entered the app, retained as immutable provenance even if its payment source is corrected.
_Avoid_: Payment source, entry origin

**Entry Origin**:
The way a transaction entered the app, such as manual entry, notification capture, bill sync, or duplicate merge.
_Avoid_: Payment source, funding account

**Ledger Entry**:
A transaction after it has been accepted into one of the user's ledger books, either through manual entry or by confirming a pending entry.
_Avoid_: Record, item

**Manual Entry**:
A user-authored transaction accepted directly as a ledger entry without passing through the review queue or requiring a payment source.
_Avoid_: Manual pending entry, draft entry

**Pending Entry**:
A captured transaction candidate that still needs user confirmation, correction, or deduplication before becoming a ledger entry.
_Avoid_: Draft, raw transaction

**Review Queue**:
The user's primary workflow for resolving pending entries into ledger entries or dismissing them.
_Avoid_: Inbox, task list

**Ignored Entry**:
A pending entry dismissed from the review queue without becoming a ledger entry, recoverable for a limited time.
_Avoid_: Deleted entry, archived transaction

**Deleted Ledger Entry**:
A former ledger entry removed from its ledger book's active list and reports, recoverable in that same ledger book for 30 days before permanent deletion.
_Avoid_: Ignored entry, archived entry

**Ledger**:
The product area that displays and manages the current ledger book.
_Avoid_: History, statement, all local data

**Ledger Book**:
A named local collection of ledger entries. Every ledger entry belongs to exactly one ledger book, while categories and funding accounts are shared across all ledger books.
_Avoid_: Category, funding account, cloud ledger

**Current Ledger Book**:
The persisted ledger-book selection that receives manual entries and confirmed pending entries and scopes ledger lists, reports, CSV export, and recently deleted entries.
_Avoid_: Default category, review queue, encrypted-backup scope

**Reports**:
Summaries and visual analysis of confirmed entries in the current ledger book.
_Avoid_: Dashboard, analytics

**Duplicate Candidate**:
Two or more captured transaction candidates that may describe the same real-world transaction.
_Avoid_: Conflict, repeated record

**Merged Entry**:
A pending entry or ledger entry formed by combining duplicate candidates that describe the same real-world transaction.
_Avoid_: Deleted duplicate, overwritten record

**Transaction Kind**:
The business nature of a transaction, such as purchase, refund, transfer, red packet, repayment, investment, fee, or another type exposed by a payment source. It does not determine whether money flows in or out.
_Avoid_: Flow direction, category, tag

**Flow Direction**:
The effect of a transaction on income and expense totals: inflow, outflow, or neutral. Amounts remain positive, and neutral entries do not affect income, expense, or net totals.
_Avoid_: Transaction kind, signed amount

**Category**:
The user's purpose-based label for a ledger entry, such as food, transport, shopping, housing, healthcare, or travel.
_Avoid_: Transaction kind, source type

**Funding Account**:
A reusable payment method or money account used by a transaction, such as cash, Alipay balance, WeChat balance, a bank card, or Huabei. It may be reported by a payment source or created by the user, is shared across ledger books, and can be deleted only when no active/deleted ledger entry, pending entry, or ignored entry references it.
_Avoid_: Balance account, asset account

**Encrypted Backup**:
A portable app data backup that is encrypted before it leaves the app sandbox and can later restore all ledger books, their entries, shared local data, settings, and the current ledger-book selection.
_Avoid_: Export, archive

**Data and Backup**:
A profile-area entry for current-ledger CSV export and all-ledger encrypted-backup export or import. Local Data Deletion is a separately protected, destructive action at the bottom of this entry.
_Avoid_: Account management, cloud sync

**User Account**:
The user's app identity used for authentication and future cloud-linked product capabilities.
_Avoid_: Device ID, profile

**Account Management**:
The first profile-area entry for viewing the current account state, signing in or registering from local mode, and managing account deletion after sign-in.
_Avoid_: Profile, local-data settings

**Local Mode**:
An app state where the user can keep local ledger books without signing in, while cloud-linked capabilities remain unavailable.
_Avoid_: Guest account, anonymous account

**Registered Device**:
A user's Android device known to the backend for account security, configuration, and future sync readiness.
_Avoid_: Client, install

**Cloud Configuration**:
Non-ledger account settings stored by the backend, such as consent state, feature flags, and future sync readiness.
_Avoid_: Cloud ledger, remote backup

**Account Deletion**:
The user-requested removal of cloud account identity, registered devices, cloud configuration, and AI categorization logs.
_Avoid_: Logout, uninstall

**Deletion Pending**:
The account state during the cooling-off period after a user requests account deletion and before cloud data is removed.
_Avoid_: Disabled account, logout

**Account Recovery**:
The user flow for regaining account access by verifying the phone number and setting a new password.
_Avoid_: SMS login, customer support reset

**Local Data Deletion**:
The separate user-confirmed removal of all ledger books and app data stored on the Android device, including diagnostic segments, their Keystore key, and the Release diagnostic preference, followed by recreation of one empty default ledger book. Diagnostic exports already written to Downloads remain outside this operation.
_Avoid_: Account deletion, logout

**Internal Beta**:
A feature-complete test release for controlled users before public store submission.
_Avoid_: MVP, prototype, store release

**Developer Tools**:
Debug-build-only tools for internal-beta readiness, device matrices, permission retention, and quality metrics. They are not available in release builds or the normal user-facing profile area, and they are distinct from the user-controlled Diagnostic Logs entry.
_Avoid_: User settings, hidden release entry

**Diagnostic Logs**:
A user-controlled Compliance and Privacy entry for encrypted, on-device troubleshooting events. It is available in Debug and Release, defaults on only in Debug, never uploads logs, never stores screenshots, and always redacts authentication secrets before writing or exporting sensitive transaction context.
_Avoid_: Developer Tools, Logcat, crash reporting, ledger evidence

**Ledger Entry Debug Metadata**:
Persisted lifecycle and capture-provenance fields such as entry origin, creation or first-confirmation time, last-modified time, original capture source, original pending-entry ID, and capture evidence. The current ledger UI does not compose these fields; editing user-visible transaction fields must preserve them.
_Avoid_: Normal transaction details, centralized transaction log

**Store Compliance Package**:
The permission explanations, privacy policy, review materials, screenshots, videos, and declarations needed for public app-store submission.
_Avoid_: Legal copy, app description

**Privacy Policy**:
The complete user-facing document that explains personal information processing, rights, retention, sharing, and contact channels.
_Avoid_: Permission copy, compliance checklist

**Personal Information Collection List**:
A structured user-facing list of personal information categories collected, processing purpose, processing method, and whether each item is required.
_Avoid_: Privacy policy, data map

**Sensitive Transaction Information**:
Transaction, bill, merchant, amount, category, and funding-account information that is treated as sensitive personal information because it can reveal financial status, habits, and movement patterns.
_Avoid_: Ordinary ledger data, analytics data

**Third-Party Service List**:
A structured user-facing list of SDKs, cloud services, SMS providers, AI providers, analytics, and other third parties that may process user information.
_Avoid_: Dependency list, vendor list

**Capture Accuracy**:
How reliably the app converts real WeChat and Alipay payment activity into pending entries.
_Avoid_: Parser accuracy, notification accuracy

**Deduplication Accuracy**:
How reliably the app merges duplicate candidates without merging unrelated transactions.
_Avoid_: Match score, duplicate rate

**Review Efficiency**:
How quickly and comfortably users can resolve pending entries into ledger entries.
_Avoid_: Confirmation speed, task completion

**Capture Reason**:
The user-visible explanation for why a pending entry was created or matched, such as notification capture, bill sync, or duplicate merge.
_Avoid_: Debug reason, parser trace

**Confidence State**:
The user-visible trust level of a captured or suggested value, such as high confidence, needs review, or duplicate suspect.
_Avoid_: Score, probability

**Permission Retention**:
How consistently users keep notification and accessibility permissions enabled after onboarding.
_Avoid_: Permission conversion, activation rate

**Progressive Onboarding**:
An onboarding flow that introduces account setup, permissions, and optional features in stages as users reach the relevant workflow.
_Avoid_: Setup wizard, first-run checklist

**Playful Copy**:
Cute, character-led product language used to make bookkeeping feel lighter and friendlier.
_Avoid_: Formal copy, system message

**Companion Character**:
A fixed in-app character that appears across onboarding, review, reports, permissions, and confirmation flows.
_Avoid_: Mascot, decoration

**Animal Companion**:
A cute animal-style companion character used as the app's recurring guide and brand personality.
_Avoid_: Abstract assistant, human assistant

**Cat Companion**:
The app's recurring animal companion, using a cat-like personality and visual direction.
_Avoid_: Generic animal, pet theme

**Auto Bookkeeping**:
A profile-area entry for enabling and maintaining automatic capture. It contains notification access, accessibility access, non-blocking background-reliability guidance, and continuous-monitoring health. Bookkeeping-result notifications are requested when needed after enabling rather than shown as a separate setting; user-started bill import remains on the Review Queue.
_Avoid_: Permission center, permission tab

**Permission Center**:
A compact section within Auto Bookkeeping that shows notification access, accessibility access, and non-blocking background-running, auto-start, battery-optimization, and battery-saver guidance.
_Avoid_: Profile-area entry, permission tab

**Permission Health**:
The user-visible readiness state of permissions and device settings that affect capture, sync, AI categorization, or monitoring.
_Avoid_: Permission granted, setup status

**CSV Export**:
A plain-text spreadsheet-oriented export of the current ledger book for user inspection and external analysis.
_Avoid_: Backup, sync

**Categorization Rule**:
A user-visible rule that assigns a category when a transaction matches known merchant, title, source, amount, or transaction-kind patterns.
_Avoid_: Hidden heuristic, classifier

**AI Categorization**:
A category suggestion produced by an AI model when rules are absent, weak, or conflicting.
_Avoid_: Auto bookkeeping, smart ledger

**AI Categorization Consent**:
The user's explicit opt-in that allows selected transaction fields to be used for cloud-based AI categorization.
_Avoid_: AI setting, smart mode

**Enhanced AI Context**:
Additional transaction context that the user may choose to share for better AI categorization accuracy beyond the default minimal fields.
_Avoid_: Full data upload, accuracy mode

Enhanced AI Context can be enabled only after AI Categorization Consent. Turning off AI Categorization revokes Enhanced AI Context; enabling AI again starts with the default minimal fields.

**AI Categorization Log**:
A backend-retained record of AI categorization requests and outcomes used to improve categorization quality.
_Avoid_: Cloud ledger, synced transaction

**Bill Sync**:
A user-started import flow that reads payment-source bill pages to capture missed or historical transactions.
_Avoid_: Crawl, scrape, scan

**Sync Step**:
A user-visible stage in bill sync, such as opening the payment source, reading bills, parsing, deduplicating, or creating pending entries.
_Avoid_: Job state, log line

**Continuous Monitoring**:
An optional advanced mode that keeps observing payment-related activity after the user enables the required permissions.
_Avoid_: Background scraping, auto crawl
