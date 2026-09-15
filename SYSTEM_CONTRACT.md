# SYSTEM CONTRACT

**Version:** 1.1
**Status:** Canonical System Architecture Contract
**Scope:** Entire trading OMS

---

## 1. Purpose

The system is a deterministic, idempotent, exchange-agnostic trading OMS.

The system must provide:

* deterministic order lifecycle;
* deterministic identity propagation;
* idempotent execution;
* crash-safe execution recovery;
* transactional persistence;
* transactional outbox;
* strict risk control;
* exchange reconciliation;
* separation of domain, application and infrastructure concerns.

The system must remain correct under:

* duplicate delivery;
* retry;
* process restart;
* database restart;
* exchange timeout;
* delayed exchange response;
* concurrent execution attempts;
* stale execution attempts;
* partial fills;
* duplicate outbox delivery.

---

# 2. Architectural Principles

## 2.1 Single Canonical Execution Path

The canonical trading flow is:

```text
Signal
  ↓
Risk Decision
  ↓
Risk Reservation
  ↓
Order Creation
  ↓
Order Persistence
  ↓
Outbox Persistence
  ↓
Execution Claim
  ↓
Execution
  ↓
Execution Commit
  ↓
Outbox Dispatch
  ↓
Projection / Reconciliation
```

No alternative execution path may bypass this lifecycle.

---

# 3. Identity Model

The identity hierarchy is deterministic:

```text
signalId
   ↓
orderId
   ↓
executionId
   ↓
eventId
```

The identities are derived as follows:

```text
orderId      = derive(signalId, "order")

executionId  = derive(orderId, "execution:" + attempt)

eventId      = derive(executionId, "event:" + eventType)
```

`signalId` is the root identity.

All derived identities MUST be deterministic and reproducible.

Retries and recovery MUST NOT generate a new identity for an already existing lifecycle.

---

# 4. Identity Rules

## 4.1 Root Identity

A new root `signalId` may be generated only at the external system entry point.

Root generation is performed by:

```text
IdentityFactory.newRoot()
```

---

## 4.2 Derived Identity

Derived identities MUST be produced only by `IdentityFactory`.

The following identities MUST NOT be generated with arbitrary UUID calls:

* orderId;
* executionId;
* eventId.

---

## 4.3 Execution Identity

`executionId` is the global idempotency key for one execution attempt.

Exactly one persisted execution lifecycle belongs to one `executionId`.

An execution attempt MUST NOT generate a new `executionId` during claim.

The execution identity MUST already be known and persisted before execution ownership is claimed.

---

## 4.4 Event Identity

`eventId` is event-specific.

Multiple events belonging to one execution lifecycle therefore have different event IDs:

```text
executionId
    ├── ORDER_CREATED → eventId A
    ├── ORDER_EXECUTED → eventId B
    ├── TRADE_CREATED  → eventId C
    └── ...
```

Therefore:

```text
eventId != executionId
```

in the general case.

---

# 5. Execution Context

`ExecutionContext` is the canonical runtime carrier of execution identity.

It contains the identity required to continue the execution lifecycle.

Identity must flow through:

```text
Signal
→ Order
→ ExecutionContext
→ Execution Claim
→ Execution
→ Execution Commit
→ Outbox
```

Infrastructure adapters MUST NOT create replacement identities.

Recovery MUST reuse persisted identities.

---

# 6. Risk Model

Risk consists of two conceptually different responsibilities.

## 6.1 Risk Decision

The risk policy/decision layer determines whether a signal is allowed.

Conceptually:

```text
Signal
→ Risk Policy
→ Risk Decision
```

The decision itself must be deterministic for identical input state.

---

## 6.2 Risk State Mutation

Risk orchestration may mutate persistent risk state through explicit operations such as:

* reserve;
* release;
* consume reservation;
* credit proceeds;
* synchronize balance;
* emergency halt.

Therefore the rule is:

> Risk policy/decision logic is pure; risk state orchestration is stateful.

The term "RiskEngine" MUST NOT be interpreted as requiring the entire risk subsystem to be immutable or side-effect free.

---

# 7. Order Creation Invariant

An order MUST NOT be created unless the risk decision explicitly allows it.

Canonical rule:

```text
RiskDecision.APPROVED
        ↓
Risk Reservation
        ↓
Order Creation
```

If risk rejects the signal:

```text
NO ORDER
```

must be created.

---

# 8. Order / Execution Invariant

The fundamental relationship is:

```text
one Order
    →
one executionId
    →
one execution lifecycle
```

The persisted `executionId` belongs to the order lifecycle.

Execution retries operate on the same identity.

---

# 9. Execution Ownership

Before external exchange I/O occurs, the system MUST establish execution ownership.

Ownership MUST be based on:

```text
executionId
```

and must be validated against the persisted order.

An execution attempt with an identity different from the persisted order execution identity MUST NOT execute exchange I/O.

---

# 10. Transaction Boundaries

The system uses explicit transaction boundaries.

### Transaction A — Risk

Responsible for:

* risk evaluation;
* reservation mutation;
* persistence of risk state;
* risk events/outbox records related to the reservation.

---

### Transaction B — Order Creation

Responsible for:

* order creation;
* persistence of order;
* persistence of `ORDER_CREATED` outbox event.

Order state and outbox record MUST become durable atomically.

---

### Transaction C — Execution Claim

Responsible for:

* establishing execution ownership;
* transitioning the order to `EXECUTING`;
* persisting the ownership/claim information required for idempotency.

Execution ownership and the corresponding order execution transition SHOULD be atomic.

