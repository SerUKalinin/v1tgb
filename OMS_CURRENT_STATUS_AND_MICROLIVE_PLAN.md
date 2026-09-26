OMS CURRENT STATUS — FINANCIAL CORE → INFRA → MICRO-LIVE

Дата фиксации: 2026-09-26

0. Архитектурные контракты

Следующие документы являются базовыми контрактами системы:

SYSTEM_CONTRACT.md — IMMUTABLE

STATE_MACHINE_CONTRACT.md

EXECUTION_ENGINE_CONTRACT.md

IDENTITY_SSOT_MANIFEST.md

Изменения production-кода и тестов не должны нарушать их инварианты.

1. ЦЕЛЬ СИСТЕМЫ

Проект — deterministic autonomous 24/7 crypto OMS / execution engine.

Целевой путь:

Backtest
↓
Paper
↓
Testnet
↓
Micro-Live
↓
Scale

Финансовое ядро:

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

Основные инварианты:

Risk — обязательный gateway для создания ордера.

Database — source of truth.

executionId — глобальный idempotency key execution lifecycle.

Один Order → один executionId → один lifecycle.

Exchange I/O не выполняется внутри DB transaction.

PENDING_EXECUTION → EXECUTING происходит до exchange I/O.

Terminal states immutable.

UNKNOWN используется только как неопределённость результата.

Recovery обязана быть restart-safe.

Partial fill является cumulative exchange state.

Повтор одинакового exchange checkpoint должен быть idempotent.

Financial downstream state должен строиться через Outbox/event-driven flow.

2. ЧТО УЖЕ ЗАКРЫТО

2.1 Risk / Reservation

✅ Risk → Reservation → Order → Outbox

Подтверждено существующими integration tests.

Проверено:

risk decision;

capital reservation;

создание Order;

persistence;

transactional outbox;

DB-backed RiskState.

2.2 Exchange Feasibility Layer

✅ EFL

Есть:

ExchangeFeasibilityPort

ExchangeMetadataService

Binance constraints validation

normalization

LOT_SIZE

MIN_NOTIONAL

PRICE_FILTER

EFL находится до execution и не переносит exchange validation внутрь execution engine.

3. STATE MACHINE / EXECUTION IDENTITY

✅ D1 — ExecutionIdentityMismatchTest

Проверяет:

payload executionId != foreign context executionId;

чужой execution context не может claim Order;

чужой context не может вызвать domain execution transition.

Ожидаемый результат:

claim = rejected
exchange = not called
Order = unchanged
executionId = unchanged

✅ D2 — ExecutionIdentityPersistenceGuardTest

Проверяет persistence-level guard:

persisted executionId
!=
incoming ExecutionContext executionId

Результат:

claim = rejected
status = PENDING_EXECUTION
executionId = unchanged
executionAttempts = unchanged

✅ D3 — ExecutionIdentityRetrySemanticsTest

Проверяет identity semantics:

Transport retry

same executionId
attemptNumber + 1

New business attempt

new deterministic executionId
same causationId

✅ D4 — ExecutionContextIdentityConsistencyTest

Проверяет полную identity chain:

Signal
↓
Order
↓
ExecutionContext

Сверяются:

signalId

orderId

executionId

Mismatch блокируется до repository claim.

✅ D5 — ExecutionCommitIdentityConsistencyTest

Проверяет commit boundary.

Identity validation выполняется до state transition.

Чужой execution context не может изменить:

FILLED
REJECTED
CANCELED
PARTIAL

чужого Order.

✅ ExecutionCommitHappyPathTest

Проверяет нормальный execution commit:

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

4. CRASH / RECOVERY

✅ E1 — ExecutionCrashAfterExchangeSubmissionTest

Проверяет:

claim
↓
exchange submission
↓
commit crash

При повторной доставке:

exchange placeOrder() не вызывается второй раз

✅ E2 — ExecutionCrashPersistenceRecoveryTest

Проверяет persistence после commit crash:

PENDING_EXECUTION
↓
EXECUTING
↓
commit crash

После crash:

status = EXECUTING
executionAttempts = 1
executionId = unchanged

Повторный ORDER_CREATED не создаёт второй execution.

