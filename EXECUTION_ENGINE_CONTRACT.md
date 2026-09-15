# EXECUTION ENGINE CONTRACT

**Version:** 1.1
**Status:** Canonical Execution Engine Contract

---

# 1. Purpose

The Execution Engine is responsible for deterministic, idempotent and crash-safe execution of a persisted order against an external exchange.

Its primary guarantees are:

* one execution identity;
* one ownership lifecycle;
* no duplicate exchange submission;
* explicit execution state;
* explicit recovery;
* ownership validation;
* atomic execution commit;
* transactional outbox;
* safe retries.

---

# 2. Canonical Execution Pipeline

The canonical pipeline is:

```text
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
COMMIT
      ↓
OUTBOX DISPATCH
```

`CLAIMED` is not an order state.

It is an ownership operation.

---

# 3. Execution Identity

The execution engine MUST NOT generate the execution identity during the claim step.

The execution identity must already exist before claim.

Canonical identity flow:

```text
signalId
   ↓
orderId
   ↓
executionId
```

The order must persist the execution identity before execution begins.

---

# 4. Execution Ownership

The claim operation establishes ownership of the existing `executionId`.

Conceptually:

```text
order.executionId
        ==
context.executionId
        ==
claimed executionId
```

All three must refer to the same execution identity.

If they do not match:

```text
CLAIM FAILS
NO EXCHANGE I/O
```

---

# 5. Claim Preconditions

Execution claim is allowed only when:

```text
order exists
AND
order is not terminal
AND
order is executable
AND
executionId matches
AND
execution ownership can be established
```

The canonical executable state is:

```text
PENDING_EXECUTION
```

A stale `EXECUTING` order may be reclaimed only through explicit stale-execution recovery rules.

---

# 6. Claim Atomicity

The execution claim must not create a permanent half-state.

The system must guarantee that:

```text
execution ownership
+
order execution transition
```

are coordinated atomically or through an explicitly recoverable protocol.

The following dangerous state is forbidden:

```text
execution claim committed
BUT
order remains PENDING_EXECUTION
```

when the presence of the claim causes all later retries to skip execution.

If such a state can occur, the execution workflow is not crash-safe.

---

# 7. Claim Before Exchange I/O

The exchange may be contacted only after successful execution claim.

Canonical sequence:

```text
LOAD ORDER
    ↓
VALIDATE EXECUTION IDENTITY
    ↓
CLAIM OWNERSHIP
    ↓
COMMIT EXECUTING
    ↓
EXCHANGE I/O
```

No exchange I/O may occur before ownership is established.

---

# 8. Exchange I/O Transaction Rule

The database transaction used to establish execution ownership MUST NOT remain open while waiting for the exchange.

Correct:

```text
TX
  claim
  persist EXECUTING
COMMIT

exchange I/O

TX
  commit result
COMMIT
```

Incorrect:

```text
BEGIN TX

claim
exchange I/O
commit result

COMMIT
```

---

# 9. Execution Result

Exchange results must be normalized into the canonical execution result model.

Possible outcomes include:

```text
FILLED
PARTIALLY_FILLED
REJECTED
CANCELED
ACCEPTED
EXCHANGE_STATE_UNKNOWN
FAILED_IO
```

The execution engine must map these outcomes to the canonical order state machine.

---

# 10. Known Rejection

If the exchange explicitly rejects the order:

```text
EXECUTING
    ↓
REJECTED
```

The rejection MUST NOT later be retried as if the exchange response had been unknown.

---

# 11. Known Cancellation

If the exchange explicitly cancels the order:

```text
EXECUTING / SENT_TO_EXCHANGE / PARTIALLY_FILLED
    ↓
CANCELED
```

Any already executed quantity must remain persisted.

---

# 12. Successful Execution

If the exchange confirms a full fill:

```text
EXECUTING / SENT_TO_EXCHANGE / PARTIALLY_FILLED
    ↓
FILLED
```

The execution commit must persist all authoritative exchange information available at that moment.

Examples:

* exchange order ID;
* executed quantity;
* average execution price;
* execution timestamps;
* execution identity.

---

# 13. Partial Fill

A partial fill is a valid non-terminal outcome:

```text
EXECUTING
    ↓
PARTIALLY_FILLED
```

Repeated partial-fill notifications must be idempotent.

The committed quantity must represent cumulative authoritative execution, not the same fill added repeatedly.

---

# 14. Unknown Outcome

If the exchange response is ambiguous:

```text
EXECUTING
    ↓
UNKNOWN
```

The execution engine MUST NOT convert ambiguity into rejection.

Examples:

* timeout;
* connection reset;
* HTTP gateway timeout;
* process crash after submission;
* exchange response unavailable.

---

# 15. UNKNOWN Recovery

The execution engine MUST hand uncertain executions to reconciliation/recovery.

Canonical flow:

```text
UNKNOWN
    ↓
RECOVERING
    ↓
AUTHORITATIVE EXCHANGE QUERY
    ↓
FILLED / REJECTED / CANCELED
```

If the exchange still cannot provide an authoritative result:

```text
RECOVERING
    ↓
UNKNOWN
```

The engine MUST NOT blindly submit another order while the remote state is unresolved.

