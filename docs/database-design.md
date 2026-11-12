# BuildingHub — Database Design

> PostgreSQL 16+. UUID v7 identifiers, `timestamptz` in UTC, `NUMERIC(19,0)` money in IRR.
> All schema changes via Liquibase. `ddl-auto=validate` in every profile.

---

## 1. Conventions

| Convention | Rule |
|---|---|
| Primary key | `id uuid primary key`, generated in Java as UUID v7 (time-ordered, non-enumerable). |
| Naming | snake_case, plural table names, `*_id` for foreign keys. |
| Audit columns | `created_at timestamptz not null`, `created_by uuid`, `updated_at`, `updated_by`. Append-only tables omit `updated_*`. |
| Soft delete | Only `app_user`, `building`, `unit`, `announcement`, `shared_facility` via `archived_at`. Never for ledger, charge, or payment history. |
| Optimistic locking | `version bigint not null default 0` on mutable aggregates. |
| Tenant column | `building_id uuid not null` on every building-scoped table, first column of tenant indexes. |
| Money | `NUMERIC(19,0)`; `CHECK (amount >= 0)` or signed `CHECK (amount <> 0)` where adjustments are allowed. |
| Enums | `varchar(32)` plus a `CHECK` constraint (not native PostgreSQL enums, so migrations stay reversible). |
| Time ranges | Half-open `[start, end)` on `tstzrange`/`daterange` for overlap constraints. |
| Search | `pg_trgm` GIN indexes for Persian name/address search. |
| Extensions | `pgcrypto`, `btree_gist`. |

---

## 2. Entity Catalogue

### 2.1 Identity

#### `app_user`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `phone_number` | varchar(16) | NOT NULL, UNIQUE, `CHECK (phone_number ~ '^\+98[0-9]{10}$')` |
| `first_name` | varchar(100) | NULL |
| `last_name` | varchar(100) | NULL |
| `national_id` | varchar(10) | NULL, `CHECK (national_id ~ '^[0-9]{10}$')` |
| `email` | varchar(254) | NULL |
| `is_platform_admin` | boolean | NOT NULL DEFAULT false |
| `status` | varchar(32) | NOT NULL, `CHECK IN ('ACTIVE','BLOCKED','ARCHIVED')` |
| `last_login_at` | timestamptz | NULL |
| `archived_at` | timestamptz | NULL |
| audit + `version` | | |

Indexes: `ux_app_user_phone (phone_number)`; partial `ux_app_user_national_id (national_id) WHERE national_id IS NOT NULL`; partial `ux_app_user_email (lower(email)) WHERE email IS NOT NULL`; `ix_app_user_status (status)`.

#### `refresh_token`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `user_id` | uuid | FK → `app_user` ON DELETE CASCADE |
| `family_id` | uuid | NOT NULL; a rotation family revoked as one unit |
| `token_hash` | varchar(64) | NOT NULL UNIQUE (SHA-256 hex of the opaque token) |
| `issued_at`, `expires_at`, `used_at`, `revoked_at` | timestamptz | |
| `replaced_by_id` | uuid | FK → self, NULL; set on rotation, enables reuse detection |
| `device_id` | varchar(128) | NULL |
| `ip_hash`, `user_agent` | varchar | NULL |

Indexes: `ux_refresh_token_hash`; `ix_refresh_token_family (family_id)`; partial `ix_refresh_token_user_active (user_id) WHERE revoked_at IS NULL`.

#### `otp_challenge`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `phone_number` | varchar(16) | NOT NULL |
| `code_hash` | varchar(64) | NOT NULL, HMAC-SHA256 of the code |
| `purpose` | varchar(32) | `CHECK IN ('LOGIN','PHONE_VERIFY')` |
| `attempts` | smallint | NOT NULL DEFAULT 0 |
| `max_attempts` | smallint | NOT NULL DEFAULT 5 |
| `expires_at` | timestamptz | NOT NULL |
| `consumed_at` | timestamptz | NULL |
| `created_at` | timestamptz | NOT NULL |

Indexes: `ix_otp_phone_created (phone_number, created_at DESC)`; partial `ix_otp_open (phone_number) WHERE consumed_at IS NULL`.

"Only one active OTP per phone" cannot be a partial unique index because it depends on `now()`. It is enforced by consuming the previous challenge and taking a PostgreSQL advisory lock keyed on the phone hash during issuance.

#### `login_attempt`

| Column | Type | Notes |
|---|---|---|
| `id` | bigserial | PK |
| `phone_number` | varchar(16) | NULL |
| `ip_hash` | varchar(64) | NULL |
| `outcome` | varchar(32) | `SUCCESS`, `INVALID_CODE`, `EXPIRED`, `RATE_LIMITED`, `BLOCKED_USER` |
| `attempted_at` | timestamptz | NOT NULL DEFAULT now() |