✅ E3 — ExecutionCrashThenReconciliationIntegrationTest

Критический recovery test.

Проверено:

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

Дополнительно проверено:

executionId не меняется;

exchange не вызывается второй раз;

Risk reservation уменьшается корректно;

FILLED:qty@price settlement checkpoint идемпотентен;

повторная reconciliation = no-op.

5. PARTIAL FILL / RECOVERY

✅ RecoveryCumulativeSettlementIntegrationTest

Проверено:

0.3 @ 100
↓
0.6 @ 100
↓
1.0 @ 100

Settlement:

0.3 → 30
0.6 → +30
1.0 → +40

Повтор одинакового cumulative state:

delta = 0

Также проверено изменение цены:

0.3 @ 100
0.6 @ 105

и защита от уменьшения cumulative quantity.

✅ PartialFillThenCancelRiskStateIntegrationTest

Проверено:

reservation = 100
partial 0.3
reservation = 70
cancel
reservation = 0

При повторном CANCEL:

no-op

✅ PartialFillThenRejectRiskStateIntegrationTest

Проверено аналогично:

100 reserved
→ partial 0.3
→ reserved 70
→ REJECTED
→ reserved 0

Повторный REJECT:

no-op

✅ PartialFillThenFilledRecoveryIntegrationTest

Проверено:

partial fill
↓
recovery
↓
full FILLED

Settlement не удваивается.

6. UNKNOWN / RECOVERY MATRIX

Закрыты тестами:

✅ UNKNOWN → FILLED

✅ UNKNOWN → PARTIALLY_FILLED

✅ UNKNOWN → REJECTED

✅ UNKNOWN → CANCELED

✅ UNKNOWN → UNKNOWN

Для всех сценариев проверяется:

lifecycle;

executionId;

Risk settlement/release;

повторная reconciliation;

idempotency.

7. RECONCILIATION CONCURRENCY

✅ ConcurrentUnknownRecoverySettlementIntegrationTest

Проверено:

worker A ─┐
├─ UNKNOWN Order
worker B ─┘

Только один worker получает reconciliation ownership.

Другой worker:

skip

Exchange status запрашивается один раз.

Settlement выполняется один раз.

✅ StaleExecutionCommitTest

Проверено:

execution A
↓
authoritative recovery
↓
terminal state
↓
stale execution A tries commit

Результат:

ExecutionOwnershipException

Старый execution не может изменить authoritative state.

8. EXECUTION COMMIT / OUTBOX / DOWNSTREAM

✅ F1 — Execution Commit Atomicity

Проверена атомарность execution commit.

В одной DB transaction согласованно коммитятся:

Order mutation
+
Risk settlement
+
completion Outbox
+
ExecutionLock finalization

При искусственном failure:

ROLLBACK

откатываются:

Order mutation;

Risk settlement;

completion event;

terminal execution lock.

При этом уже выполненный execution claim не откатывается:

Order = EXECUTING
ExecutionLock = EXECUTING

После recovery authoritative exchange state может быть закоммичен повторно тем же:

executionId

Дополнительно закреплено правило:

PARTIALLY_FILLED / UNKNOWN / SENT_TO_EXCHANGE
→ execution lock остаётся EXECUTING

ExecutionLock переходит в EXECUTED только при terminal state.

✅ F2 — Completion Outbox Retry / Idempotency

Проверено:

commit
↓
ORDER_EXECUTED
↓
consumer failure
↓
rollback
↓
Outbox retry

После повторной доставки:

one execution
→ one Trade
→ one completion event

Идемпотентность сохраняется после rollback/retry.

Проверено:

повтор ORDER_EXECUTED;

failed consumer;

повторная обработка;

completion idempotency;

отсутствие duplicate Trade.

✅ F3 — Outbox Causal Ordering

Для одного aggregate:

event #1
event #2

event #2 не может быть обработан до успешного завершения event #1.

Проверено:

#1 = FAILED
#2 = NEW

После retry:

#1
#1 retry
#2

При этом события разных aggregate могут обрабатываться независимо.

✅ F4 — Trade / Position / Equity Exactly-Once

Проверена downstream цепочка:

ORDER_EXECUTED
↓
Trade
↓
TRADE_CREATED
↓
Position
↓
Equity

Проверено:

duplicate ORDER_EXECUTED
duplicate TRADE_CREATED
consumer rollback
consumer retry

Результат:

one execution fact
→ one Trade
→ one Position projection
→ one Equity projection

Для Equity введена отдельная consumer-scoped idempotency.

Повторная обработка TRADE_CREATED не создаёт второй Equity snapshot.

9. TEST INFRASTRUCTURE

✅ Integration Test Stability

Полный regression build:

./gradlew clean build --no-daemon --max-workers=1

проходит успешно.

Для тестового профиля:

Exchange metadata startup refresh = disabled

Production default остаётся:

startup refresh = enabled

Также ограничены:

TestContext cache;

test JVM heap;

parallel forks;

lifetime отдельных test JVM.

Цель:

stable full-suite execution

без ложных OOM из-за накопления Spring contexts / connection pools / metadata cache.

10. RISK TESTS

✅ R1 — Concurrent Risk Reservation

Проверена атомарность конкурентного Risk reservation.

Сценарий:

balance = 1000

10 concurrent requests
каждый reserve = 600

Ожидаемо:

approved = 1
rejected = 9
reserved = 600
available = 400

Проверено через pessimistic DB locking:

SELECT ... FOR UPDATE

Race condition не приводит к:

reserved > available capital
available < 0

RiskState является DB-backed source of truth.

11. CURRENT CORE TARGET

🟡 R2 — SELL Settlement Matrix

Следующий основной Fix Loop.

Необходимо закрыть полный SELL lifecycle:

SELL
├─ partial fill
├─ full fill
├─ cancel
├─ reject
├─ UNKNOWN
├─ recovery
└─ repeated recovery

Для каждого сценария доказать согласованность:

Order
Risk capital
Trade
Position
Equity
Outbox
Idempotency
Execution identity

Особенно важно проверить:

SELL partial fill
→ position reduction
→ remaining position
→ capital settlement
→ final terminal settlement

и:

same cumulative exchange state
→ delta = 0

при повторной reconciliation.

12. NEXT CORE TARGET

R3 — Fees / Actual Execution Values

После R2 проверить согласованность фактических exchange values:

executedQty
averagePrice
fee
feeAsset
realized value

между:

Risk
Trade
Position
Equity

Нужно исключить расхождение между:

requested order values

и:

actual execution values

13. RESTART / CRASH MATRIX

После R2/R3 закрыть полный restart matrix.

Минимум:

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

Для каждого:

restart
↓
bootstrap
↓
recovery
↓
reconciliation
↓
final authoritative state

Нельзя получить:

double execution
double reservation consumption
double trade
double position update
double equity update

14. STARTUP / BOOTSTRAP GATE

Должен быть доказан lifecycle:

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

При невозможности подтвердить financial truth:

HALT

Система не должна начинать торговлю при:

unresolved UNKNOWN;

stale EXECUTING;

Risk recovery failure;

reconciliation drift;

missing critical market metadata.

Примечание:

Тестовая среда ранее показывала startup HALT при реальном запросе Binance balance с невалидным API key. Это тестовый/environmental concern и не является текущим core Fix Loop; production bootstrap gate всё равно должен оставаться fail-closed.

15. FINAL ARCHITECTURE GATE

После R2 / R3 / restart matrix:

./gradlew clean test

Проверить:

Domain

Нет:

Spring;

JPA;

transaction annotations;

repository implementation;

exchange I/O;

UUID generation.

Identity

Нет:

mutation of executionId;

identity fallback;

foreign execution context;

generation outside IdentityFactory.

Execution

Exchange I/O:

OUTSIDE DB TX

Persistence

Application/domain не обходят domain ports напрямую через JPA.

Outbox

Financial downstream flow идёт через Outbox.

State machine

Все transitions соответствуют:

STATE_MACHINE_CONTRACT.md

16. INFRASTRUCTURE — ПОСЛЕ CORE GATE

I1 — Production PostgreSQL

Нужно:

PostgreSQL production;

dedicated DB user;

strong password;

persistent volume;

Flyway migrations;

