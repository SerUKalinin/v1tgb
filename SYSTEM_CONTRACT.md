# 📜 SYSTEM CONTRACT v1 — OMS / EXECUTION PIPELINE

## 🎯 ЦЕЛЬ СИСТЕМЫ

Система предназначена для:

Deterministic, idempotent execution of trading orders based on incoming signals with strict risk control and guaranteed single execution.

---

# 🧱 1. ГЛОБАЛЬНЫЕ ИНВАРИАНТЫ

## 💣 INVARIANT #1 — ORDER CREATION

Order может быть создан ТОЛЬКО если RiskEngine одобрил Signal.

❌ Запрещено:
- создание Order без RiskDecision

---

## 💣 INVARIANT #2 — EXECUTION UNIQUENESS

Один Order → один executionId → один execution lifecycle.

executionId = global idempotency key.

---

## 💣 INVARIANT #3 — SINGLE ENTRY FLOW

Signal → ONLY ONE canonical processing path.

❌ Запрещено:
- обход SignalHandler
- альтернативные entry points

---

## 💣 INVARIANT #4 — RISK IS PURE

RiskEngine НЕ изменяет состояние системы.

✔ Только decision:
- APPROVE
- REJECT
- REDUCE

---

## 💣 INVARIANT #5 — SIDE EFFECTS ONLY IN INFRASTRUCTURE

Domain не выполняет IO операции.

---

# 🔁 2. ПОЛНЫЙ LIFECYCLE

## STEP 1 — SIGNAL ENTRY

SignalHandler получает Signal.

---

## STEP 2 — RISK EVALUATION

RiskDecision = RiskEngine.evaluate(Signal, RiskState)

---

## STEP 3 — RISK RESERVATION

Если APPROVED:

RiskState.reserve(signalId, amount)

---

## STEP 4 — ORDER CREATION

Order создаётся только после успешного reserve.

Order содержит:
- orderId
- executionId
- signalId (idempotency key)

---

## STEP 5 — ORDER PERSISTENCE

OrderRepositoryPort.save(Order)

---

## STEP 6 — OUTBOX EVENT

Outbox event создаётся ПОСЛЕ commit транзакции.

---

## STEP 7 — EXECUTION PHASE

Claim → Execute → Commit

---

### 🔒 EXECUTION RULES

#### Claim
Order атомарно блокируется для исполнения.

#### Execute
ExecutionPort выполняет ордер во внешней системе.

#### Commit
Order помечается как EXECUTED.

---

# 🧠 3. IDENTITY & IDEMPOTENCY MODEL

## PRIMARY KEYS

- Signal → signalId
- Order → orderId
- Execution → executionId

---

## IDEMPOTENCY RULE

Один signalId → ровно один Order.

---

## EXECUTION IDEMPOTENCY

executionId гарантирует exactly-once execution.

---

# ⚠️ 4. FAILURE MODEL

## FAILURE AT RISK STAGE
→ STOP, Order не создаётся

## FAILURE AT RESERVATION
→ retry safe, Order не создаётся

## FAILURE AT ORDER SAVE
→ должен быть safe retry (no double reserve)

## FAILURE AT EXECUTION
→ retry через executionId, без повторного execution

---

# 🔐 5. TRANSACTION BOUNDARIES

## TX1
Risk evaluation + reservation

## TX2
Order creation + persistence + outbox

## TX3
Execution lifecycle (claim/execute/commit)

---

# 🧱 6. ARCHITECTURAL RULES

## ❌ FORBIDDEN
- domain → infrastructure
- application → JPA / Entity
- execution logic в domain services
- stateful RiskEngine

## ✔ REQUIRED
- IO только через ports
- application = orchestration only
- infrastructure = adapters only

---

# 🧨 7. SYSTEM GUARANTEES

- no double order creation
- no double execution
- deterministic risk decisions
- retry-safe pipeline
- no lost events (Outbox)

---

# 📊 8. FINAL MODEL

Signal  
→ RiskEngine (pure)  
→ RiskState.reserve  
→ Order.create (executionId assigned)  
→ OrderRepository.save  
→ Outbox.publish  
→ ExecutionPipeline (Claim → Execute → Commit)

---