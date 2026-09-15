# STATE MACHINE CONTRACT

**Version:** 1.1
**Status:** Canonical Order State Machine
**Scope:** Order lifecycle

---

# 1. Purpose

This document defines the canonical state machine for an order.

The state machine is the Single Source of Truth for legal lifecycle transitions.

No service, adapter, scheduler, recovery process or event handler may invent an undocumented transition.

---

# 2. Canonical States

The canonical order states are:

```text
NEW
VALIDATED
PENDING_EXECUTION
EXECUTING
SENT_TO_EXCHANGE
PARTIALLY_FILLED
FILLED
REJECTED
CANCELED
UNKNOWN
RECOVERING
ERROR
```

---

# 3. State Categories

## 3.1 Initial States

```text
NEW
VALIDATED
```

These states represent order creation and validation before execution.

---

## 3.2 Execution States

```text
PENDING_EXECUTION
EXECUTING
SENT_TO_EXCHANGE
PARTIALLY_FILLED
UNKNOWN
RECOVERING
```

These states represent an execution lifecycle that has not yet reached terminal completion.

---

## 3.3 Terminal States

```text
FILLED
REJECTED
CANCELED
ERROR
```

Terminal means:

> The execution lifecycle is complete and ordinary execution may not continue.

---

# 4. Allowed Transitions

The canonical transitions are:

```text
NEW
 ├──> VALIDATED
 ├──> PENDING_EXECUTION
 └──> REJECTED
```

```text
VALIDATED
 ├──> PENDING_EXECUTION
 └──> REJECTED
```

```text
PENDING_EXECUTION
 ├──> EXECUTING
 ├──> CANCELED
 └──> REJECTED
```

```text
EXECUTING
 ├──> EXECUTING
 ├──> SENT_TO_EXCHANGE
 ├──> PARTIALLY_FILLED
 ├──> FILLED
 ├──> REJECTED
 ├──> CANCELED
 └──> UNKNOWN
```

```text
SENT_TO_EXCHANGE
 ├──> PARTIALLY_FILLED
 ├──> FILLED
 ├──> REJECTED
 └──> CANCELED
```

```text
PARTIALLY_FILLED
 ├──> PARTIALLY_FILLED
 ├──> FILLED
 ├──> CANCELED
 └──> REJECTED
```

```text
UNKNOWN
 └──> RECOVERING
```

```text
RECOVERING
 ├──> FILLED
 ├──> REJECTED
 ├──> CANCELED
 └──> UNKNOWN
```

Terminal states:

```text
FILLED       → no transition
REJECTED     → no transition
CANCELED     → no transition
ERROR        → no transition
```

---

# 5. Execution Ownership

A transition into:

```text
EXECUTING
```

represents the establishment of execution ownership.

Ownership is not a separate order state.

Therefore:

```text
CLAIMED
```

is NOT a canonical `OrderStatus`.

It is an execution ownership condition.

The system MAY internally represent a claim record, but it MUST NOT introduce `CLAIMED` as an alternative lifecycle state unless this document is explicitly revised.

---

# 6. PENDING_EXECUTION → EXECUTING

This is the canonical first execution transition.

Before exchange I/O:

```text
PENDING_EXECUTION
        ↓
EXECUTING
        ↓
EXCHANGE I/O
```

Exchange I/O MUST NOT occur while the order remains merely `PENDING_EXECUTION`.

---

# 7. EXECUTING → UNKNOWN

`UNKNOWN` is entered when the local system cannot establish the exchange outcome.

Examples:

* network timeout after submission;
* connection loss;
* process crash after exchange submission;
* ambiguous exchange response;
* exchange API unavailable while execution result is unresolved.

The system MUST NOT map every I/O exception to `REJECTED`.

---

# 8. UNKNOWN Safety Rule

`UNKNOWN` MUST NOT be treated as an ordinary retryable state.

The meaning is:

```text
REMOTE RESULT UNKNOWN
```

Therefore:

```text
UNKNOWN
   ↓
RECOVERING
   ↓
QUERY AUTHORITATIVE EXCHANGE STATE
```

Recovery must determine the actual exchange state.

Blind resubmission from `UNKNOWN` is forbidden.

---

# 9. RECOVERING

`RECOVERING` means that the system is actively resolving an unknown execution outcome.

`RECOVERING` exists to make the recovery process explicit and observable.

The recovery process may resolve the order to:

```text
FILLED
REJECTED
CANCELED
UNKNOWN
```

A return to `UNKNOWN` means:

> The recovery attempt itself did not obtain a sufficiently authoritative answer.

---

# 10. UNKNOWN Must Not Become EXECUTING Directly

The following is forbidden as a normal recovery transition:

```text
UNKNOWN → EXECUTING
```

