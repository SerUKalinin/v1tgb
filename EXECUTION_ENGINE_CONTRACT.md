# 📜 EXECUTION ENGINE CONTRACT v1 — OMS EXECUTION CORE

## 🎯 PURPOSE

This document defines the canonical execution semantics of the OMS execution engine.

The execution engine is responsible for:

- deterministic exchange execution
- exactly-once execution semantics
- idempotent retries
- ownership-safe execution
- crash-safe recovery
- concurrency-safe state mutation
- capital-safe execution finality

This contract is immutable unless explicitly versioned.

---

# 1. CORE EXECUTION PRINCIPLES

The execution engine MUST guarantee:

- single execution ownership
- deterministic execution flow
- exactly-once execution semantics
- retry-safe processing
- crash-safe recovery
- idempotent commits
- serialized state mutation

The execution engine MUST NEVER:

- execute order twice
- bypass state machine
- mutate terminal state
- commit stale execution
- release capital before finality
- assume exchange timeout means rejection

---

# 2. EXECUTION PIPELINE

Canonical execution flow:

PENDING
→ CLAIMED
→ EXECUTING
→ COMMIT
→ FINAL STATE

Canonical final states:

- FILLED
- REJECTED
- UNKNOWN

Execution pipeline MUST remain linear and deterministic.

No alternative execution path may exist.

---

# 3. CLAIM SEMANTICS

Execution MUST begin with ownership claim.

## Claim Requirements

Claim operation MUST:

- generate executionId
- establish execution ownership
- prevent concurrent execution
- persist ownership atomically

## Claim Guarantees

Exactly one active execution owner per order.

Concurrent claim attempts MUST fail.

Claim MUST be idempotent.

Repeated claim requests for same execution MUST return same ownership state.

---

# 4. EXECUTION OWNERSHIP

Execution ownership is authoritative.

Only current execution owner may:

- execute exchange request
- commit execution result
- mutate execution state
- finalize order

## Ownership Validation

Every commit MUST validate:

- executionId
- ownership validity
- active ownership state
- non-terminal order state

Stale ownership MUST be rejected.

---

# 5. EXECUTION ID RULES

Each execution attempt MUST have unique executionId.

executionId MUST:

- uniquely identify execution attempt
- survive retries
- survive crashes
- be persisted before exchange interaction

executionId MUST be immutable after claim.

---

# 6. EXCHANGE EXECUTION RULES

Exchange interaction MUST occur ONLY after successful ownership claim.

Execution engine MUST:

- submit order exactly once
- persist execution context before exchange call
- treat exchange as non-transactional boundary
- assume exchange response may be delayed or lost

Exchange communication MUST be retry-safe.

---

# 7. TIMEOUT SEMANTICS

Exchange timeout NEVER means rejection.

Timeout means:

- execution outcome unknown
- exchange may still process request
- execution finality unresolved

Mandatory transition:

EXECUTING -> UNKNOWN

Execution engine MUST NEVER:

- auto-reject timeout
- auto-cancel timeout
- auto-release reserved capital
- assume rollback on timeout

UNKNOWN requires reconciliation.

---

# 8. COMMIT PROTOCOL

Commit phase finalizes execution result.

Commit MUST be:

- idempotent
- ownership-validated
- atomic
- state-machine compliant

## Commit Requirements

Commit MUST validate:

- execution ownership
- executionId
- allowed transition
- non-terminal order state

## Commit MUST Persist

- final order state
- execution result
- exchange identifiers
- timestamps
- outbox events
- audit metadata

All within same transactional boundary.

---

# 9. IDEMPOTENCY GUARANTEES

Execution engine MUST support retry-safe execution.

Repeated processing MUST NOT:

- duplicate exchange execution
- duplicate state transition
- duplicate outbox event
- duplicate fill
- duplicate reservation mutation

## Required Guarantees

Same executionId MUST produce same result.

Duplicate commit MUST become no-op.

Retry after crash MUST remain deterministic.

---

# 10. OUTBOX GUARANTEES

Execution result publication MUST use transactional outbox semantics.

Outbox persistence MUST occur:

- within same transaction as state mutation
- after successful commit validation
- exactly once per logical execution result

Outbox MUST support:

- idempotent publishing
- replay safety
- crash recovery

Outbox MUST NEVER:

- publish uncommitted state
- publish duplicate logical event
- bypass execution ownership validation

---

# 11. RECOVERY SEMANTICS

Recovery engine MUST be ownership-aware.

Recovery MUST NOT:

- re-execute committed execution
- override terminal state
- create duplicate execution
- bypass state machine

Recovery MAY:

- resume interrupted execution
- reconcile UNKNOWN state
- retry incomplete commit
- continue valid claimed execution

Recovery MUST remain deterministic.

---

# 12. STALE EXECUTION REJECTION

Stale execution attempts MUST be rejected.

Execution becomes stale when:

- ownership replaced
- order finalized
- executionId invalidated
- newer execution exists

Stale execution MUST NOT:

- commit
- mutate state
- publish outbox event
- release capital

---

# 13. CONCURRENCY GUARANTEES

Execution engine MUST remain safe under concurrency.

The system MUST guarantee:

- single active execution owner
- serialized commit
- atomic ownership validation
- no split-brain execution
- deterministic final state

Concurrent execution attempts MUST fail safely.

---

# 14. CRASH CONSISTENCY

Execution engine MUST survive:

- JVM crash
- database reconnect
- exchange timeout
- partial commit
- duplicate retry
- process restart

Crash recovery MUST preserve:

- ownership correctness
- executionId consistency
- state machine legality
- outbox consistency

---

# 15. CAPITAL SAFETY

Capital safety overrides throughput.

The system MUST prioritize:

1. correctness
2. deterministic execution
3. idempotency
4. consistency
5. recovery safety
6. throughput

The system MUST NEVER:

- release reserved funds before execution finality
- assume failed execution without verification
- finalize ambiguous exchange state incorrectly

---

# 16. EXECUTION FINALITY

Execution finality exists ONLY when:

- terminal state committed
- commit transaction completed
- ownership validated
- outbox persisted

Execution is NOT final when:

- exchange timeout occurred
- UNKNOWN state active
- commit incomplete
- ownership unresolved

---

# 17. STATE MACHINE AUTHORITY

Execution engine MUST fully comply with:

- SYSTEM_CONTRACT.md
- STATE_MACHINE_CONTRACT.md

Execution engine MUST NEVER bypass canonical state machine rules.

All execution logic MUST remain contract-driven.

---

# 18. CANONICAL EXECUTION MODEL

Canonical execution model:

CLAIM
→ EXECUTE
→ COMMIT
→ PUBLISH
→ RECONCILE (if UNKNOWN)

No alternative execution flow is permitted.

This pipeline is the authoritative execution lifecycle of the OMS.