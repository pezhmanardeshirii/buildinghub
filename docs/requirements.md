# BuildingHub — Requirements

> Status: Phase 1 (proposed, awaiting approval)
> Currency unit: **IRR (Rial)**, integer. Display may render Toman (= IRR / 10).
> Time: stored in UTC (`timestamptz`); Jalali used for presentation and for defining billing periods.

---

## 1. Purpose and Scope

BuildingHub is a multi-tenant SaaS platform for managing residential buildings and complexes in Iran.
It serves two mobile audiences (Manager app, Resident app) through one versioned REST API.

In scope for v1:

- Identity via Iranian mobile number + OTP.
- Multiple buildings per user, with per-building roles.
- Units, ownership history, tenancy history.
- Monthly charge generation with configurable strategies.
- Double-entry building ledger, expenses, receipts, reports.
- Online payments through an abstracted Iranian gateway.
- Maintenance requests, announcements, shared-facility reservations, meetings and voting, notifications.
- Platform-level subscription billing for BuildingHub itself (separate ledger).

Out of scope for v1 (documented to prevent scope creep):

- Microservices, message broker, event sourcing infrastructure.
- Native payment gateway production credentials; only the abstraction, a mock, and one documented adapter seam.
- Accounting for VAT/tax filings, payroll, or external accounting-system integration.
- Web admin console (API + Swagger only; a console is a later phase).
- Offline-first mobile sync, push-notification delivery guarantees beyond best-effort + in-app record.
- Bank-statement import / automatic reconciliation matching (manual reconciliation only).

---

## 2. Personas

| Persona | Description | Primary surface |
|---|---|---|
| Platform admin | Operates BuildingHub SaaS, manages plans and buildings' subscription status | API (Phase later: console) |
| Building manager | Runs one or more buildings end to end | Manager app |
| Accountant | Handles charges, expenses, payments for granted buildings | Manager app (restricted) |
| Owner | Owns one or more units; sees financials of those units | Resident app |
| Tenant | Occupies a unit under a current lease | Resident app |
| Resident | Household member / occupant without ownership (e.g. family member) | Resident app |

---

## 3. Glossary

| Term | Meaning |
|---|---|
| Building | A managed property: single apartment building or a complex. Tenant boundary of the business domain. |
| Membership | Link between a user and a building carrying roles and permissions. |
| Unit | An independently billed/owned space inside a building. |
| Occupancy | A period during which a user occupies a unit as tenant or resident. |
| Ownership | A period during which a user holds a share of a unit. |
| Charge period | One billing cycle (default: one Jalali month) for a building. |
| Unit charge | An amount owed by a unit for a charge period. Frozen once generated. |
| Allocation | The link that applies a successful payment to one or more unit charges. |
| Journal entry | Immutable double-entry posting. |
| SaaS subscription | BuildingHub's own billing of a building — never part of the building ledger. |

---

## 4. Functional Requirements

Requirement IDs are stable and referenced by tests and the implementation plan.

### 4.1 Authentication (AUTH)

| ID | Requirement |
|---|---|
| FR-AUTH-01 | Register/identify a user by Iranian mobile number, normalized to `+98XXXXXXXXXX`. |
| FR-AUTH-02 | Issue a 6-digit OTP with a configurable TTL (default 120 s) and a single active OTP per phone. |
| FR-AUTH-03 | Limit verification attempts per OTP (default 5) and invalidate the OTP after exhaustion. |
| FR-AUTH-04 | Rate-limit OTP requests per phone and per IP (default 3/phone/10 min, 20/IP/hour). |
| FR-AUTH-05 | Issue a short-lived JWT access token (default 15 min) and a rotating refresh token (default 30 days). |
| FR-AUTH-06 | Refresh token rotation with reuse detection: replaying a rotated token revokes the whole token family. |
| FR-AUTH-07 | Logout revokes the presented refresh token; "logout all devices" revokes the family(s). |
| FR-AUTH-08 | Store refresh tokens and OTPs only as hashes; never in plaintext. |
| FR-AUTH-09 | OTP delivery goes through `OtpSender`; the dev implementation is enabled only under the `dev` profile. |
| FR-AUTH-10 | Unauthenticated responses must not reveal whether a phone number is registered. |

### 4.2 User (USER)

| ID | Requirement |
|---|---|
| FR-USER-01 | Profile with first name, last name, optional national ID, optional email, avatar reference. |
| FR-USER-02 | National ID is unique when present and validated against the Iranian check digit. |
| FR-USER-03 | Phone number is immutable after registration; changing it requires a separate verified flow (out of v1, documented). |
| FR-USER-04 | Users can list the buildings they are a member of, with the roles held in each. |