Indexes: `ix_login_attempt_phone_time (phone_number, attempted_at DESC)`; `ix_login_attempt_ip_time (ip_hash, attempted_at DESC)`.

### 2.2 Building and membership

#### `building`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `name` | varchar(200) | NOT NULL |
| `code` | varchar(32) | NOT NULL, globally UNIQUE, used by support |
| `city` | varchar(100) | NOT NULL |
| `address_line` | varchar(500) | NOT NULL |
| `postal_code` | varchar(10) | NULL, `CHECK (~ '^[0-9]{10}$')` |
| `latitude`, `longitude` | `numeric(9,6)` | NULL; `CHECK (latitude BETWEEN -90 AND 90)`, same for longitude |
| `floor_count` | smallint | NOT NULL, `CHECK (floor_count >= 0)` |
| `total_units` | integer | NOT NULL DEFAULT 0, `CHECK (total_units >= 0)`; maintained from `unit` |
| `status` | varchar(32) | NOT NULL DEFAULT 'DRAFT', `CHECK IN ('DRAFT','ACTIVE','SUSPENDED','ARCHIVED')` |
| `timezone` | varchar(64) | NOT NULL DEFAULT 'Asia/Tehran' |
| `archived_at` | timestamptz | NULL |
| audit + `version` | | |

Indexes: `ux_building_code`; `ix_building_status (status)`; `ix_building_name_trgm USING gin (name gin_trgm_ops)`.

#### `building_membership`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | FK → `building`, NOT NULL |
| `user_id` | uuid | FK → `app_user`, NOT NULL |
| `roles` | `varchar(32)[]` | NOT NULL, `CHECK (roles <@ ARRAY['BUILDING_MANAGER','ACCOUNTANT','OWNER','TENANT','RESIDENT'])` |
| `permissions` | `varchar(48)[]` | NOT NULL DEFAULT '{}' (`CHARGE_GENERATE`, `FINANCE_POST`, `REPORT_VIEW`, …) |
| `status` | varchar(32) | NOT NULL DEFAULT 'ACTIVE', `CHECK IN ('INVITED','ACTIVE','SUSPENDED','REMOVED')` |
| `joined_at`, `removed_at` | timestamptz | |
| audit + `version` | | |

Constraints: `UNIQUE (building_id, user_id)`; `CHECK (array_length(roles,1) >= 1)`.
Indexes: `ux_membership_building_user`; `ix_membership_user (user_id)`; partial `ix_membership_building_active (building_id) WHERE status = 'ACTIVE'`.

`PLATFORM_ADMIN` is not a membership role; it lives on `app_user.is_platform_admin`.

### 2.3 Units and occupancy

#### `unit`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | FK → `building`, NOT NULL |
| `unit_number` | varchar(32) | NOT NULL, as entered |
| `unit_number_normalized` | varchar(32) | GENERATED ALWAYS AS `lower(regexp_replace(unit_number, '\s', '', 'g'))` STORED |
| `floor` | smallint | NOT NULL, `CHECK (floor BETWEEN -10 AND 200)` |
| `area_m2` | `numeric(10,2)` | NOT NULL, `CHECK (area_m2 > 0 AND area_m2 <= 100000)` |
| `resident_count` | smallint | NOT NULL DEFAULT 0, `CHECK (resident_count >= 0)` |
| `status` | varchar(32) | NOT NULL DEFAULT 'ACTIVE', `CHECK IN ('ACTIVE','VACANT','UNDER_RENOVATION','INACTIVE')` |
| `block` | varchar(32) | NULL, for complexes |
| `archived_at` | timestamptz | NULL |
| audit + `version` | | |

Constraints: `UNIQUE (building_id, unit_number_normalized)`; `UNIQUE (building_id, id)` to support composite tenant FKs.
Indexes: `ux_unit_building_number (building_id, unit_number_normalized)`; `ix_unit_building_status (building_id, status)`.

#### `unit_parking` / `unit_storage`

Two separate tables rather than comma-joined columns, so each item is addressable and reservable.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `code` | varchar(32) | NOT NULL, e.g. `P-12` |
| `location_note` | varchar(200) | NULL |
| `is_active` | boolean | NOT NULL DEFAULT true |

`UNIQUE (building_id, code)`. Composite FK `(building_id, unit_id)` → `unit(building_id, id)`.

#### `unit_ownership`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `user_id` | uuid | FK → `app_user`, NOT NULL |
| `share` | `numeric(6,5)` | NOT NULL, `CHECK (share > 0 AND share <= 1)` |
| `start_date` | date | NOT NULL |
| `end_date` | date | NULL, `CHECK (end_date IS NULL OR end_date > start_date)` |
| `deed_reference` | varchar(100) | NULL |
| audit | | |

Constraints:

