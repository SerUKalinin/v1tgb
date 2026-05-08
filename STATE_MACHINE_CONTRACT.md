# 📜 STATE MACHINE CONTRACT v1 — OMS EXECUTION CORE

## 🎯 PURPOSE

This document defines the canonical order state machine for the OMS execution pipeline.

The goal of this contract is to guarantee:

- deterministic execution semantics
- capital safety
- retry-safe execution
- idempotent state transitions
- strict execution ownership
- crash-safe recovery behavior
- reconciliation correctness

This contract is immutable unless explicitly versioned.

---

# 1. STATE DEFINITIONS

The OMS supports the following canonical order states.

| State | Description |
|---|---|
| PENDING | Order created but not yet submitted to exchange |
| EXECUTING | Exchange execution currently in progress |
| UNKNOWN | Exchange outcome unknown due to timeout, disconnect, crash, or ambiguous acknowledgement |
| FILLED | Order fully executed on exchange |
| REJECTED | Order permanently rejected |
| CANCELLED | Order cancelled before execution |

---

# 2. TERMINAL STATES

The following states are terminal and immutable:

- FILLED
- REJECTED
- CANCELLED

Terminal state guarantees:

- terminal states are FINAL
- terminal states are IMMUTABLE
- terminal states cannot transition further
- terminal states cannot be reopened
- terminal states cannot be overridden by reconciliation
- terminal states cannot be bypassed by retry logic

---

# 3. ALLOWED TRANSITIONS

The following transitions are the ONLY valid transitions in the system.

## Execution Flow

PENDING -> EXECUTING

EXECUTING -> FILLED
EXECUTING -> REJECTED
EXECUTING -> UNKNOWN

UNKNOWN -> FILLED
UNKNOWN -> REJECTED
UNKNOWN -> EXECUTING

## Cancellation Flow

PENDING -> CANCELLED

---

# 4. FORBIDDEN TRANSITIONS

Any transition not explicitly allowed is forbidden.

Explicitly forbidden transitions include:

## Terminal Resurrection

FILLED -> *
REJECTED -> *
CANCELLED -> *

## Invalid Execution Recovery

REJECTED -> EXECUTING
FILLED -> EXECUTING
CANCELLED -> EXECUTING

## Invalid Lifecycle Re-entry

FILLED -> PENDING
REJECTED -> PENDING
CANCELLED -> PENDING

UNKNOWN -> PENDING

## Invalid Cancellation

EXECUTING -> CANCELLED
FILLED -> CANCELLED

---

# 5. EXECUTION OWNERSHIP

Execution ownership is mandatory.

Execution processing MUST follow claim-based ownership semantics.

## Rules

- only claimed execution owner may commit execution result
- execution ownership is identified by executionId
- commit phase MUST validate ownership before state mutation
- stale execution attempts MUST be rejected
- concurrent execution attempts MUST NOT mutate order state
- retry execution MUST reuse ownership validation rules

## Ownership Guarantees

The system MUST guarantee:

- single execution authority
- no double execution
- no split-brain execution commits
- deterministic execution result ownership

---

# 6. TIMEOUT SEMANTICS

Exchange timeout NEVER means rejection.

Timeout is NOT execution failure.

Timeout means:

- exchange result is unknown
- exchange acknowledgement may still arrive later
- execution may already have happened remotely
- order state becomes UNKNOWN

## Mandatory Rules

Exchange timeout MUST transition:

EXECUTING -> UNKNOWN

The system MUST NEVER:

- auto-reject timeout orders
- auto-cancel timeout orders
- assume exchange rollback
- release reserved risk automatically on timeout

## UNKNOWN State Guarantees

UNKNOWN state indicates:

- execution ambiguity
- reconciliation required
- execution finality unresolved

UNKNOWN orders remain recoverable only through:

- reconciliation
- exchange status verification
- deterministic recovery flow

---

# 7. RECONCILIATION RULES

Reconciliation is verification-only.

Reconciliation exists to verify external exchange reality against internal OMS state.

## Reconciliation MUST NOT

Reconciliation MUST NOT:

- create order
- bypass state machine
- resurrect terminal state
- mutate immutable terminal state
- fabricate execution result
- overwrite valid ownership
- introduce illegal transitions

## Reconciliation MAY

Reconciliation MAY:

- verify exchange status
- resolve UNKNOWN state
- finalize ambiguous execution result
- synchronize external execution outcome

## Allowed Reconciliation Transitions

UNKNOWN -> FILLED
UNKNOWN -> REJECTED

ONLY if verified by authoritative exchange state.

---

# 8. IDEMPOTENCY REQUIREMENTS

All state transitions MUST be idempotent.

Repeated processing MUST NOT:

- duplicate execution
- duplicate fills
- duplicate reservations
- create inconsistent transitions

## Idempotency Guarantees

The system MUST guarantee:

- same executionId => same result
- duplicate commit => no-op
- retry-safe execution
- crash-safe recovery

---

# 9. CONCURRENCY GUARANTEES

The state machine MUST remain deterministic under concurrency.

## Required Guarantees

- exactly one active execution owner
- serialized state mutation
- optimistic or pessimistic concurrency enforcement
- stale writers rejected
- concurrent retries safe

The system MUST reject:

- concurrent commit races
- duplicate exchange submission
- out-of-order state mutation

---

# 10. CAPITAL SAFETY RULES

Capital safety overrides throughput.

The system MUST prioritize:

1. deterministic execution
2. consistency
3. idempotency
4. correctness
5. observability
6. throughput

The system MUST NEVER:

- release reserved capital before execution finality
- assume rejection without verification
- execute twice
- mutate terminal execution result

---

# 11. CRASH RECOVERY GUARANTEES

The system MUST survive:

- process crash
- network disconnect
- exchange timeout
- duplicate retry
- partial execution acknowledgement

Recovery flow MUST remain:

- deterministic
- idempotent
- ownership-safe
- state-machine compliant

---

# 12. STATE MACHINE AUTHORITY

This document is the canonical authority for:

- order lifecycle
- transition legality
- execution finality
- reconciliation boundaries
- retry semantics
- ownership semantics

All services MUST comply with this contract.

No component may bypass this state machine.