### 4.3 Building (BLD)

| ID | Requirement |
|---|---|
| FR-BLD-01 | Create a building (name, address, floors, total units, city, postal code, geo point optional). |
| FR-BLD-02 | Assign/replace building managers; a building may have more than one manager. |
| FR-BLD-03 | Building status: `DRAFT`, `ACTIVE`, `SUSPENDED`, `ARCHIVED`. Only `ACTIVE` allows business operations. |
| FR-BLD-04 | `total_units` is derived from actual units; the stored value must stay consistent (validated + recomputed). |
| FR-BLD-05 | A building belongs to exactly one tenant scope (its own `building_id`) — never shared. |
| FR-BLD-06 | Archiving is soft; historical financial data remains readable and immutable. |

### 4.4 Membership and authorization (MEM)

| ID | Requirement |
|---|---|
| FR-MEM-01 | A membership is unique per `(building_id, user_id)`. |
| FR-MEM-02 | Membership carries a set of roles: `BUILDING_MANAGER`, `ACCOUNTANT`, `OWNER`, `TENANT`, `RESIDENT`. |
| FR-MEM-03 | `PLATFORM_ADMIN` is a user-level role, not a membership role. |
| FR-MEM-04 | Accountants receive explicit permission grants (enum set) rather than unrestricted financial access. |
| FR-MEM-05 | `OWNER`/`TENANT` roles are granted and revoked automatically from ownership/occupancy records, and cannot be granted manually without a matching record. |
| FR-MEM-06 | Removing a membership revokes access immediately (no token-scoped caching beyond the request). |
| FR-MEM-07 | Every building-scoped operation validates membership + required permission server-side. |

### 4.5 Units (UNIT)

| ID | Requirement |
|---|---|
| FR-UNIT-01 | CRUD units: number, floor (may be negative for basements), area m², resident count, parking list, storage list, status. |
| FR-UNIT-02 | Unit number is unique per building (case-insensitive, trimmed, Persian/Arabic digits normalized). |
| FR-UNIT-03 | Unit status: `ACTIVE`, `VACANT`, `UNDER_RENOVATION`, `INACTIVE`. Only `ACTIVE` units are charged by default. |
| FR-UNIT-04 | Area must be > 0 and ≤ 100 000 m²; floors within documented bounds. |
| FR-UNIT-05 | Deletion is prohibited once the unit has financial history; use `INACTIVE`. |

### 4.6 Ownership and occupancy (OCC)

| ID | Requirement |
|---|---|
| FR-OCC-01 | Ownership records with `share` (0 < share ≤ 1), start date, optional end date. Sum of active shares per unit ≤ 1. |
| FR-OCC-02 | Occupancy records with start/end date, occupant type (`TENANT`, `RESIDENT`), optional lease reference. |
| FR-OCC-03 | Historical records are never deleted; ending a record closes it (`end_date`). |
| FR-OCC-04 | At most one active occupancy per unit per occupant type at a time, unless explicitly configured for shared rentals (documented rule). |
| FR-OCC-05 | Ownership/occupancy changes emit events that synchronize `OWNER`/`TENANT` membership roles. |
| FR-OCC-06 | A user may hold both an ownership and an occupancy role in the same unit; roles are additive. |

### 4.7 Charges (CHG)

| ID | Requirement |
|---|---|
| FR-CHG-01 | Charge rules per building: `EQUAL`, `AREA_BASED`, `RESIDENT_COUNT_BASED`, `CUSTOM`, `MIXED`. |
| FR-CHG-02 | A `MIXED` rule is an ordered list of components, each with its own type, weight/amount, and target (`ALL_UNITS` or explicit unit set). |
| FR-CHG-03 | `CUSTOM` uses a fixed per-unit amount table supplied by the manager, not a free-form expression. |
| FR-CHG-04 | Charge period is unique per `(building_id, jalali_year, jalali_month)`. |
| FR-CHG-05 | Generation is idempotent: a second call for the same period returns the existing result, creating nothing. |
| FR-CHG-06 | Generation is atomic: either all unit charges for the period are created, or none. |
| FR-CHG-07 | Generated unit charges are immutable. Corrections are separate `charge_adjustment` rows (positive or negative) with reason and actor. |
| FR-CHG-08 | Changes to charge rules affect only periods generated afterwards. |
| FR-CHG-09 | Rounding: each component is rounded to the nearest Rial per unit, then remainders are distributed to keep the period total exact (documented in `financial-rules.md`). |
| FR-CHG-10 | Period lifecycle: `DRAFT` → `GENERATED` → `CLOSED`. `CLOSED` blocks further adjustments. |