```sql
ALTER TABLE unit_ownership ADD CONSTRAINT ex_ownership_no_overlap
  EXCLUDE USING gist (unit_id WITH =, user_id WITH =,
                      daterange(start_date, end_date, '[)') WITH &&);
```

Sum of active shares per unit ≤ 1 is an application invariant enforced under `SELECT … FOR UPDATE` on the unit row, verified by an integrity query in tests and a nightly report.

Indexes: partial `ix_ownership_unit_active (unit_id) WHERE end_date IS NULL`; `ix_ownership_user (user_id)`.

#### `unit_occupancy`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `user_id` | uuid | NOT NULL |
| `occupant_type` | varchar(32) | `CHECK IN ('TENANT','RESIDENT')` |
| `start_date` | date | NOT NULL |
| `end_date` | date | NULL |
| `lease_reference` | varchar(100) | NULL |
| `resident_count` | smallint | NULL |
| audit | | |

Constraints: same `EXCLUDE USING gist (unit_id WITH =, user_id WITH =, daterange(...) WITH &&)`; plus single current tenant in v1:

```sql
CREATE UNIQUE INDEX ux_occupancy_one_current_tenant ON unit_occupancy (unit_id)
  WHERE end_date IS NULL AND occupant_type = 'TENANT';
```

### 2.4 Charges

#### `charge_rule`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `name` | varchar(200) | NOT NULL |
| `strategy` | varchar(32) | `CHECK IN ('EQUAL','AREA_BASED','RESIDENT_COUNT_BASED','CUSTOM','MIXED')` |
| `effective_from` | date | NOT NULL |
| `effective_to` | date | NULL |
| `is_active` | boolean | NOT NULL DEFAULT true |
| `rounding_mode` | varchar(16) | NOT NULL DEFAULT 'HALF_UP', `CHECK IN ('HALF_UP','FLOOR')` |
| audit + `version` | | |

No overlapping active rules per building:

```sql
ALTER TABLE charge_rule ADD CONSTRAINT ex_charge_rule_no_overlap
  EXCLUDE USING gist (building_id WITH =,
                      daterange(effective_from, effective_to, '[)') WITH &&)
  WHERE (is_active);
```

#### `charge_rule_component`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `charge_rule_id` | uuid | FK → `charge_rule` ON DELETE CASCADE |
| `sequence` | smallint | NOT NULL |
| `component_type` | varchar(32) | `EQUAL`,`AREA_BASED`,`RESIDENT_COUNT_BASED`,`CUSTOM` (no nested `MIXED`) |
| `title` | varchar(200) | Persian invoice label |
| `pool_amount` | `numeric(19,0)` | NULL, total to distribute for pool-based components |
| `rate_per_m2` | `numeric(19,4)` | NULL |
| `rate_per_resident` | `numeric(19,0)` | NULL |
| `target_scope` | varchar(24) | `CHECK IN ('ALL_UNITS','OCCUPIED_UNITS')` |
| `include_inactive_units` | boolean | NOT NULL DEFAULT false |

A `CHECK` ties each `component_type` to the field it requires. `UNIQUE (charge_rule_id, sequence)`.

#### `unit_charge_override`

Input for the `CUSTOM` strategy.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `charge_rule_id` | uuid | NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount >= 0)` |

`UNIQUE (charge_rule_id, unit_id)`.

#### `charge_period`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `jalali_year` | smallint | NOT NULL, `CHECK (jalali_year BETWEEN 1390 AND 1500)` |
| `jalali_month` | smallint | NOT NULL, `CHECK (jalali_month BETWEEN 1 AND 12)` |
| `start_date`, `end_date` | date | NOT NULL, `CHECK (end_date >= start_date)` |
| `status` | varchar(24) | NOT NULL DEFAULT 'DRAFT', `CHECK IN ('DRAFT','GENERATED','CLOSED')` |
| `charge_rule_id` | uuid | NOT NULL, the rule used |
| `rule_snapshot` | jsonb | NOT NULL after generation, frozen copy of rule + components |
| `generated_at`, `generated_by` | | NULL until generated |
| `total_amount` | `numeric(19,0)` | NULL until generated |
| `units_charged` | integer | NULL until generated |
| audit + `version` | | |

Constraints:

```sql
UNIQUE (building_id, jalali_year, jalali_month);
CHECK (status = 'DRAFT' OR (generated_at IS NOT NULL AND total_amount IS NOT NULL));
```

`rule_snapshot` is what makes FR-CHG-08 auditable: any invoice can be reproduced after the rule is edited.

#### `unit_charge`

Amount fields are immutable after insert; `paid_amount`, `credit_amount`, and `status` are derived projections recomputed from allocations.

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `charge_period_id` | uuid | FK NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount >= 0)` |
| `paid_amount` | `numeric(19,0)` | NOT NULL DEFAULT 0, `CHECK (paid_amount >= 0)` |
| `credit_amount` | `numeric(19,0)` | NOT NULL DEFAULT 0, `CHECK (credit_amount >= 0)` |
| `status` | varchar(24) | NOT NULL DEFAULT 'UNPAID', `CHECK IN ('UNPAID','PARTIALLY_PAID','PAID','CREDITED','WAIVED')` |
| `due_date` | date | NOT NULL |
| `breakdown` | jsonb | NOT NULL, per-component amounts for receipts |
| `calculation_trace` | jsonb | NULL, inputs used (area, residents) for audit |

