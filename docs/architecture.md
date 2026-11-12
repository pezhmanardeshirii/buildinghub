# BuildingHub — Architecture

> Status: Phase 1 (proposed). Modular monolith, single deployable, strict module boundaries.

---

## 1. Architectural Drivers

| Driver | Consequence |
|---|---|
| Multi-tenant isolation is the top security risk | Building scope is a first-class parameter of every service call, enforced in the application layer, not just URL rules. |
| Money must be auditable | Append-only double-entry ledger, immutable postings, reversals instead of edits. |
| Concurrency on charges and payments | Database-level uniqueness, optimistic locking, and transactional boundaries designed up front. |
| Two mobile apps, one API | API-first contract, versioned, DTO-only, generated client models. |
| Small team, production intent | Modular monolith; no broker, no distributed transactions; clear seams if extraction is ever needed. |

---

## 2. System Context

```
┌──────────────────┐        ┌──────────────────┐
│ Manager App      │        │ Resident App     │
│ Flutter (iOS/And)│        │ Flutter (iOS/And)│
└────────┬─────────┘        └─────────┬────────┘
         │ HTTPS /api/v1              │
         └───────────┬────────────────┘
                     ▼
        ┌────────────────────────────┐
        │ BuildingHub API (Spring)   │
        │  modular monolith          │
        └───┬───────┬────────┬───────┘
            │       │        │
            ▼       ▼        ▼
     ┌──────────┐ ┌──────┐ ┌────────────────────┐
     │PostgreSQL│ │Files │ │ External providers  │
     │ (Liquibase)│ │(local/S3)│ SMS, Push, Payment│
     └──────────┘ └──────┘ └────────────────────┘
```

Single deployable Spring Boot application. External providers are reached only through port interfaces.

---

## 3. Package Layout

```
ir.buildinghub
├── BuildingHubApplication.java
├── common
│   ├── api          # ApiResponse, PageResponse, ErrorCode, GlobalExceptionHandler
│   ├── domain       # BaseEntity, Money, JalaliPeriod, DomainException, Ids
│   ├── security     # CurrentUser, TenantContext, AuthorizationService, Permission
│   ├── persistence  # converters, auditing base, JPA config
│   ├── storage      # FileStorage port + local impl
│   └── config       # OpenApiConfig, JacksonConfig, ClockConfig, JpaAuditingConfig
├── auth
│   ├── api / application / domain / persistence / dto / mapper / validation
├── user
├── building
├── unit
├── membership
├── occupancy
├── charge
├── finance
├── payment
├── maintenance
├── announcement
├── reservation
├── voting
├── notification
├── subscription
└── audit
```

Each module has the same internal shape:

```
<module>/
├── api/            # @RestController, request/response mapping, no business logic
├── application/    # @Service, @Transactional, use cases, cross-module orchestration
├── domain/         # entities, value objects, domain services, policies, events
├── persistence/    # Spring Data repositories, query projections
├── dto/            # request + response records
├── mapper/         # MapStruct mappers
└── validation/     # custom validators
```

Layers point inward: `api → application → domain`, `application → persistence`. `domain` has no dependency on Spring Data or web types.

---

## 4. Module Responsibilities

| Module | Owns | Must not own |
|---|---|---|
| `common` | Base types, error model, security primitives, money, Jalali period, file storage port | Business rules |
| `auth` | Phone identity, OTP lifecycle, tokens, sessions, rate limiting | Roles/authorization decisions |
| `user` | User profile, national ID, contact data | Building membership |
| `building` | Building aggregate, address, status, manager assignment refs | Units, charges, finance |
| `membership` | Roles per building, permission grants, access checks caching | Role semantics of finance |
| `unit` | Unit aggregate, parking/storage metadata, status | Occupancy, charges |
| `occupancy` | Ownership and occupancy history, share validation | Billing |
| `charge` | Rule configuration, period lifecycle, generation, adjustments, unit balances | Ledger postings |
| `finance` | Chart of accounts, journal entries, fiscal years, expenses, receipts, reports | Payment gateway details |
| `payment` | Payment intent, transaction state machine, gateway port, allocation | Ledger rules (delegates to finance) |
| `maintenance` | Requests, assignment, comments, resolution | Notifications delivery |
| `announcement` | Announcements and read tracking | Push delivery |
| `reservation` | Facilities, rules, slot conflicts, cancellation policy | Payment capture |
| `voting` | Meetings, votes, eligibility snapshots, ballots, results | Membership mutation |
| `notification` | Templates, in-app records, provider ports, delivery log | Business decisions |
| `subscription` | Plans, building subscriptions, limits, trials | Building ledger |
| `audit` | Append-only audit records, redaction | Everything else |

---

## 5. Inter-Module Communication Rules

