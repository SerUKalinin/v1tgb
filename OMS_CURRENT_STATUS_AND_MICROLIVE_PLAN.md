````markdown
# OMS CURRENT STATUS — FINANCIAL CORE → INFRA → MICRO-LIVE

Дата фиксации: 2026-09-25

## 0. Архитектурные контракты

Следующие документы являются базовыми контрактами системы:

- `SYSTEM_CONTRACT.md` — IMMUTABLE
- `STATE_MACHINE_CONTRACT.md`
- `EXECUTION_ENGINE_CONTRACT.md`
- `IDENTITY_SSOT_MANIFEST.md`

Изменения production-кода и тестов не должны нарушать их инварианты.

---

# 1. ЦЕЛЬ СИСТЕМЫ

Проект — deterministic autonomous 24/7 crypto OMS / execution engine.

Целевой путь:

```text
Backtest
   ↓
Paper
   ↓
Testnet
   ↓
Micro-Live
   ↓
Scale
````

Финансовое ядро:

```text
Signal
  ↓
Risk Decision
  ↓
Risk Reservation
  ↓
Order
  ↓
Transactional Outbox
  ↓
Execution Claim
  ↓
EXECUTING
  ↓
Exchange I/O
  ↓
Execution Commit / Recovery
  ↓
Trade / Position / Equity projections
```

Основные инварианты:

* Risk — обязательный gateway для создания ордера.
* Database — source of truth.
* `executionId` — глобальный idempotency key execution lifecycle.
* Один `Order` → один `executionId` → один lifecycle.
* Exchange I/O не выполняется внутри DB transaction.
* `PENDING_EXECUTION → EXECUTING` происходит до exchange I/O.
* Terminal states immutable.
* UNKNOWN используется только как неопределённость результата.
* Recovery обязана быть restart-safe.
* Partial fill является cumulative exchange state.
* Повтор одинакового exchange checkpoint должен быть idempotent.
* Financial downstream state должен строиться через Outbox/event-driven flow.

---

# 2. ЧТО УЖЕ ЗАКРЫТО

## 2.1 Risk / Reservation

### ✅ Risk → Reservation → Order → Outbox

Подтверждено существующими integration tests.

Проверено:

* risk decision;
* capital reservation;
* создание Order;
* persistence;
* transactional outbox;
* DB-backed RiskState.

---

## 2.2 Exchange Feasibility Layer

### ✅ EFL

Есть:

* `ExchangeFeasibilityPort`
* `ExchangeMetadataService`
* Binance constraints validation
* normalization
* `LOT_SIZE`
* `MIN_NOTIONAL`
* `PRICE_FILTER`

EFL находится до execution и не переносит exchange validation внутрь execution engine.

---

# 3. STATE MACHINE / EXECUTION IDENTITY

## ✅ D1 — ExecutionIdentityMismatchTest

Проверяет:

* payload executionId != foreign context executionId;
* чужой execution context не может claim Order;
* чужой context не может вызвать domain execution transition.

Ожидаемый результат:

```text
claim = rejected
exchange = not called
Order = unchanged
executionId = unchanged
```

---

## ✅ D2 — ExecutionIdentityPersistenceGuardTest

Проверяет persistence-level guard:

```text
persisted executionId
        !=
incoming ExecutionContext executionId
```

Результат:

```text
claim = rejected
status = PENDING_EXECUTION
executionId = unchanged
executionAttempts = unchanged
```

---

## ✅ D3 — ExecutionIdentityRetrySemanticsTest

Проверяет identity semantics:

### Transport retry

```text
same executionId
attemptNumber + 1
```

### New business attempt

```text
new deterministic executionId
same causationId
```

---

## ✅ D4 — ExecutionContextIdentityConsistencyTest

Проверяет полную identity chain:

```text
Signal
  ↓
Order
  ↓
ExecutionContext
```

Сверяются:

* signalId
* orderId
* executionId

Mismatch блокируется до repository claim.

---

## ✅ D5 — ExecutionCommitIdentityConsistencyTest

Проверяет commit boundary.

Identity validation выполняется до state transition.

Чужой execution context не может:

```text
FILLED
REJECTED
CANCELED
PARTIAL
```

чужого Order.

---

## ✅ ExecutionCommitHappyPathTest

Проверяет нормальный execution commit:

```text
EXECUTING
   ↓
FILLED
   ↓
Risk settlement
   ↓
Order persistence
   ↓
completion Outbox
   ↓
execution lock
```

---

# 4. CRASH / RECOVERY

## ✅ E1 — ExecutionCrashAfterExchangeSubmissionTest

Проверяет:

```text
claim
 ↓
exchange submission
 ↓
