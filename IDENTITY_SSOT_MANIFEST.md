# IDENTITY SSOT MANIFEST

**Version:** 1.1
**Status:** Canonical / Locked Identity Rules

---

# 1. Purpose

This document defines the Single Source of Truth for all business and execution identities.

Identity generation and propagation MUST remain deterministic and consistent across:

* domain;
* application;
* persistence;
* execution;
* outbox;
* recovery;
* reconciliation.

---

# 2. Identity Hierarchy

The identity hierarchy is:

```text
signalId
   ↓
orderId
   ↓
executionId
   ↓
eventId
```

---

# 3. Root Identity

`signalId` is the root identity of a trading lifecycle.

A new root identity may be created only at the external entry point.

The canonical generator is:

```text
IdentityFactory.newRoot()
```

The implementation may use a random UUID here because this is the creation of a new root lifecycle.

---

# 4. Order Identity

The order identity is deterministically derived from the signal identity.

```text
orderId = IdentityFactory.deriveOrder(signalId)
```

Equivalent conceptual derivation:

```text
orderId = derive(signalId, "order")
```

For one `signalId`, the resulting order identity must be stable.

---

# 5. Execution Identity

The execution identity is deterministically derived from the order identity and execution attempt.

```text
executionId =
    IdentityFactory.deriveExecution(orderId, attempt)
```

Equivalent conceptual derivation:

```text
executionId = derive(orderId, "execution:" + attempt)
```

The execution identity MUST be established before execution claim.

---

# 6. Event Identity

Each event receives its own deterministic event identity.

```text
eventId =
    IdentityFactory.deriveEventId(executionId, eventType)
```

Equivalent conceptual derivation:

```text
eventId = derive(executionId, "event:" + eventType)
```

Therefore:

```text
eventId != executionId
```

in general.

---

# 7. Event Identity Example

For:

```text
executionId = E
```

the lifecycle may contain:

```text
ORDER_CREATED
ORDER_EXECUTED
TRADE_CREATED
CAPITAL_RESERVED
CAPITAL_CONSUMED
```

and each event has its own identity:

```text
event(E, ORDER_CREATED)
event(E, ORDER_EXECUTED)
event(E, TRADE_CREATED)
event(E, CAPITAL_RESERVED)
event(E, CAPITAL_CONSUMED)
```

This guarantees deterministic per-event identity without reusing the execution identity.

---

# 8. Identity Factory

All deterministic identity derivation MUST go through:

```text
IdentityFactory
```

The canonical operations are:

```text
newRoot()
derive()
deriveOrder()
deriveExecution()
deriveEventId()
```

No infrastructure component may implement a second identity derivation algorithm.

---

# 9. UUID Rules

`UUID.randomUUID()` is allowed only for creation of a new root identity.

Forbidden elsewhere:

```text
Order identity
Execution identity
Event identity
Recovery identity
Retry identity
Persistence reconstruction
Outbox identity
```

These MUST be deterministic.

---

# 10. Recovery Rule

Recovery MUST NEVER generate a replacement identity.

Example:

```text
persisted order.executionId = E
```

After restart:

```text
reconstructed order.executionId = E
```

not:

```text
new UUID
```

---

# 11. Retry Rule

Retry MUST preserve the existing execution identity when continuing the same lifecycle.

Example:

```text
executionId = E
```

Retrying processing of the same lifecycle remains:

```text
executionId = E
```

A retry does not create:

```text
executionId = E2
```

unless the system explicitly creates a new execution attempt according to the declared attempt model.

---

# 12. ExecutionContext

`ExecutionContext` is the canonical runtime carrier of execution identity.

Identity must flow through the system using the context model rather than manual re-derivation at arbitrary layers.

The execution context may contain:

```text
IdentityContext
ExecutionAttemptContext
BusinessContext
```

according to the execution context architecture.

---

# 13. Context Integrity

The following relationship must remain true:

```text
context.executionId
==
order.executionId
```

before execution begins.

At execution commit, the same identity must still be validated.

---

# 14. Persistence Rule

Persistence adapters may:

* store identities;
* restore identities;
* map identities.

Persistence adapters MUST NOT:

* generate replacement identities;
* derive new identities;
* silently repair missing identities.

If a persisted required identity is absent:

```text
FAIL CLOSED
```

rather than inventing one.

---

# 15. Domain Rule

The domain must consume identities.

It must not obtain them from:

* Spring;
* JPA;
* database sequences;
* infrastructure;
* exchange APIs;
* random UUID generation.

Identity generation belongs to the explicit identity factory.

---

# 16. Entity Rule

Identity fields should be treated as lifecycle identities, not arbitrary mutable attributes.

The following identities must be immutable after their canonical creation:

```text
signalId
orderId
executionId
```

No generic setter-based reassignment is allowed as a normal business operation.

---

# 17. Outbox Rule

Outbox records must preserve the identity chain:

```text
signalId
orderId
executionId
eventId
```

Each event must retain the identity of the lifecycle it belongs to.

The expected relation is:

```text
event.executionId == executionId
event.eventId     == derive(executionId, eventType)
```

not:

```text
event.eventId == executionId
```

---

# 18. Causation and Correlation

Where supported by the event model:

### Correlation ID

Groups events belonging to the same business lifecycle.

Canonical root:

```text
signalId
```

### Causation ID

Identifies the identity of the event or command that caused the current event.

The exact field semantics must remain consistent across all event producers and consumers.

---

# 19. Identity Propagation

Canonical propagation:

```text
External Entry
      ↓
signalId
      ↓
Order Creation
      ↓
orderId
      ↓
Execution Creation
      ↓
executionId
      ↓
ExecutionContext
      ↓
Execution Commit
      ↓
eventId
```

No stage may silently replace an upstream identity.

---

# 20. No Identity Regeneration

The following are forbidden:

```text
retry → new orderId
retry → new executionId
recovery → new executionId
reconstruction → new executionId
outbox retry → new eventId
same event replay → new eventId
```

All identities must remain deterministic.

---

# 21. Missing Identity Policy

If a mandatory identity is missing:

```text
FAIL CLOSED
```

Do not:

```text
generate fallback UUID
derive from timestamp
derive from database ID
derive from exchange order ID
derive from object hash
```

---

# 22. Exchange IDs

Exchange-generated identifiers such as:

```text
exchangeOrderId
tradeId
execution report ID
```

are external identities.

They MUST NOT replace:

```text
signalId
orderId
executionId
eventId
```

They may only be stored as external metadata.

---

# 23. Identity Immutability

Once persisted:

```text
signalId
orderId
executionId
```

must remain stable for the lifecycle.

Only event identity changes per distinct event.

---

# 24. Canonical Identity Equation

The complete identity model is:

```text
signalId = root identity

orderId = derive(signalId, "order")

executionId = derive(orderId, "execution:" + attempt)

eventId = derive(executionId, "event:" + eventType)
```

This equation is canonical.

---

# 25. Architectural Rule

Any code path that:

* generates an identity;
* derives an identity;
* replaces an identity;
* reconstructs an identity;

outside the canonical identity mechanism is a contract violation.

---

# 26. Locked Rule

This manifest is the Single Source of Truth for identity semantics.

Any change to identity generation or propagation must update this document first and then update:

```text
SYSTEM_CONTRACT.md
EXECUTION_ENGINE_CONTRACT.md
STATE_MACHINE_CONTRACT.md
IdentityFactory
ExecutionContext model
persistence mappings
architecture tests
```

No silent identity semantics changes are permitted.