1. **Allowed:** call another module's *application service interface* (Java interface in `<module>.application.port`) or react to a published domain event.
2. **Forbidden:** importing another module's entities, repositories, or DTOs; cross-module JPA associations.
3. Entities reference other aggregates by ID only (`UUID`). Joins across modules are done in query projections inside the owning module.
4. Cross-module orchestration lives in the calling module's application service inside one transaction, or via `@TransactionalEventListener(phase = AFTER_COMMIT)` for side effects.
5. Events are in-process Spring events in v1. Publishing is written behind `DomainEventPublisher` so a broker can replace it.
6. Reading foreign aggregates uses explicit "read port" interfaces (e.g. `finance` needs `UnitChargeView` for posting), implemented in the owning module.

### Dependency direction (intended)

```
common ◄── everything
auth ◄── membership ◄── building ◄── unit ◄── occupancy
                              ▲
charge ──► finance ◄── payment
subscription ──► building (read only)
notification ◄── (all, via events)
audit ◄── (all, via events)
```

`finance` must not depend on `payment`; `payment` depends on `finance` through `LedgerPort`.

---

## 6. Multi-Tenancy Model

**Chosen model:** shared schema, explicit `building_id` discriminator, application-enforced isolation, with defense-in-depth at the query level.

Rationale: hundreds of buildings, not hundreds of thousands of tenants; per-schema or per-database isolation adds migration and connection-pool complexity with little benefit at this scale. Isolation is instead enforced structurally.

### Enforcement layers

| Layer | Mechanism |
|---|---|
| Authentication | JWT carries `sub` (user id) and `sid` (session id) only — never roles or building scopes, so revocation is immediate. |
| Resolution | `TenantContext` is resolved per request from the path/body building id and the user's memberships, then validated. |
| Application | `AuthorizationService.require(buildingId, permission)` at the start of every building-scoped use case. |
| Persistence | Repository methods always take `buildingId` as the first parameter; no `findById(id)` on tenant-owned aggregates — `findByIdAndBuildingId`. |
| Database | `building_id NOT NULL` on tenant tables; composite indexes leading with `building_id`; FKs are composite where it prevents cross-tenant linkage (e.g. `unit(building_id, id)` referenced by `unit_charge`). |
| Tests | A dedicated cross-tenant test harness asserts 404/403 for every building-scoped endpoint. |

`PLATFORM_ADMIN` bypasses membership checks but writes audit records and is excluded from building-finance mutation by policy.

### Why not a Hibernate filter alone

Hibernate `@Filter`/`@TenantId` is useful but silently bypassed by native queries and by code paths that forget to enable it. It will be used as an *additional* safety net for entity loads, never as the primary control.

---

## 7. Transaction and Concurrency Strategy

| Concern | Approach |
|---|---|
| Transaction boundary | Application-service method. Controllers never open transactions. |
| Read-only queries | `@Transactional(readOnly = true)`; projections instead of entities for reports. |
| Lost updates | `@Version` on mutable aggregates (unit, building, charge period, payment, subscription, facility). |
| Duplicate charge generation | Unique `(building_id, jalali_year, jalali_month)` on `charge_period` + `INSERT ... ON CONFLICT DO NOTHING` semantics through a repository `saveAndFlush` guarded by constraint; on conflict, return existing. |
| Duplicate payment confirmation | Row-level `SELECT ... FOR UPDATE` on the payment row, then a state-machine transition check; unique `(provider_id, provider_reference)` on transactions. |
| Reservation overlap | PostgreSQL `EXCLUDE USING gist (facility_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)` on confirmed reservations. |
| Balance computations | Derived from ledger sums, never from mutable counters. |
| Deadlock reduction | Consistent lock ordering: charge period → unit charges → payment → journal entry. |
| Long operations | Generation is bounded per period per building; no cross-building batch in a request. |

No distributed transactions in v1. External side effects (SMS, push, gateway calls) happen after commit via `@TransactionalEventListener(AFTER_COMMIT)` and are recorded for retry.

---

## 8. Domain Model (conceptual)

```
User ──< BuildingMembership >── Building ──< Unit ──< UnitOwnership
                                   │           │
                                   │           └──< UnitOccupancy
                                   ├──< ChargeRule ──< ChargeRuleComponent
                                   ├──< ChargePeriod ──< UnitCharge ──< ChargeAdjustment
                                   ├──< FinancialAccount ──< JournalEntryLine >── JournalEntry
                                   ├──< Expense (→ JournalEntry)
                                   ├──< Payment ──< PaymentTransaction ──< PaymentAllocation
                                   ├──< MaintenanceRequest ──< MaintenanceComment
                                   ├──< Announcement ──< AnnouncementRead
                                   ├──< SharedFacility ──< FacilityReservation
                                   ├──< Meeting ──< Vote ──< VoteOption ──< Ballot
                                   ├──< BuildingSubscription >── SubscriptionPlan
                                   └──< AuditLog
```

