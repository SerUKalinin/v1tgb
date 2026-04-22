# SYSTEM RECOVERY DOCUMENT (Crypto Trading System)

## 0. CURRENT STATE

System is currently in a BROKEN / NON-BUILDABLE state.

Key issues: - Java ID model mismatch (String vs DB BIGINT/UUID) -
Missing or incomplete FK constraints in DB - Domain and Infrastructure
layers are mixed (JPA leaking into domain) - Outbox pattern is
non-atomic and unreliable - Risk state stored in-memory
(RiskStateStore), not durable - Execution layer is not fully isolated
from financial core

------------------------------------------------------------------------

## 1. REFACTOR STRATEGY (CRITICAL PRINCIPLE)

We do NOT rewrite the system.

We perform controlled refactor (strangler approach):

1.  Make system buildable
2.  Restore data consistency
3.  Extract financial core
4.  Enforce atomic transaction model
5.  Fix reliability layer (Outbox)
6.  Stabilize Risk persistence
7.  Fully isolate execution layer

------------------------------------------------------------------------

## 2. PHASE 1 --- MAKE SYSTEM BUILDABLE

Goal: system must compile and start without runtime failures.

### Tasks:

-   Unify ID model across Java and DB
    -   Preferred: UUID everywhere
-   Fix OrderEntity vs DB schema mismatch
-   Align Flyway migrations with entities
-   Add missing FK constraints
-   Enable: spring.jpa.hibernate.ddl-auto=validate

------------------------------------------------------------------------

## 3. PHASE 2 --- DATABASE CONSISTENCY

Goal: DB becomes single source of truth.

### Tasks:

-   Add FK constraints:
    -   orders → users
    -   trades → orders
-   Add NOT NULL constraints where needed
-   Fix idempotency table relations
-   Ensure schema matches domain model exactly

------------------------------------------------------------------------

## 4. PHASE 3 --- FINANCIAL CORE EXTRACTION

Goal: isolate money logic from infrastructure.

### Create module:

com.trading.core.financial

Includes: - Order (domain aggregate) - RiskState (pure domain model) -
Capital model - RiskEngine (business logic only)

### Rules:

-   NO JPA
-   NO repositories
-   NO REST
-   NO messaging

------------------------------------------------------------------------

## 5. PHASE 4 --- ATOMIC TRANSACTION MODEL

Goal: ensure financial safety.

### SINGLE TRANSACTION FLOW:

Risk → Reserve Capital → Create Order → Write Outbox

### Rules:

-   One @Transactional boundary only
-   Risk MUST be locked via DB (SELECT FOR UPDATE)
-   No async inside core transaction

------------------------------------------------------------------------

## 6. PHASE 5 --- OUTBOX RELIABILITY FIX

Goal: guarantee delivery of financial events.

### Fixes:

-   Outbox write must be part of SAME transaction as Order creation
-   Outbox processing uses: SELECT ... FOR UPDATE SKIP LOCKED
-   Make processing idempotent
-   Ensure retry safety

------------------------------------------------------------------------

## 7. PHASE 6 --- RISK PERSISTENCE FIX

Goal: eliminate in-memory risk state risk.

### Replace:

-   AtomicReference RiskStateStore ❌

### With:

-   DB-backed RiskState ✔
-   Optional event log (future enhancement)

------------------------------------------------------------------------

## 8. PHASE 7 --- EXECUTION ISOLATION

Goal: external systems must not affect financial core.

### Rules:

-   Execution NEVER inside transaction
-   Execution is async side-effect only
-   Outbox → Execution → Reconciliation

------------------------------------------------------------------------

## 9. FINAL TARGET ARCHITECTURE

Financial system becomes:

Strategy ↓ Application Service ↓ Financial Core (atomic) ↓ Outbox
(reliability) ↓ Execution (external adapter) ↓ Reconciliation loop

------------------------------------------------------------------------

## 10. NON-NEGOTIABLE RULES

-   Risk engine is mandatory gate for all orders
-   No bypass of financial core via repositories
-   All state changes go through domain services
-   System must survive restart at any time
-   Idempotency is required everywhere
-   No feature development before core stability

------------------------------------------------------------------------

## 11. SUCCESS CRITERIA

System is considered stable when:

-   Project compiles and starts cleanly
-   No Java/DB mismatch exists
-   Risk flow is deterministic
-   Outbox guarantees delivery
-   No double execution possible
-   System survives crash without data corruption