Constraints: `UNIQUE (charge_period_id, unit_id)`; `CHECK (paid_amount + credit_amount <= amount)`.
Indexes: `ix_unit_charge_building_status (building_id, status)`; `ix_unit_charge_unit (unit_id, charge_period_id)`.

#### `charge_adjustment`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `unit_charge_id` | uuid | FK NOT NULL |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount <> 0)`; signed |
| `kind` | varchar(24) | `CHECK IN ('CORRECTION','DISCOUNT','PENALTY','MANUAL')` |
| `reason` | varchar(500) | NOT NULL |
| `reverses_adjustment_id` | uuid | NULL, UNIQUE |
| `created_by`, `created_at` | | NOT NULL |

Append-only. A reversal cannot itself be reversed (application rule, verified by test).

### 2.5 Finance

#### `financial_account`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `code` | varchar(16) | NOT NULL |
| `name` | varchar(200) | NOT NULL |
| `type` | varchar(16) | `CHECK IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')` |
| `normal_balance` | varchar(8) | `CHECK IN ('DEBIT','CREDIT')` |
| `is_system` | boolean | NOT NULL DEFAULT false |
| `is_active` | boolean | NOT NULL DEFAULT true |
| `parent_id` | uuid | NULL, FK → self, `CHECK (parent_id <> id)` |
| audit | | |

`UNIQUE (building_id, code)`.

Seeded chart per building: `1100 Cash`, `1200 Bank`, `1300 Accounts Receivable`, `2100 Accounts Payable`, `3000 Fund Balance`, `3100 Retained Surplus`, `4000 Charge Income`, `4100 Facility Income`, `4200 Penalty Income`, `5000 Maintenance`, `5100 Utilities`, `5200 Cleaning`, `5300 Repairs`, `5400 Other`.

#### `fiscal_year`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `jalali_year` | smallint | NOT NULL |
| `start_date`, `end_date` | date | NOT NULL |
| `status` | varchar(16) | `CHECK IN ('OPEN','CLOSED')` |
| `closed_at`, `closed_by` | | NULL |

`UNIQUE (building_id, jalali_year)`.

#### `journal_entry`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `fiscal_year_id` | uuid | NOT NULL |
| `entry_number` | bigint | NOT NULL, gapless per building/fiscal year |
| `entry_date` | date | NOT NULL |
| `description` | varchar(500) | NOT NULL |
| `source_type` | varchar(32) | `CHECK IN ('PAYMENT','EXPENSE','ADJUSTMENT','REVERSAL','MANUAL','OPENING','FACILITY_FEE')` |
| `source_id` | uuid | NULL |
| `posted_at`, `posted_by` | | NOT NULL |
| `reversal_of_entry_id` | uuid | NULL, UNIQUE |
| `is_reversal` | boolean | NOT NULL DEFAULT false |
| `total_amount` | `numeric(19,0)` | NOT NULL, `CHECK (total_amount > 0)` |

```sql
UNIQUE (building_id, fiscal_year_id, entry_number);
CHECK (reversal_of_entry_id IS NULL OR is_reversal);
```

Indexes: `ix_journal_entry_building_date (building_id, entry_date DESC)`; `ix_journal_entry_source (source_type, source_id)`.

#### `journal_entry_line`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `journal_entry_id` | uuid | FK NOT NULL |
| `line_number` | smallint | NOT NULL |
| `account_id` | uuid | FK NOT NULL |
| `debit` | `numeric(19,0)` | NOT NULL DEFAULT 0, `CHECK (debit >= 0)` |
| `credit` | `numeric(19,0)` | NOT NULL DEFAULT 0, `CHECK (credit >= 0)` |
| `description` | varchar(300) | NULL |
| `unit_id` | uuid | NULL, analytic dimension |
| `charge_period_id` | uuid | NULL |
| `payment_id` | uuid | NULL |

```sql
CHECK ((debit = 0) <> (credit = 0));
UNIQUE (journal_entry_id, line_number);
```

Balance (`Σ debit = Σ credit`) is validated in the application and re-asserted by a deferred constraint trigger at statement end, because a plain `CHECK` cannot aggregate across rows.

Indexes: `ix_journal_line_entry (journal_entry_id)`; `ix_journal_line_account (account_id, journal_entry_id)`; `ix_journal_line_unit (unit_id)`; `ix_journal_line_period (charge_period_id)`.