### 4.8 Finance (FIN)

| ID | Requirement |
|---|---|
| FR-FIN-01 | Per-building chart of accounts with 4-digit codes, types `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE`. |
| FR-FIN-02 | Journal entries in double entry: `Σ debit == Σ credit`, at least two lines, one posting per entry. |
| FR-FIN-03 | Entries are immutable after posting; corrections use reversal (`reversal_of_entry_id`, one reversal max) or new adjusting entries. |
| FR-FIN-04 | Expense lifecycle: `DRAFT` → `POSTED` (creates journal entry) → `REVERSED`. |
| FR-FIN-05 | Receipts are numbered per building with a gapless sequence per fiscal year. |
| FR-FIN-06 | Trial balance, income/expense statement, unit balance report, and cash-flow-by-account report are computed from the ledger only. |
| FR-FIN-07 | Fiscal year is Jalali; years are flagged open/closed; closed years reject postings. |
| FR-FIN-08 | Every write to the ledger produces an audit record (actor, before/after, request id). |

### 4.9 Payments (PAY)

| ID | Requirement |
|---|---|
| FR-PAY-01 | Initiate payment for one or more unit charges of the same building; receive gateway redirect token. |
| FR-PAY-02 | Statuses: `INITIATED`, `PENDING`, `SUCCESS`, `FAILED`, `CANCELLED`, `REFUNDED`. |
| FR-PAY-03 | Confirmation requires server-side verification against the gateway; client callbacks are hints only. |
| FR-PAY-04 | Confirmation is idempotent and concurrency-safe (two simultaneous callbacks → exactly one ledger posting). |
| FR-PAY-05 | Partial payments supported via `payment_allocation` rows. |
| FR-PAY-06 | Overpayment is not auto-applied past the outstanding balance; excess creates a `CREDIT_BALANCE` allocation to the unit. |
| FR-PAY-07 | Refunds are separate transactions and reverse the corresponding ledger posting. |
| FR-PAY-08 | Payment attempts carry an idempotency key unique per user. |
| FR-PAY-09 | Gateway adapter is chosen from configuration; the domain never references a concrete gateway. |

### 4.10 Maintenance (MNT)

| ID | Requirement |
|---|---|
| FR-MNT-01 | Request with category, description, priority (`LOW`, `NORMAL`, `HIGH`, `URGENT`), unit/area reference, attachments. |
| FR-MNT-02 | Status machine: `OPEN` → `IN_PROGRESS` → (`WAITING` ↔ `IN_PROGRESS`) → `RESOLVED` → `CLOSED`; `CANCELLED` from non-terminal states. |
| FR-MNT-03 | Assignment to a building staff member (membership with manager/accountant role) and status history. |
| FR-MNT-04 | Comments visible to building staff; internal notes restricted. |
| FR-MNT-05 | Resolution records capture resolver, resolution note, and time. |
| FR-MNT-06 | Only request author, unit occupants, and building staff can read a request. |

### 4.11 Announcements (ANN)

| ID | Requirement |
|---|---|
| FR-ANN-01 | Building announcements with title, body, attachments, publish date, expiry date. |
| FR-ANN-02 | Visibility: `ALL_MEMBERS`, `OWNERS`, `TENANTS`, `MANAGERS`. |
| FR-ANN-03 | Read tracking per user with timestamp; unread counters per user. |
| FR-ANN-04 | Draft → published → expired lifecycle; expiry filters listing, does not delete. |

### 4.12 Reservations (RSV)

| ID | Requirement |
|---|---|
| FR-RSV-01 | Shared facilities with type, capacity, opening hours, slot duration, max advance days, cancellation window, fee (optional). |
| FR-RSV-02 | Reservations must not overlap for the same facility (enforced at database level). |
| FR-RSV-03 | Per-user quotas (e.g. max 2 active reservations, max 1 per day) configurable per facility. |
| FR-RSV-04 | Status: `PENDING`, `CONFIRMED`, `CANCELLED`, `NO_SHOW`, `COMPLETED`. |
| FR-RSV-05 | Cancel allowed until `starts_at - cancellation_window`; after that only building staff. |
| FR-RSV-06 | Fee-bearing reservations post to the building ledger through the same charge/allocation pipeline. |

### 4.13 Meetings and voting (VOT)