no ddl-auto=create;

connection pool limits;

indexes;

backup.

I2 — Backup / Restore

Нужно реально проверить:

DB backup
↓
destroy/replace DB
↓
restore
↓
application startup
↓
state consistent

I3 — Secrets

Убрать из runtime:

Binance API keys из code/config;

Telegram secrets;

DB password;

production credentials.

Secrets должны передаваться через environment/secret management.

I4 — Runtime / Restart

Нужно:

automatic restart;

graceful shutdown;

startup recovery;

readiness;

liveness;

JVM memory limits;

DB pool limits.

I5 — Monitoring / Alerting

Минимальные alerts:

HALT
UNKNOWN
RECOVERING stuck
stale EXECUTING
Outbox DEAD
DB unavailable
exchange unavailable
rate limit
reconciliation drift

17. TESTNET GATE

Полный цикл на реальном testnet:

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

Нужны реальные проверки:

FILLED;

PARTIALLY_FILLED;

CANCELED;

REJECTED;

UNKNOWN;

exchange timeout;

exchange API error;

duplicate event;

application restart;

reconciliation.

18. PAPER GATE

Paper должен идти на:

LIVE market data
+
real strategy
+
real risk
-
real capital

Нужно накопить статистику:

trades;

execution latency;

rejection rate;

slippage;

PnL;

drawdown;

exposure;

frequency;

system incidents.

Стратегия не должна меняться во время validation window без повторной валидации.

19. MICRO-LIVE GATE

Micro-live разрешается только после прохождения:

CORE
✅ execution identity
✅ state machine
✅ risk reservation
✅ concurrent Risk reservation
✅ risk settlement
✅ partial fill recovery
✅ cumulative settlement
✅ UNKNOWN recovery
✅ reconciliation concurrency
✅ stale execution protection
✅ execution commit atomicity
✅ completion Outbox retry / idempotency
✅ Outbox causal ordering
✅ Trade / Position / Equity exactly-once
⬜ SELL settlement matrix
⬜ Fees / actual execution values
⬜ restart / crash matrix
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

20. MICRO-LIVE PARAMETERS

Micro-live начинается с минимального капитала и жёстких ограничений:

max risk per trade
max daily loss
max drawdown
max simultaneous exposure
max capital allocation
max order frequency

При нарушении safety conditions:

TRADING_ENABLED
↓
HALT

Без автоматического расширения лимитов.

21. ЧТО НЕ ДЕЛАЕМ ДО MICRO-LIVE

Не расширяем систему без необходимости:

Kafka;

multi-exchange scaling;

distributed cluster;

high-throughput optimization;

сложный distributed locking;

новые trading strategies;

feature expansion.

Приоритет:

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

22. АКТУАЛЬНЫЙ STATUS

CLOSED

D1 ✅
D2 ✅
D3 ✅
D4 ✅
D5 ✅

E1 ✅
E2 ✅
E3 ✅

F1 ✅
F2 ✅
F3 ✅
F4 ✅

R1 ✅

Cumulative settlement ✅
Partial fill recovery ✅
UNKNOWN recovery matrix ✅
Concurrent reconciliation ✅
Stale execution protection ✅
Execution identity SSOT ✅

Integration test infrastructure ✅
Full clean build ✅

CURRENT

R2 🟡
SELL Settlement Matrix

Branch:

test/execution-commit-atomicity

NEXT

R3
Restart / Crash Matrix
Final Architecture Gate

I1
I2
I3
I4
I5

T1
T2
T3

P1
P2

MICRO-LIVE

CURRENT EXECUTION ORDER

R2
↓
R3
↓
Restart / Crash Matrix
↓
Final Architecture Gate
↓
Infrastructure Gate
↓
Testnet
↓
Paper
↓
Micro-Live

23. DEFINITION OF DONE ДО MICRO-LIVE

Micro-live НЕ считается разрешённым, пока одновременно не выполнено:

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

Главный принцип:

NO TRADING WHILE TRUTH IS UNCERTAIN.

NO EXECUTION WITHOUT IDEMPOTENCY.

NO ORDER WITHOUT RISK.

NO MICRO-LIVE WITHOUT RECOVERY.