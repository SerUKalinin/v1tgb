Crypto Trading System — Master Engineering Constitution / Active Roadmap

Проект развивается через controlled refactor (strangler approach), не переписывание.
Цель — построение deterministic autonomous financial engine для 24/7 crypto trading, а не обычного trading bot.

=== SYSTEM PRINCIPLES (NON-NEGOTIABLE) ===

1. Risk Engine — mandatory gateway для каждого ордера.
   Никаких bypass через repository или альтернативные пути.

2. Каждый ордер обязан проходить единый атомарный transaction flow:
   Risk -> Reserve Capital -> Create Order -> Write Outbox

3. Database — source of truth:
- FK constraints
- NOT NULL
- unique clientOrderId
- UUID consistency
- financial invariants enforced in DB

4. Idempotency обязательна на всех уровнях:
   API
   Service
   Execution
   Outbox consumers

5. Изменение Order state только через controlled domain services.
   Никаких direct entity mutations.

6. Outbox — единственный event source.
   Spring domain events исключены из финансового ядра.

7. Execution — только внешний side effect.
   Никогда не часть core transaction.

8. Система обязана быть restart-safe в любой точке без потери данных и double execution.

9. Reconciliation — обязательная часть финансового ядра.

10. Никаких новых фич до прохождения financial core stability gates.

------------------------------------------------

=== TARGET ARCHITECTURE ===

Financial Core:
- Risk Engine
- Capital Reservation model
- Order lifecycle aggregate
- Outbox backbone
- Position as derived state
- Reconciliation engine

Side Control Planes later:
- Drawdown / Halt Protection
- Supervisor / self-healing
- Observability
- Scaling later

Execution, Strategy, Notifications изолированы от financial core.

------------------------------------------------

=== CURRENT STATUS ===

System status:
Conditionally Testnet Ready
NOT Micro-Live Ready

Core already strong:
- Risk engine safe (SELECT FOR UPDATE, fail-closed HALT)
- Atomic Risk→Reserve→Order→Outbox
- Core DB invariants present
- DDD layered architecture exists
- Outbox direction adopted
- Execution isolated from transaction

Main current blockers:
1. Outbox concurrency correctness
2. Position row locking
3. Execution idempotency (clientOrderId)
4. Reconciliation engine

These are the active no-deviation priorities.

------------------------------------------------

=== ACTIVE EXECUTION PRIORITY (DO NOT DEVIATE) ===

Only these 4 tasks in order:

1. SKIP LOCKED outbox hardening
- multi-consumer safe processing
- duplicate prevention
- retry-safe consumers

2. Position pessimistic locking
- eliminate race conditions
- safe fill updates
- position consistency

3. Deterministic clientOrderId
- execution idempotency
- unique guarantees
- retry-safe submits

4. Reconciliation engine
   Includes:
- startup reconciliation gate
- order/position/balance reconciliation
- reservation reconciliation
- drift classification
- repair orchestrator
- crash recovery matrix

Nothing else before these four are closed.

------------------------------------------------

=== PHASED ROADMAP ===

Phase 0:
DB constraints
UUID unification
Atomic transaction
Pessimistic locking

Phase 1:
Outbox hardening
Order lifecycle correctness
Event idempotency
Reconciliation

Phase 2:
Drawdown guards
Hard halt gate
Async isolation
Risk hardening

Phase 3:
Observability
Aggregate cleanup
Optional distributed locking later
Scaling later

Phase 4:
Testnet / paper validation

Phase 5:
Micro-live gradual rollout

------------------------------------------------

=== CURRENT EXECUTION FOCUS ===

Immediate focus:
stabilize runtime and testnet readiness BEFORE architectural expansion.

Order of work:
1 SKIP LOCKED
2 Position locking
3 clientOrderId
4 Reconciliation

No strategies.
No scaling.
No distributed locks.
No feature expansion.

Only financial core hardening.

------------------------------------------------

Core system invariant:

No order without risk.
No execution without idempotency.
No trading while truth is uncertain.
Correctness before throughput.
Safety before features.