| ID | Requirement |
|---|---|
| FR-VOT-01 | Meeting with title, scheduled time, location, agenda items, eligible voter scope, and one or more votes. |
| FR-VOT-02 | Vote has type (`SINGLE_CHOICE`, `YES_NO`, `MULTI_CHOICE`), options, opens/closes timestamps, quorum, and weighting mode. |
| FR-VOT-03 | Eligibility is evaluated at vote-open time against ownership/occupancy records, not at cast time. |
| FR-VOT-04 | One ballot per eligible voting principal (unit or owner, depending on weighting mode). |
| FR-VOT-05 | Ballots are immutable; a ballot may be replaced only if the vote explicitly allows recasting before the deadline. |
| FR-VOT-06 | Results computed server-side only, with quorum and turnout, after the deadline. |
| FR-VOT-07 | Voting is closed automatically at the deadline (lazy evaluation on read + scheduled job). |

### 4.14 Notifications (NTF)

| ID | Requirement |
|---|---|
| FR-NTF-01 | In-app notifications are persisted rows with read state. |
| FR-NTF-02 | SMS and push use provider interfaces (`SmsProvider`, `PushProvider`) selected by configuration. |
| FR-NTF-03 | Delivery attempts are recorded with status and retry count; failures must not roll back the business transaction. |
| FR-NTF-04 | Users can opt out of non-transactional categories; transactional categories (OTP, payment result) cannot be disabled. |
| FR-NTF-05 | Templates are data-driven with locale `fa-IR` and parameter substitution. |

### 4.15 SaaS subscription (SUB)

| ID | Requirement |
|---|---|
| FR-SUB-01 | Plans with code, name, price (IRR), billing cycle (`MONTHLY`, `YEARLY`), trial days, and feature limits (max units, max managers, feature flags). |
| FR-SUB-02 | One active subscription per building at a time; state machine `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED`, `EXPIRED`. |
| FR-SUB-03 | Limits enforced at the point of use (e.g. adding units beyond plan limit is rejected with a specific error code). |
| FR-SUB-04 | Platform billing uses its own tables and never creates entries in a building's ledger. |
| FR-SUB-05 | Subscription status changes are audited and notify the building manager. |

### 4.16 Audit (AUD)

| ID | Requirement |
|---|---|
| FR-AUD-01 | Audit the following: auth events, membership changes, charge generation and adjustments, all ledger writes, payment state transitions, subscription changes. |
| FR-AUD-02 | Records include actor, building, entity, action, before/after JSON diff (redacted), IP, user agent, request id. |
| FR-AUD-03 | Audit rows are append-only and never updated or deleted through the application. |

---

## 5. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-01 | Java 21, Spring Boot 3.x, Maven. Build reproducible with a pinned wrapper. |
| NFR-02 | Schema changes only through Liquibase; `ddl-auto=validate` in all profiles. |
| NFR-03 | p95 API latency < 300 ms for read endpoints at 50 rps per instance (reference hardware, 100k units). |
| NFR-04 | All monetary values as `BigDecimal` (scale 0) in Java; `NUMERIC(19,0)` in PostgreSQL; never `double`/`float`. |
| NFR-05 | Cross-building access attempts return `404` for read and `404`/`403` for writes, never leaking existence. |
| NFR-06 | No secrets in the repository; configuration through environment variables or a mounted secret file. |
| NFR-07 | Structured JSON logs with request id, user id, building id; no PII beyond phone (masked) and no tokens. |
| NFR-08 | Health and readiness endpoints; liveness must not depend on the database. |
| NFR-09 | Persian-first API error messages keyed by stable error codes; English for developer messages. |
| NFR-10 | OpenAPI 3.1 spec served in dev/test, disabled or admin-protected in production. |
| NFR-11 | Accessibility: semantic labels, 48dp targets, dynamic type support in both apps. |
| NFR-12 | Both Flutter apps support Android 8.0+ and iOS 15+. |

---

## 6. Business Rules (decisions taken now)

These are documented assumptions. Those marked **[Q]** materially affect correctness and are re-listed as open questions in section 8.