---

# 16. Retry Rule

Retries are allowed for processing failures, not for blindly repeating remote execution.

For example:

```text
same event delivery
→ retry processing
```

is safe.

But:

```text
UNKNOWN exchange outcome
→ submit a second remote order
```

is forbidden without explicit proof that the original attempt did not execute.

---

# 17. Ownership Validation Before Commit

Before applying an execution result, the engine MUST validate:

```text
order.executionId == context.executionId
```

and that the execution ownership is still valid.

If ownership is stale or lost:

```text
NO COMMIT
```

The stale executor MUST NOT overwrite the authoritative result.

---

# 18. Execution Commit

Execution commit MUST be atomic from the database perspective.

The commit transaction is responsible for applying, as required:

```text
order state
execution metadata
exchange identifiers
executed quantity
average price
risk settlement
outbox events
execution audit metadata
```

The business mutation and corresponding outbox records MUST commit together.

---

# 19. Transactional Outbox

Execution commit does NOT synchronously publish events to all consumers.

Instead:

```text
EXECUTION COMMIT
      ↓
PERSIST OUTBOX RECORDS
      ↓
DB COMMIT
      ↓
OUTBOX PROCESSOR
      ↓
EVENT DISPATCH
```

Therefore "publish" in the logical execution pipeline means:

> persist the event into the transactional outbox.

Actual consumer dispatch occurs after database commit.

---

# 20. Idempotency

The primary execution idempotency key is:

```text
executionId
```

A duplicate execution delivery MUST NOT create:

* a new exchange submission;
* a new order lifecycle;
* a second risk reservation;
* a second settlement.

---

# 21. Execution Claim Duplicate

If an execution claim already exists for the same execution identity, the system must determine whether the current processing attempt is:

* a duplicate delivery of an already-running execution;
* a completed execution;
* a stale/incomplete execution;
* a recoverable execution attempt.

It MUST NOT blindly interpret "claim exists" as:

```text
execution already completed
```

A claim record proves ownership history, not successful completion.

---

# 22. Crash Safety

The system must remain recoverable after crashes at every boundary.

Relevant crash points include:

```text
before claim
after claim before commit
after EXECUTING persistence
before exchange I/O
after exchange submission
before exchange response
after exchange response
before execution commit
after execution commit
before outbox dispatch
after outbox dispatch
```

The resulting persistent state must permit deterministic recovery.

---

# 23. Crash After Exchange Submission

The most dangerous crash point is:

```text
exchange accepted request
        ↓
process crashes
        ↓
local result not committed
```

The order MUST be recoverable as:

```text
UNKNOWN / recovery-required
```

and reconciliation must query the exchange.

No blind duplicate submission is allowed.

---

# 24. Crash After Execution Commit

If execution commit has succeeded:

```text
terminal/updated order state
+
outbox records
```

must already be durable.

A crash before event dispatch is therefore safe.

The outbox processor will dispatch the event later.

---

# 25. Stale Execution Attempts

A stale executor MUST NOT overwrite a newer authoritative state.

Examples:

```text
old execution result arrives after reconciliation
old worker finishes after another worker committed
duplicate event reprocessed after terminal transition
```

All such attempts must be rejected or reduced to deterministic no-op behavior.

---

# 26. Finality

An execution is final only when:

```text
terminal state committed
AND
execution ownership validated
AND
business state persisted
AND
required outbox records persisted atomically
```

Outbox consumer completion is NOT required for execution finality.

---

# 27. Exchange Order Identity

Exchange order IDs are external identities.

They MUST NOT replace:

```text
signalId
orderId
executionId
eventId
```

They are stored as exchange metadata associated with the execution lifecycle.

---

# 28. Exchange-Agnostic Requirement

The execution core MUST operate against an abstraction such as:

```text
ExecutionPort
```

Exchange-specific details belong to adapters.

Example:

```text
BinanceExecutionAdapter
BybitExecutionAdapter
OkxExecutionAdapter
FakeExecutionAdapter
```

The domain state machine must not contain exchange-specific statuses.

---

# 29. Determinism

For identical persisted state, execution context and exchange result:

```text
resulting state
+
settlement
+
outbox events
```

must be deterministic.

---

# 30. Forbidden Behavior

The execution engine MUST NOT:

* generate a new executionId during claim;
* execute before ownership;
* hold DB transactions across exchange I/O;
* treat UNKNOWN as REJECTED;
* blindly retry UNKNOWN;
* allow stale ownership to commit;
* create a second lifecycle for the same executionId;
* bypass the order state machine;
* bypass risk settlement rules;
* bypass transactional outbox.

---

# 31. Canonical Pipeline

```text
PENDING_EXECUTION
       ↓
VALIDATE IDENTITY
       ↓
CLAIM OWNERSHIP
       ↓
EXECUTING
       ↓
EXCHANGE I/O
       ↓
NORMALIZE RESULT
       ↓
VALIDATE OWNERSHIP
       ↓
EXECUTION COMMIT
       ↓
OUTBOX PERSISTED
       ↓
DB COMMIT
       ↓
OUTBOX DISPATCH
       ↓
PROJECTION / RECONCILIATION
```