commit crash
```

При повторной доставке:

```text
exchange placeOrder() не вызывается второй раз
```

---

## ✅ E2 — ExecutionCrashPersistenceRecoveryTest

Проверяет persistence после commit crash:

```text
PENDING_EXECUTION
        ↓
EXECUTING
        ↓
commit crash
```

После crash:

```text
status = EXECUTING
executionAttempts = 1
executionId = unchanged
```

Повторный `ORDER_CREATED` не создаёт второй execution.

---

## ✅ E3 — ExecutionCrashThenReconciliationIntegrationTest

Критический recovery test.

Проверено:

```text
exchange side-effect
       ↓
commit crash
       ↓
stale EXECUTING
       ↓
RECOVERING
       ↓
authoritative exchange FILLED
       ↓
incremental settlement
       ↓
FILLED
```

Дополнительно проверено:

* executionId не меняется;
* exchange не вызывается второй раз;
* Risk reservation уменьшается корректно;
* `FILLED:qty@price` settlement checkpoint идемпотентен;
* повторная reconciliation = no-op.

Result:

```text
BUILD SUCCESSFUL
```

---

# 5. PARTIAL FILL / RECOVERY

## ✅ RecoveryCumulativeSettlementIntegrationTest

Проверено:

```text
0.3 @ 100
   ↓
0.6 @ 100
   ↓
1.0 @ 100
```

Settlement:

```text
0.3 → 30
0.6 → +30
1.0 → +40
```

Повтор одинакового cumulative state:

```text
delta = 0
```

Также проверено изменение цены:

```text
0.3 @ 100
0.6 @ 105
```

и защита от уменьшения cumulative quantity.

---

## ✅ PartialFillThenCancelRiskStateIntegrationTest

Проверено:

```text
reservation = 100
partial 0.3
reservation = 70
cancel
reservation = 0
```

При повторном CANCEL:

```text
no-op
```

---

## ✅ PartialFillThenRejectRiskStateIntegrationTest

Проверено аналогично:

```text
100 reserved
→ partial 0.3
→ reserved 70
→ REJECTED
→ reserved 0
```

Повторный REJECT:

```text
no-op
```

---

## ✅ PartialFillThenFilledRecoveryIntegrationTest

Проверено:

```text
partial fill
    ↓
recovery
    ↓
full FILLED
```

Settlement не удваивается.

---

# 6. UNKNOWN / RECOVERY MATRIX

Закрыты тестами:

### ✅ UNKNOWN → FILLED

### ✅ UNKNOWN → PARTIALLY_FILLED

### ✅ UNKNOWN → REJECTED

### ✅ UNKNOWN → CANCELED

### ✅ UNKNOWN → UNKNOWN

Для всех сценариев проверяется:

* lifecycle;
* executionId;
* Risk settlement/release;
* повторная reconciliation;
* idempotency.

---

# 7. RECONCILIATION CONCURRENCY

## ✅ ConcurrentUnknownRecoverySettlementIntegrationTest

Проверено:

```text
worker A ─┐
          ├─ UNKNOWN Order
worker B ─┘
```

Только один worker получает reconciliation ownership.

Другой worker:

```text
skip
```

Exchange status запрашивается один раз.

Settlement выполняется один раз.

---

## ✅ StaleExecutionCommitTest

Проверено:

```text
execution A
    ↓
authoritative recovery
    ↓
terminal state
    ↓
stale execution A tries commit
```

Результат:

```text
ExecutionOwnershipException
```

Старый execution не может изменить authoritative state.

---

# 8. ЧТО СЕЙЧАС В РАБОТЕ

Новая ветка:

```text
test/execution-commit-atomicity
```

## F1 — Execution Commit Atomicity

Статус:

```text
TODO
```

Нужно доказать атомарность:

```text
Order mutation
+
Risk settlement
+
completion Outbox
+
ExecutionLock
```

в рамках одного commit transaction.

При искусственном failure:

```text
ROLLBACK
```

должны откатиться:

* Order mutation;
* Risk settlement;
* completion event;
* execution lock finalization.

При этом ранее committed execution claim должен остаться:

```text
Order = EXECUTING
```

---

# 9. ОСТАВШИЕСЯ CORE TESTS

## F2 — Completion Outbox Retry / Idempotency

Сценарий:

```text
commit
 ↓
ORDER_EXECUTED
 ↓
consumer crash
 ↓
outbox retry
```

Нужно доказать отсутствие:

```text
duplicate Trade
duplicate Position mutation
duplicate Equity mutation
```

---

## F3 — Outbox Causal Ordering

Проверить:

```text
event #1 = FAILED
event #2 = NEW
```

для одного aggregate.

`event #2` не должен обработаться раньше `event #1`.

