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
A transaction after it has been accepted into the user's ledger, either through manual entry or by confirming a pending entry.
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
A former ledger entry removed from the active ledger and reports, recoverable for 30 days before permanent deletion.
_Avoid_: Ignored entry, archived entry

**Ledger**:
The user's confirmed collection of ledger entries.
_Avoid_: History, statement

**Reports**:
Summaries and visual analysis of confirmed ledger entries.
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
A reusable payment method or money account used by a transaction, such as cash, Alipay balance, WeChat balance, a bank card, or Huabei. It may be reported by a payment source or created by the user.
_Avoid_: Balance account, asset account

**Encrypted Backup**:
A portable app data backup that is encrypted before it leaves the app sandbox and can later restore the user's ledger and settings.
_Avoid_: Export, archive

**User Account**:
The user's app identity used for authentication and future cloud-linked product capabilities.
_Avoid_: Device ID, profile

**Local Mode**:
An app state where the user can keep a local ledger without signing in, while cloud-linked capabilities remain unavailable.
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
The separate user-confirmed removal of ledger and app data stored on the Android device.
_Avoid_: Account deletion, logout

**Internal Beta**:
A feature-complete test release for controlled users before public store submission.
_Avoid_: MVP, prototype, store release

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

**Permission Center**:
A profile-area screen that shows notification access, accessibility access, AI consent, and related setup or troubleshooting actions.
_Avoid_: Permission tab, setup page

**Permission Health**:
The user-visible readiness state of permissions and device settings that affect capture, sync, AI categorization, or monitoring.
_Avoid_: Permission granted, setup status

**CSV Export**:
A plain-text spreadsheet-oriented export of ledger entries for user inspection and external analysis.
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