#### `expense_category`

`id`, `building_id`, `name`, `default_account_id` FK → `financial_account`, `is_active`. `UNIQUE (building_id, name)`.

#### `expense`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `category_id` | uuid | FK NOT NULL |
| `expense_date` | date | NOT NULL |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount > 0)` |
| `payee` | varchar(200) | NOT NULL |
| `description` | varchar(500) | NULL |
| `payment_account_id` | uuid | NOT NULL, the cash/bank account |
| `status` | varchar(16) | `CHECK IN ('DRAFT','POSTED','REVERSED')` |
| `journal_entry_id` | uuid | NULL |
| `reversal_entry_id` | uuid | NULL |
| `attachment_id` | uuid | NULL |
| audit + `version` | | |

`CHECK (status <> 'POSTED' OR journal_entry_id IS NOT NULL)`.

#### `receipt`

`id`, `building_id`, `fiscal_year_id`, `receipt_number bigint`, `unit_charge_id NULL`, `payment_id NULL`, `amount`, `issued_at`, `issued_by`, `pdf_storage_key`.
`UNIQUE (building_id, fiscal_year_id, receipt_number)`.

#### `sequence_counter`

| Column | Type | Notes |
|---|---|---|
| `building_id` | uuid | PK part |
| `scope` | varchar(24) | PK part, `JOURNAL` or `RECEIPT` |
| `fiscal_year_id` | uuid | PK part |
| `next_value` | bigint | NOT NULL DEFAULT 1 |

Incremented under `SELECT … FOR UPDATE` inside the posting transaction. Gapless numbering trades a little write concurrency for auditability, which is the right trade for building finances.

### 2.6 Payments

#### `payment`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `unit_id` | uuid | NOT NULL |
| `payer_user_id` | uuid | NOT NULL |
| `idempotency_key` | varchar(64) | NOT NULL |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount > 0)` |
| `currency` | char(3) | NOT NULL DEFAULT 'IRR', `CHECK (currency = 'IRR')` |
| `status` | varchar(16) | `CHECK IN ('INITIATED','PENDING','SUCCESS','FAILED','CANCELLED','REFUNDED')` |
| `provider` | varchar(32) | NOT NULL |
| `provider_reference` | varchar(128) | NULL |
| `gateway_url` | varchar(500) | NULL, returned to the client |
| `description` | varchar(300) | NULL |
| `initiated_at`, `verified_at`, `completed_at`, `failed_at` | timestamptz | |
| `journal_entry_id` | uuid | NULL |
| `failure_code`, `failure_message` | varchar | NULL |
| audit + `version` | | |

```sql
UNIQUE (payer_user_id, idempotency_key);
CREATE UNIQUE INDEX ux_payment_provider_ref ON payment (provider, provider_reference)
  WHERE provider_reference IS NOT NULL;
CHECK (status <> 'SUCCESS' OR (verified_at IS NOT NULL AND journal_entry_id IS NOT NULL));
```

Indexes: `ix_payment_building_status (building_id, status, initiated_at DESC)`; `ix_payment_unit (unit_id)`.

#### `payment_transaction`

`id`, `building_id`, `payment_id`, `type` (`INITIATE`,`VERIFY`,`REFUND`,`CANCEL`), `provider_status`, `provider_reference`, `amount`, `request_payload jsonb`, `response_payload jsonb` (both redacted, no PAN or card data), `occurred_at`.
Append-only. Index: `ix_payment_txn_payment (payment_id, occurred_at)`.

#### `payment_allocation`

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `payment_id` | uuid | NOT NULL |
| `unit_charge_id` | uuid | NULL, NULL means unit credit balance |
| `amount` | `numeric(19,0)` | NOT NULL, `CHECK (amount > 0)` |
| `kind` | varchar(16) | `CHECK IN ('CHARGE','CREDIT_BALANCE')` |
| `created_at` | timestamptz | NOT NULL |

`CHECK ((kind = 'CHARGE') = (unit_charge_id IS NOT NULL))`.
Invariant: for a `SUCCESS` payment, `SUM(allocation.amount) = payment.amount`. Enforced in the posting transaction and re-asserted by a constraint trigger after each insert.

### 2.7 Maintenance

#### `maintenance_request`

`id`, `building_id`, `request_number bigint` (per building), `title`, `description text`, `category_id`, `unit_id NULL` (NULL = common area), `reported_by`, `priority` (`LOW`,`NORMAL`,`HIGH`,`URGENT`), `status` (`OPEN`,`IN_PROGRESS`,`WAITING`,`RESOLVED`,`CLOSED`,`CANCELLED`), `assignee_membership_id NULL`, `resolved_at`, `resolution_note`, `closed_at`, audit, `version`.

`UNIQUE (building_id, request_number)`; index `(building_id, status, priority)`, `(assignee_membership_id, status)`.

#### `maintenance_comment`