---

## F4 — Trade / Position / Equity Exactly-Once

Цепочка:

```text
ORDER_EXECUTED
      ↓
Trade
      ↓
TRADE_CREATED
      ↓
Position
      ↓
Equity
```

Нужно доказать:

```text
one execution fact
→ one trade
→ one position projection
→ one equity projection
```

Особенно для:

* duplicate ORDER_EXECUTED;
* duplicate TRADE_CREATED;
* partial fill #1;
* partial fill #2;
* final fill.

---

# 10. RISK TESTS, КОТОРЫЕ ЕЩЁ НУЖНЫ

## R1 — Concurrent Risk Reservation

Сценарий:

```text
balance = 150

A reserves 100
B reserves 100
```

Нельзя получить:

```text
reserved = 200
available < 0
```

---

## R2 — SELL Settlement Matrix

Нужно покрыть:

```text
SELL
 ├─ partial fill
 ├─ full fill
 ├─ cancel
 ├─ reject
 ├─ UNKNOWN
 ├─ recovery
 └─ repeated recovery
```

Проверять:

* position;
* capital;
* trade;
* equity;
* idempotency.

---

## R3 — Fees / Actual Execution Values

Нужно проверить согласованность:

```text
executedQty
averagePrice
fee
```

между:

```text
Risk
Trade
Position
Equity
```

---

# 11. RESTART / CRASH MATRIX

Перед infra необходимо закрыть restart safety.

Минимум:

```text
A. restart after Risk reservation
B. restart after Order persistence
C. restart after Outbox publication
D. restart after execution claim
E. restart after exchange submission
F. restart after partial fill
G. restart in UNKNOWN
H. restart during RECOVERING
I. restart after Risk settlement
J. restart after completion event
```

Для каждого:

```text
restart
 ↓
bootstrap
 ↓
recovery
 ↓
reconciliation
 ↓
final state
```

Нельзя получить:

```text
double execution
double reservation consumption
double trade
double position update
double equity update
```

---

# 12. STARTUP / BOOTSTRAP GATE

Должен быть доказан lifecycle:

```text
INITIALIZING
    ↓
RISK_RECOVERING
    ↓
RECONCILING
    ↓
MARKET_WARMING
    ↓
READY
    ↓
TRADING_ENABLED
```

При невозможности подтвердить financial truth:

```text
HALT
```

Система не должна начинать торговлю при:

* unresolved UNKNOWN;
* stale EXECUTING;
* Risk recovery failure;
* reconciliation drift;
* missing critical market metadata.

---

# 13. FINAL ARCHITECTURE GATE

После core tests:

```text
./gradlew clean test
```

Проверить:

## Domain

Нет:

* Spring;
* JPA;
* transaction annotations;
* repository implementation;
* exchange I/O;
* UUID generation.

## Identity

Нет:

* mutation of executionId;
* identity fallback;
* foreign execution context;
* generation outside IdentityFactory.

## Execution

Exchange I/O:

```text
OUTSIDE DB TX
```

## Persistence

Application/domain не обходят domain ports напрямую через JPA.

## Outbox

Financial downstream flow идёт через Outbox.

## State machine

Все transitions соответствуют:

`STATE_MACHINE_CONTRACT.md`

---

# 14. INFRASTRUCTURE — ПОСЛЕ CORE GATE

## I1 — Production PostgreSQL

Нужно:

* PostgreSQL production;
* dedicated DB user;
* strong password;
* persistent volume;
* Flyway migrations;
* no `ddl-auto=create`;
* connection pool limits;
* indexes;
* backup.

---

## I2 — Backup / Restore

Нужно реально проверить:

```text
DB backup
   ↓
destroy/replace DB
   ↓
restore
   ↓
application startup
   ↓
state consistent
```

---

## I3 — Secrets

Убрать из runtime:

* Binance API keys из code/config;
* Telegram secrets;
* DB password;
* production credentials.

Secrets должны передаваться через environment/secret management.

---

## I4 — Runtime / Restart

Нужно:

* automatic restart;
* graceful shutdown;
* startup recovery;
* readiness;
* liveness;
* JVM memory limits;
* DB pool limits.

---

## I5 — Monitoring / Alerting

Минимальные alerts:

```text
HALT
UNKNOWN
RECOVERING stuck
stale EXECUTING
Outbox DEAD
DB unavailable
exchange unavailable
rate limit
reconciliation drift
```

---

# 15. TESTNET GATE

Полный цикл на реальном testnet:

```text
Signal
 ↓
Risk
 ↓
Reservation
 ↓
Order
 ↓
Outbox
 ↓
Claim
 ↓
Exchange
 ↓
Fill
 ↓
Commit
 ↓
Trade
 ↓
Position
 ↓
Equity
```