A partially committed ownership state that can permanently block execution is forbidden.

---

### Transaction D — Execution Commit

Responsible for:

* validating execution ownership;
* applying the exchange result;
* persisting final/updated order state;
* updating execution metadata;
* applying risk settlement/compensation;
* persisting resulting outbox events.

The business state and its outbox records MUST commit atomically.

---

# 11. Transactional Outbox

The system uses the transactional outbox pattern.

The rule is:

```text
Business State Mutation
        +
Outbox Record
        ↓
Same DB Transaction
        ↓
Atomic Commit
```

An outbox event MUST be persisted in the same transaction as the state change it describes.

The event is NOT required to be dispatched to the consumer during the same transaction.

Instead:

```text
DB COMMIT
   ↓
Outbox Processor
   ↓
Event Dispatch
```

Therefore:

> Persisting an outbox record and dispatching an outbox message are two different operations.

---

# 12. Exchange I/O

External exchange I/O MUST occur outside the database transaction that protects business state.

Canonical sequence:

```text
CLAIM
  ↓
DB COMMIT
  ↓
EXCHANGE I/O
  ↓
RESULT
  ↓
COMMIT RESULT
```

The system MUST NOT hold a database transaction open while waiting for the exchange.

---

# 13. Exchange Failure Model

A failure of exchange I/O MUST NOT automatically mean that the exchange rejected the order.

The following must be distinguished:

```text
Known Exchange Rejection
        ↓
REJECTED

Known Exchange Cancellation
        ↓
CANCELED

Successful Exchange Execution
        ↓
FILLED / PARTIALLY_FILLED

Uncertain Exchange Outcome
        ↓
UNKNOWN
```

Network timeout, connection loss, process crash after submission, or any other ambiguity that cannot prove whether the exchange accepted the order MUST be treated as uncertain.

---

# 14. UNKNOWN Safety Rule

`UNKNOWN` means:

> The local system cannot prove whether the exchange has executed or accepted the order.

Therefore the system MUST NOT blindly re-submit an order from `UNKNOWN`.

Recovery MUST first establish the authoritative exchange status.

A new external submission is permitted only if the system has explicit proof that no remote execution exists and the retry policy explicitly allows it.

---

# 15. Terminal State Invariant

The following states are terminal:

```text
FILLED
REJECTED
CANCELED
ERROR
```

A terminal state MUST NOT be mutated into a non-terminal state by ordinary execution.

Any exceptional administrative repair must be explicit and auditable.

---

# 16. Partial Fill

`PARTIALLY_FILLED` is a valid non-terminal execution state.

The system MUST preserve:

* cumulative executed quantity;
* average execution price where available;
* exchange order identity;
* execution identity;
* lifecycle state.

A partial fill may transition to:

```text
FILLED
CANCELED
REJECTED
PARTIALLY_FILLED
```

according to the state machine contract.

---

# 17. Idempotency

Duplicate delivery of the same execution event MUST NOT create a second execution.

For the same:

```text
executionId
```

the system MUST produce at most one execution lifecycle.

Repeated processing of an already committed execution MUST be a no-op or deterministic replay.

---

# 18. Domain Rules

The domain layer MUST NOT depend on:

* Spring;
* Spring transactions;
* JPA;
* Hibernate;
* database APIs;
* network I/O;
* exchange APIs;
* Telegram APIs;
* infrastructure adapters;
* random UUID generation.

The domain layer MUST represent business rules and state transitions.

---

# 19. Application Layer

The application layer is responsible for:

* orchestration;
* transaction boundaries;
* coordination of domain operations;
* calling ports;
* coordinating persistence and outbox;
* execution workflow.

Application services MUST NOT bypass domain invariants.

---

# 20. Infrastructure Layer

Infrastructure is responsible for:

* JPA;
* PostgreSQL;
* Binance/Bybit/OKX adapters;
* Telegram;
* messaging;
* scheduling;
* serialization;
* external APIs.

Infrastructure MUST NOT invent business identities or bypass application/domain rules.

---

# 21. Repository Rule

Persistence MUST go through declared repository ports/adapters.

Application code MUST NOT directly manipulate JPA repositories as an architectural shortcut.

---

# 22. Single Source of Truth

Persistent database state is the source of truth for:

* order lifecycle;
* execution identity;
* execution ownership;
* risk reservations;
* risk balance;
* execution settlement;
* recovery state.

In-memory state may be used as a cache or optimization only.

It MUST NOT become the authoritative source of capital or order lifecycle.

---

# 23. Canonical Execution Lifecycle

The canonical lifecycle is:

```text
SIGNAL
  ↓
RISK DECISION
  ↓
RISK RESERVATION
  ↓
ORDER CREATION
  ↓
ORDER PERSISTENCE
  ↓
OUTBOX PERSISTENCE
  ↓
PENDING_EXECUTION
  ↓
CLAIM OWNERSHIP
  ↓
EXECUTING
  ↓
EXCHANGE I/O
  ↓
EXECUTION RESULT
  ↓
EXECUTION COMMIT
  ↓
OUTBOX PERSISTENCE
  ↓
ASYNC DISPATCH
  ↓
PROJECTIONS / RECONCILIATION
```

No competing canonical lifecycle may exist.

---

# 24. Architecture Priority

When implementation conflicts with this contract, the correct response is:

1. identify the inconsistency;
2. update the contract intentionally if the design changed;
3. update implementation;
4. add/adjust tests;
5. verify runtime invariants.

The implementation MUST NOT silently redefine the contract.