| ID | Rule |
|---|---|
| BR-01 | Currency is IRR, integer, no fractional Rial. Toman is display-only (`/10`), never persisted or transmitted as a distinct unit. **[Q3]** |
| BR-02 | Billing periods follow the Jalali month, stored with `jalali_year`/`jalali_month` plus Gregorian `start_date`/`end_date`. |
| BR-03 | Only `ACTIVE` units receive charges. `VACANT` units are still charged unless the rule excludes them. **[Q6]** |
| BR-04 | Ownership share is a fraction of 1; active shares per unit must not exceed 1. |
| BR-05 | A unit charge is billed to the unit, not to a person; responsibility allocation between owner and tenant is a reporting concern, not a ledger concern. **[Q2]** |
| BR-06 | Overpayment creates a unit credit balance; it is not silently applied to future charges without an explicit action. **[Q5]** |
| BR-07 | A closed fiscal year rejects all postings, including reversals; reversals must be posted in the year in which they occur. |
| BR-08 | Building status `SUSPENDED` blocks new charges and payments but keeps data readable. |
| BR-09 | Reservation conflicts are a hard constraint, not a soft warning. |
| BR-10 | One vote per unit by default; the unit's owner votes. Tenants may vote only if the meeting enables it. **[Q1]** |
| BR-11 | Charge generation remainder distribution is deterministic (largest-remainder, tie-break by unit number) so regeneration is reproducible. |
| BR-12 | Deleting business records is not supported where history matters: units, users, buildings, ledger data, payments. Soft-deactivate instead. |
| BR-13 | An OTP is invalidated by a successful login and cannot be reused. |
| BR-14 | Accountants can never post journal entries or generate charges unless explicitly granted `FINANCE_POST` / `CHARGE_GENERATE`. |

---

## 7. traceability

Every FR above maps to at least one test in the test plan (`implementation-plan.md` §6). Requirement IDs are the anchor for both test names and API operation IDs.

---

## 8. Critical Open Questions

Each has a working default so Phase 2 can start. Answering changes a documented rule, not the architecture.

| # | Question | Working default | Impact if changed |
|---|---|---|---|
| Q1 | Who may vote: per unit, per owner, or per person? Are votes weighted by ownership share? | One ballot per unit; owner casts it; optional tenant voting per meeting; weighting `ONE_PER_UNIT`. | Ballot uniqueness key, eligibility resolver, result math. |
| Q2 | Are charges billed to the unit, split between owner and tenant, or configurable per charge component? | Billed to the unit; split is reporting-only. | `unit_charge` gains a payer column; allocation and reporting change. |
| Q3 | Should API accept/return Toman anywhere? | No. IRR only; clients format Toman. | Contract-wide, must decide before API freeze. |
| Q4 | Is there an accountant role per building or one accountant across a complex of buildings? | Per building membership; a user can join many buildings. | None — already supported. |
| Q5 | Does overpayment auto-apply to the next period's charges, or stay as a credit? | Stays as credit; manager applies it explicitly. | Charge generation must consider credits. |
| Q6 | Are vacant units charged the full amount, a reduced rate, or exempt? | Charged in full unless a `MIXED` component targets only occupied units. | Charge strategy contract. |
| Q7 | Are late-payment penalties required, and how computed? | Not in v1. | New rule type + scheduled job. |
| Q8 | Multi-building complexes: charge a shared complex-level expense? | v1: expenses are per building. Complex-level allocation is a later module. | Adds a `complex` scope above building. |
| Q9 | Payment gateway choice (Zarinpal / IDPay / Mellat / Saman / Sep) and whether settlement is direct or per-building wallet. | Adapter interface + mock only; one production adapter when credentials exist. | Adapter implementation only. |
| Q10 | Should residents see individual unit debt of other units? | No. Only aggregates and the caller's units. | Report authorization rules. |
| Q11 | Fiscal year = Jalali year or a custom 12-month window? | Jalali year (Farvardin–Esfand). | Period/fiscal tables. |
| Q12 | Is SMS sending billable to the building or to BuildingHub? | BuildingHub platform cost in v1, tracked outside the building ledger. | Notification cost accounting. |
| Q13 | Do we need Persian PDF receipts with official numbering from day one? | Yes — receipt PDF in Phase 3, gapless numbering per building. | Packaging of PDF library. |
| Q14 | File storage backend (local disk, S3-compatible, Iranian object storage)? | Abstraction `FileStorage` with local-disk dev impl; production driver TBD. | Upload/storage module only. |
| Q15 | Does a tenant see ownership-level financial data (e.g. fund balance)? | No; tenants see unit charges, payments, receipts, published expense reports. | Report authorization matrix. |

---

## 9. Environment Prerequisites (observed)

Checked on the target machine at the time of writing:

- Java 21: **not installed** (only `jdk-17.0.2` found). Phase 2 requires JDK 21.
- Maven 3.9.12: present.
- Docker CLI: present (daemon not verified).
- Flutter/Dart SDK: **not installed**. Required from Phase 4.
- No existing Git repository and no existing BuildingHub code — nothing to preserve.

Phase 1 is documentation-only and is unaffected. Installing JDK 21, Flutter, and starting Docker are prerequisites for Phase 2 and Phase 4 respectively.