`id`, `building_id`, `request_id`, `author_user_id`, `body text`, `is_internal boolean`, `created_at`, `edited_at NULL`. Edits allowed by the author within 15 minutes, audited.

#### `maintenance_status_history`

`id`, `building_id`, `request_id`, `from_status`, `to_status`, `changed_by`, `changed_at`, `note`.

#### `attachment`

File *metadata* only, attached to exactly one owner through a typed column pair.

| Column | Type | Constraints |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NOT NULL |
| `owner_type` | varchar(32) | `CHECK IN ('MAINTENANCE_REQUEST','ANNOUNCEMENT','EXPENSE','PAYMENT_RECEIPT')` |
| `owner_id` | uuid | NOT NULL |
| `storage_key` | varchar(300) | NOT NULL UNIQUE |
| `original_name` | varchar(255) | NOT NULL |
| `content_type` | varchar(100) | NOT NULL |
| `size_bytes` | bigint | NOT NULL, `CHECK (size_bytes > 0 AND size_bytes <= 10485760)` |
| `sha256` | char(64) | NOT NULL |
| `uploaded_by`, `uploaded_at` | | |

Index: `ix_attachment_owner (owner_type, owner_id)`.

### 2.8 Announcements

#### `announcement`

`id`, `building_id`, `title`, `body text`, `visibility` (`ALL_MEMBERS`,`OWNERS`,`TENANTS`,`MANAGERS`), `status` (`DRAFT`,`PUBLISHED`,`ARCHIVED`), `publish_at`, `expires_at NULL`, `pinned boolean`, `created_by`, audit, `version`.
`CHECK (expires_at IS NULL OR expires_at > publish_at)`. Index: `ix_announcement_building_publish (building_id, publish_at DESC)`.

#### `announcement_read`

PK `(announcement_id, user_id)`, `read_at timestamptz NOT NULL`. Index: `ix_announcement_read_user (user_id, read_at DESC)`.

### 2.9 Reservations

#### `shared_facility`

`id`, `building_id`, `name`, `facility_type` (`MEETING_ROOM`,`HALL`,`SPORTS`,`OTHER`), `capacity`, `opens_at time`, `closes_at time`, `slot_minutes smallint`, `max_reservations_per_user_per_month smallint`, `max_advance_days smallint`, `cancellation_window_hours smallint`, `fee_per_slot numeric(19,0)`, `requires_approval boolean`, `is_active`, `archived_at`, audit, `version`.

`UNIQUE (building_id, name)`; `CHECK (closes_at > opens_at)`; `CHECK (fee_per_slot >= 0)`.

#### `facility_reservation`

`id`, `building_id`, `facility_id`, `unit_id`, `reserved_by_user_id`, `starts_at timestamptz`, `ends_at timestamptz`, `status` (`PENDING`,`CONFIRMED`,`CANCELLED`,`NO_SHOW`,`COMPLETED`), `note`, `fee_amount numeric(19,0)`, `payment_id NULL`, `cancelled_at`, `cancelled_by`, audit, `version`.

```sql
ALTER TABLE facility_reservation
  ADD CONSTRAINT ex_reservation_no_overlap
  EXCLUDE USING gist (facility_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
  WHERE (status IN ('PENDING','CONFIRMED'));
```

Plus `CHECK (ends_at > starts_at)` and `CHECK (fee_amount >= 0)`; index `ix_reservation_facility_time (facility_id, starts_at)`.
This is a database guarantee, not a service-level convenience: two concurrent inserts cannot both succeed.

### 2.10 Meetings and voting

#### `meeting`

`id`, `building_id`, `title`, `description text`, `scheduled_at timestamptz`, `location varchar(200)`, `status` (`SCHEDULED`,`IN_PROGRESS`,`COMPLETED`,`CANCELLED`), `eligible_scope` (`ALL_UNITS`,`OWNERS_ONLY`,`UNITS_WITH_TENANT_VOTE`), `created_by`, audit, `version`.

#### `meeting_agenda_item`

`id`, `building_id`, `meeting_id`, `sequence`, `title`, `description text`. `UNIQUE (meeting_id, sequence)`.

#### `vote`

`id`, `building_id`, `meeting_id NULL`, `title`, `description`, `vote_type` (`SINGLE_CHOICE`,`YES_NO`,`MULTI_CHOICE`), `weighting` (`ONE_PER_UNIT`,`OWNERSHIP_SHARE`,`ONE_PER_OWNER`), `opens_at`, `closes_at`, `quorum_percent numeric(5,2)`, `allow_recast boolean`, `status` (`SCHEDULED`,`OPEN`,`CLOSED`,`CANCELLED`), `created_by`, audit, `version`.

`CHECK (closes_at > opens_at)`; `CHECK (quorum_percent BETWEEN 0 AND 100)`.

#### `vote_option`