Aggregates and invariants are detailed in `database-design.md`; the visual ERD is in `erd.md`.

### Aggregate boundaries (transactional consistency)

| Aggregate root | Members changed atomically with it |
|---|---|
| `Building` | address, status, manager assignments (through membership port) |
| `Unit` | parking/storage metadata |
| `UnitOwnership` / `UnitOccupancy` | single record each; share-sum invariant enforced by repository query + constraint |
| `ChargePeriod` | `UnitCharge` rows and `ChargeAdjustment` rows for that period |
| `JournalEntry` | `JournalEntryLine` rows |
| `Payment` | `PaymentTransaction`, `PaymentAllocation` |
| `MaintenanceRequest` | comments, status history, resolution |
| `Meeting` | votes, options, ballots |
| `BuildingSubscription` | state changes and audit |

---

## 9. Cross-Cutting Concerns

### API layer

- All endpoints under `/api/v1`. Controllers are thin: validate → call service → map to response.
- Uniform envelope for single resources; lists use a paginated envelope with `content`, `page`, `size`, `totalElements`, `totalPages`, `sort`.
- `ProblemDetail` (RFC 9457) for errors with a stable `code` extension.
- `X-Request-Id` accepted or generated, echoed, and attached to logs and audit.
- Idempotency: `Idempotency-Key` header honored on `POST` payment and charge-generation endpoints.

### Validation

- Jakarta Bean Validation on DTOs.
- Domain validators for invariants that need repository access (duplicate unit number, share sum, overlapping reservation).
- Persian text normalization (Arabic Yeh/Kaf → Persian, digit normalization) applied at DTO mapping for searchable fields.

### Mapping

- MapStruct, component model `spring`, `unmappedTargetPolicy = ERROR`.
- Entities never appear in controller signatures; enforced by an ArchUnit rule.

### Observability

- Micrometer + Actuator; JSON logs via Logback with request id/user id/building id MDC.
- Actuator: `health` public (no details), `info`, `metrics` restricted; `prometheus` in prod.
- Audit is business-level, not a replacement for logging.

---

## 10. Architecture Enforcement (ArchUnit)

Rules that fail the build:

1. `..api..` must not depend on `..persistence..`.
2. `..domain..` must not depend on `org.springframework.web..` or `org.springframework.data..`.
3. Module `a..` must not access module `b..` internals except `b.application.port..`, `b.dto..` where declared public, and events.
4. No controller may accept or return a type annotated `@Entity`.
5. Repositories are only injected into `application`/`persistence` packages.
6. `finance` must not depend on `payment`.

---

## 11. Key Decisions (ADR summary)

| # | Decision | Alternatives rejected | Why |
|---|---|---|---|
| ADR-01 | Modular monolith in one Maven module with package boundaries | Multi-module Maven, microservices | Faster iteration, one deploy, still enforceable via ArchUnit; splitting later is mechanical. |
| ADR-02 | Shared-schema tenancy with explicit `building_id` and app-level checks | Schema-per-tenant, RLS-only | Scale does not justify it; explicit checks are auditable and testable. |
| ADR-03 | JWT without embedded roles | Roles in JWT | Instant revocation; membership changes take effect immediately. |
| ADR-04 | Immutable double-entry ledger | Mutable balance columns | Auditability and correctness under concurrent payments. |
| ADR-05 | OTP-only authentication in v1 | Password + OTP | Matches Iranian consumer expectations and reduces credential risk. |
| ADR-06 | Integer Rial end to end | Toman decimal amounts | Eliminates rounding ambiguity; display conversion only in clients. |
| ADR-07 | Idempotency keys + DB uniqueness for financial commands | Retry-safe clients only | Network retries are unavoidable on mobile. |
| ADR-08 | In-process events in v1 | Kafka/RabbitMQ | No operational need yet; publisher is already an interface. |
| ADR-09 | `BigDecimal` scale 0 persisted as `NUMERIC(19,0)` | `numeric(19,2)` | Rial has no subunit; scale 2 invites ambiguous half-Rial values. |
| ADR-10 | Liquibase changelogs split per module, timestamped ids | Single monolithic changelog | Reviewable diffs, module ownership, easier rollback reasoning. |

---

## 12. Path to Extraction

If a module must become a service later, these seams already exist:

- Application-service interfaces (call sites become HTTP/gRPC clients).
- Domain events (`DomainEventPublisher`).
- ID-only cross-aggregate references (no shared JPA joins).
- Ledger isolated in `finance` with a narrow `LedgerPort`.

The following would need work: distributed transactions (outbox), shared reference data, and reporting joins.