because the exchange may already have accepted or filled the order.

A retry from `UNKNOWN` could therefore create a duplicate remote order.

Any exceptional retry after UNKNOWN requires explicit evidence that the previous remote attempt did not execute and a policy that authorizes a new execution attempt.

---

# 11. PARTIALLY_FILLED

`PARTIALLY_FILLED` is a non-terminal state.

It represents:

```text
0 < executedQuantity < requestedQuantity
```

The state must preserve cumulative execution data.

Repeated exchange notifications that do not increase cumulative executed quantity MUST be idempotent.

---

# 12. Partial Fill Completion

The canonical completion path is:

```text
PARTIALLY_FILLED
      ↓
FILLED
```

When the order is canceled after partial execution:

```text
PARTIALLY_FILLED
      ↓
CANCELED
```

The already executed quantity MUST remain part of the execution result.

---

# 13. Terminal States

## FILLED

The complete requested execution is confirmed.

No additional execution is permitted.

---

## REJECTED

The exchange or validated execution path explicitly rejected the order.

No successful execution may later be performed under the same lifecycle.

---

## CANCELED

The order is explicitly canceled and will not continue execution.

If partial execution occurred before cancellation, the executed quantity remains persisted.

---

## ERROR

`ERROR` represents a terminal local/system failure that has been explicitly committed as terminal.

`ERROR` MUST NOT be used as a substitute for `UNKNOWN`.

If the remote exchange outcome is uncertain:

```text
UNKNOWN
```

must be used instead.

---

# 14. Stale Execution

An execution attempt is stale when:

* the order is terminal;
* the execution identity does not match;
* the execution ownership is no longer valid;
* the execution attempt is superseded by an authoritative recovery result.

A stale execution MUST NOT mutate the order.

---

# 15. Execution Identity

Every lifecycle mutation requiring execution context MUST validate:

```text
context.executionId
==
order.executionId
```

Mismatch means:

```text
NO MUTATION
NO EXCHANGE I/O
```

---

# 16. Idempotency

For the same execution identity:

```text
executionId
```

repeated delivery MUST NOT create another execution.

Examples:

```text
same FILLED result
→ no-op

same REJECTED result
→ no-op

same PARTIALLY_FILLED cumulative quantity
→ no-op
```

---

# 17. Exchange Status Mapping

Exchange-specific statuses MUST be converted into the canonical state machine.

The domain must not depend on raw Binance/Bybit/OKX status names.

Example mapping:

```text
EXCHANGE FILLED
      ↓
FILLED
```

```text
EXCHANGE PARTIALLY_FILLED
      ↓
PARTIALLY_FILLED
```

```text
EXCHANGE REJECTED
      ↓
REJECTED
```

```text
EXCHANGE CANCELED
      ↓
CANCELED
```

```text
AMBIGUOUS EXCHANGE RESULT
      ↓
UNKNOWN
```

---

# 18. Reconciliation Rule

Reconciliation is authoritative for resolving uncertain exchange state.

Canonical flow:

```text
UNKNOWN
   ↓
RECOVERING
   ↓
EXCHANGE QUERY
   ↓
AUTHORITATIVE RESULT
```

Reconciliation MUST NOT invent a result that is not supported by exchange evidence.

---

# 19. State Mutation Authority

Normal state transitions may be performed only by the component responsible for the corresponding lifecycle phase.

Execution commit MUST verify execution ownership.

A stale or foreign execution context cannot commit a result.

---

# 20. Forbidden Transitions

The following are forbidden:

```text
FILLED → anything
REJECTED → anything
CANCELED → anything
ERROR → anything
```

and:

```text
UNKNOWN → EXECUTING
```

without explicit verified proof that the original remote attempt did not execute.

Also forbidden:

```text
PENDING_EXECUTION → FILLED
```

without an execution lifecycle having been established.

---

# 21. Canonical Execution Flow

```text
PENDING_EXECUTION
        ↓
   CLAIM OWNERSHIP
        ↓
     EXECUTING
        │
        ├──> SENT_TO_EXCHANGE
        │        │
        │        ├──> PARTIALLY_FILLED
        │        │        └──> FILLED
        │        │
        │        ├──> FILLED
        │        ├──> REJECTED
        │        └──> CANCELED
        │
        ├──> PARTIALLY_FILLED
        │        ├──> PARTIALLY_FILLED
        │        ├──> FILLED
        │        └──> CANCELED
        │
        ├──> FILLED
        ├──> REJECTED
        ├──> CANCELED
        └──> UNKNOWN
                 ↓
             RECOVERING
                 ↓
        FILLED / REJECTED / CANCELED
                 OR
              UNKNOWN
```

---

# 22. Contract Rule

Any implementation transition not represented here is considered a contract violation until this document is intentionally revised.