Нужны реальные проверки:

* FILLED;
* PARTIALLY_FILLED;
* CANCELED;
* REJECTED;
* UNKNOWN;
* exchange timeout;
* exchange API error;
* duplicate event;
* application restart;
* reconciliation.

---

# 16. PAPER GATE

Paper должен идти на:

```text
LIVE market data
+
real strategy
+
real risk
-
real capital
```

Нужно накопить статистику:

* trades;
* execution latency;
* rejection rate;
* slippage;
* PnL;
* drawdown;
* exposure;
* frequency;
* system incidents.

Стратегия не должна меняться во время validation window без повторной валидации.

---

# 17. MICRO-LIVE GATE

Micro-live разрешается только после прохождения:

```text
CORE
 ✅ execution identity
 ✅ state machine
 ✅ risk settlement
 ✅ recovery
 ✅ reconciliation
 ⬜ commit atomicity
 ⬜ downstream exactly-once
 ⬜ restart matrix
 ⬜ final architecture gate

INFRA
 ⬜ production PostgreSQL
 ⬜ backup / restore
 ⬜ secrets
 ⬜ monitoring
 ⬜ alerting
 ⬜ health/readiness
 ⬜ automatic restart

VALIDATION
 ⬜ testnet full cycle
 ⬜ testnet chaos/recovery
 ⬜ paper validation
 ⬜ release checklist
```

---

# 18. MICRO-LIVE PARAMETERS

Micro-live начинается с минимального капитала и жёстких ограничений:

```text
max risk per trade
max daily loss
max drawdown
max simultaneous exposure
max capital allocation
max order frequency
```

При нарушении safety conditions:

```text
TRADING_ENABLED
      ↓
HALT
```

Без автоматического расширения лимитов.

---

# 19. ЧТО НЕ ДЕЛАЕМ ДО MICRO-LIVE

Не расширяем систему без необходимости:

* Kafka;
* multi-exchange scaling;
* distributed cluster;
* high-throughput optimization;
* сложный distributed locking;
* новые trading strategies;
* feature expansion.

Приоритет:

```text
Correctness
   ↓
Determinism
   ↓
Recovery
   ↓
Observability
   ↓
Testnet
   ↓
Paper
   ↓
Micro-Live
   ↓
Scale
```

---

# 20. АКТУАЛЬНЫЙ STATUS

## CLOSED

```text
D1 ✅
D2 ✅
D3 ✅
D4 ✅
D5 ✅

E1 ✅
E2 ✅
E3 ✅

Cumulative settlement ✅
Partial fill recovery ✅
UNKNOWN recovery matrix ✅
Concurrent reconciliation ✅
Stale execution protection ✅
Execution identity SSOT ✅
```

## CURRENT

```text
F1 ⬜
```

Branch:

```text
test/execution-commit-atomicity
```

## NEXT

```text
F2 ⬜
F3 ⬜
F4 ⬜

R1 ⬜
R2 ⬜
R3 ⬜

C1 ⬜
C2 ⬜
C3 ⬜

A1 ⬜
A2 ⬜

I1 ⬜
I2 ⬜
I3 ⬜
I4 ⬜
I5 ⬜

T1 ⬜
T2 ⬜
T3 ⬜

P1 ⬜
P2 ⬜

MICRO-LIVE ⬜
```

---

# 21. DEFINITION OF DONE ДО MICRO-LIVE

Micro-live НЕ считается разрешённым, пока одновременно не выполнено:

```text
1. Financial core deterministic
2. Execution identity deterministic
3. State machine deterministic
4. Risk settlement idempotent
5. Reconciliation authoritative
6. Restart-safe
7. Outbox retry-safe
8. Downstream projections idempotent
9. PostgreSQL production-ready
10. Backup/restore tested
11. Secrets secured
12. Monitoring/alerts working
13. Binance Testnet full cycle passed
14. Testnet chaos/recovery passed
15. Paper validation passed
16. Kill-switch tested
17. Release checklist completed
```

Главный принцип:

```text
NO TRADING WHILE TRUTH IS UNCERTAIN.

NO EXECUTION WITHOUT IDEMPOTENCY.

NO ORDER WITHOUT RISK.

NO MICRO-LIVE WITHOUT RECOVERY.
```

```

**Имя файла:** `OMS_CURRENT_STATUS_AND_MICROLIVE_PLAN.md`

Сейчас этот файл хорошо отделяет уже доказанное от будущего и его можно дальше обновлять по одному пункту: `F1 ✅`, затем `F2 ✅` и т.д.
```