`id`, `vote_id`, `sequence`, `label varchar(200)`. `UNIQUE (vote_id, sequence)`.

#### `vote_eligibility`

Snapshot taken when the vote opens, so results are reproducible regardless of later ownership changes.

`id`, `building_id`, `vote_id`, `unit_id`, `user_id`, `weight numeric(6,5)`, `principal_type` (`OWNER`,`TENANT`), `snapshot_at`. `UNIQUE (vote_id, unit_id, user_id)`.

#### `ballot` and `ballot_choice`

`ballot`: `id`, `building_id`, `vote_id`, `unit_id`, `user_id`, `cast_at`, `weight numeric(6,5)`.
`ballot_choice`: PK `(ballot_id, vote_option_id)`, `value numeric(3,0) NOT NULL DEFAULT 1`.

`UNIQUE (vote_id, unit_id, user_id)` on ballot, plus an application rule for one ballot per unit when the vote's weighting is `ONE_PER_UNIT`. Recast is delete+insert inside one transaction while the vote is open and `allow_recast` is true, and is audited.

### 2.11 Notifications

#### `notification`

`id`, `building_id NULL`, `user_id`, `category` (`AUTH`,`PAYMENT`,`CHARGE`,`ANNOUNCEMENT`,`MAINTENANCE`,`RESERVATION`,`VOTE`,`SUBSCRIPTION`,`SYSTEM`), `title`, `body text`, `data jsonb`, `read_at NULL`, `created_at`.
Partial index: `ix_notification_user_unread (user_id, created_at DESC) WHERE read_at IS NULL`.

#### `notification_delivery`

`id`, `notification_id`, `channel` (`IN_APP`,`SMS`,`PUSH`), `provider`, `status` (`QUEUED`,`SENT`,`FAILED`,`SUPPRESSED`), `attempt`, `error_code`, `sent_at`, `next_retry_at`.

#### `notification_template`

`id`, `code UNIQUE`, `channel`, `locale` (`fa-IR`), `subject`, `body_template text`, `is_transactional boolean`.

### 2.12 SaaS subscription

#### `subscription_plan`

`id`, `code UNIQUE`, `name`, `price_irr numeric(19,0)`, `billing_cycle` (`MONTHLY`,`YEARLY`), `trial_days smallint`, `max_units integer`, `max_managers smallint`, `features jsonb`, `is_active`, audit, `version`.

#### `building_subscription`

`id`, `building_id`, `plan_id`, `status` (`TRIALING`,`ACTIVE`,`PAST_DUE`,`CANCELED`,`EXPIRED`), `started_at`, `trial_ends_at`, `current_period_start`, `current_period_end`, `canceled_at`, `ended_at`, `external_billing_ref`, audit, `version`.

```sql
CREATE UNIQUE INDEX ux_subscription_one_per_building ON building_subscription (building_id)
  WHERE status IN ('TRIALING','ACTIVE','PAST_DUE');
```

#### `subscription_invoice`

`id`, `building_id`, `subscription_id`, `amount_irr`, `period_start`, `period_end`, `status` (`ISSUED`,`PAID`,`VOID`,`OVERDUE`), `issued_at`, `paid_at`, `payment_reference`.

No FK to any building ledger table. Platform billing and building accounting are separate bounded contexts (FR-SUB-04).

### 2.13 Audit

#### `audit_log`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `building_id` | uuid | NULL for platform-level actions |
| `actor_user_id` | uuid | NULL for system actions |
| `actor_type` | varchar(16) | `USER`,`SYSTEM`,`PLATFORM_ADMIN` |
| `action` | varchar(64) | e.g. `CHARGE_PERIOD_GENERATED` |
| `entity_type` | varchar(64) | |
| `entity_id` | uuid | |
| `before_state` | jsonb | NULL, redacted |
| `after_state` | jsonb | NULL, redacted |
| `ip_hash` | varchar(64) | NULL |
| `user_agent` | varchar(300) | NULL |
| `request_id` | varchar(64) | NULL |
| `occurred_at` | timestamptz | NOT NULL DEFAULT now() |

Indexes: `ix_audit_building_time (building_id, occurred_at DESC)`; `ix_audit_entity (entity_type, entity_id, occurred_at DESC)`; `ix_audit_actor (actor_user_id, occurred_at DESC)`.

---

## 3. Referential Integrity

- All foreign keys default to `ON DELETE RESTRICT`.
- `ON DELETE CASCADE` only for true composition: `journal_entry_line`, `vote_option`, `ballot_choice`, `maintenance_comment`, `meeting_agenda_item`, `refresh_token`.
- **Composite tenant FKs** prevent rows from linking across buildings:

```sql
ALTER TABLE unit ADD CONSTRAINT ux_unit_building_id UNIQUE (building_id, id);
ALTER TABLE unit_charge
  ADD CONSTRAINT fk_unit_charge_unit
  FOREIGN KEY (building_id, unit_id) REFERENCES unit (building_id, id);
```

  Applied to `unit_charge`, `unit_ownership`, `unit_occupancy`, `unit_parking`, `unit_storage`, `payment_allocation`.
- `building_id` is intentionally denormalized onto child tables: it makes tenant indexes covering and lets the database itself reject a row whose parent belongs to another building.

---

## 4. Index Strategy

| Access pattern | Index |
|---|---|
| A user's buildings | `building_membership(user_id) WHERE status='ACTIVE'` |
| Units in a building | `unit(building_id, status)` |
| Unit charges of a period | `unit_charge(charge_period_id, unit_id)` unique |
| Outstanding balances | partial on `unit_charge(building_id) WHERE status IN ('UNPAID','PARTIALLY_PAID')` |
| Ledger by account/date | `journal_entry_line(account_id, journal_entry_id)` + `journal_entry(building_id, entry_date)` |
| Payment history | `payment(building_id, status, initiated_at DESC)` |
| Upcoming reservations | `facility_reservation(facility_id, starts_at) WHERE status IN ('PENDING','CONFIRMED')` |
| Unread notifications | partial index on `notification` |
| Persian text search | `gin_trgm_ops` |

Hot tables are `unit_charge`, `journal_entry_line`, `notification`, `audit_log` — all append-heavy and all partition-ready because `created_at` is `NOT NULL` everywhere.

---

## 5. Database-Level Invariants

| # | Invariant | Enforced by |
|---|---|---|
| I-01 | One charge period per building per month | `UNIQUE (building_id, jalali_year, jalali_month)` |
| I-02 | One unit charge per period/unit | `UNIQUE (charge_period_id, unit_id)` |
| I-03 | Unit numbers unique per building | generated normalized column + unique index |
| I-04 | Active ownership shares ≤ 1 per unit | application (unit row lock) + integrity query in tests |
| I-05 | No overlapping ownership or occupancy per user/unit | `EXCLUDE USING gist` with `daterange` |
| I-06 | No overlapping reservations | `EXCLUDE USING gist` with `tstzrange` |
| I-07 | Journal entry balances | deferred constraint trigger + application check |
| I-08 | One debit or one credit per line | `CHECK ((debit=0) <> (credit=0))` |
| I-09 | Allocations equal payment amount | constraint trigger + application |
| I-10 | `SUCCESS` implies verified + journal entry | `CHECK` |
| I-11 | One live subscription per building | partial unique index |
| I-12 | Ledger rows immutable | triggers + `REVOKE UPDATE, DELETE` from app role |
| I-13 | Closed fiscal year rejects postings | application check + trigger |
| I-14 | No cross-building FK linkage | composite FKs |

### Immutability

Migrations run as `buildinghub_owner`; the application connects as `buildinghub_app` without update rights on append-only tables.

```sql
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END $$;

CREATE TRIGGER trg_journal_immutable BEFORE UPDATE OR DELETE ON journal_entry
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_journal_line_immutable BEFORE UPDATE OR DELETE ON journal_entry_line
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_payment_txn_immutable BEFORE UPDATE OR DELETE ON payment_transaction
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_unit_charge_amount_immutable
  BEFORE UPDATE OF amount, breakdown, charge_period_id, unit_id ON unit_charge
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
```

Corrections are new rows, so these triggers never block legitimate work.

---

## 6. Liquibase Layout

```
src/main/resources/db/changelog/
├── db.changelog-master.xml
├── 001-common/{001-extensions,002-audit-columns,003-immutability-triggers}.xml
├── 010-identity/{010-app-user,011-refresh-token,012-otp-challenge,013-login-attempt}.xml
├── 020-building/
├── 030-unit/
├── 040-occupancy/
├── 050-charge/
├── 060-finance/
├── 070-payment/
├── 080-maintenance/
├── 090-announcement/
├── 100-reservation/
├── 110-voting/
├── 120-notification/
├── 130-subscription/
└── 140-audit/
```

Rules: one changeset per logical change; `id` = `NNN-short-slug`; `author` = `buildinghub`; every DDL changeset has an explicit `rollback`; no destructive forward changesets; seed data lives in separate `*:seed` changelogs guarded for idempotency.

---

## 7. Volume Assumptions

| Entity | v1 target | 3-year estimate |
|---|---|---|
| Buildings | 500 | 5 000 |
| Units | 50 000 | 500 000 |
| Unit charges | 600 000 / year | 6 000 000 |
| Journal entry lines | 1 500 000 / year | 15 000 000 |
| Notifications | 2 000 000 / year | 20 000 000 |
| Audit logs | 3 000 000 / year | 30 000 000 |

`journal_entry_line`, `notification`, and `audit_log` become declarative-partitioning candidates around 20M rows. No partitioning in v1, but every column needed for range partitioning already exists.
