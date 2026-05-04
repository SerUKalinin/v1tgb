🧭 Core Stabilization — Implementation Plan (EFL First)
📊 Текущий статус
Core correctness: 85–90%
Production readiness: 60–70%
Capital safety: ~55%
Главный блокер: ❌ Exchange Feasibility Layer отсутствует
🥇 PHASE A — Exchange Feasibility Layer (EFL) [CRITICAL]
🎯 Цель

Гарантировать, что:

ни один ордер не нарушает правила биржи
Risk Engine принимает решения с учётом реальных ограничений
A.1 — Domain Contract (EFL API)
✔ Сделать:
ExchangeFeasibilityPort
FeasibilityResult
📌 Требования:
immutable model
no side effects
deterministic
A.2 — Exchange Metadata Layer
✔ Сделать:
SymbolConstraints
ExchangeMetadataService
in-memory cache (ConcurrentHashMap<String, SymbolConstraints>)
📌 Источник:
Binance /api/v3/exchangeInfo
A.3 — Metadata Lifecycle
✔ Сделать:
Startup preload (ApplicationReadyEvent)
Scheduled refresh (каждые 6 часов)
Lazy fallback (если символ отсутствует)
Fail strategy:
keep old cache
hard reject если metadata отсутствует
A.4 — Binance Constraint Validator
✔ Сделать:
BinanceConstraintValidator implements ExchangeFeasibilityPort
📌 Логика:
normalize:
floorToStep(quantity)
roundToTick(price)
validate:
minQty
minNotional
price precision
A.5 — Integration into Risk Engine
✔ Сделать:
внедрить в DefaultRiskManager.approveSignal()
📌 Flow:
Signal → Risk sizing → EFL validate → ApprovedOrder
❗ Правило:
если feasible == false → Optional.empty()
A.6 — Invariants после внедрения
в Outbox попадают только валидные ордера
quantity всегда кратен stepSize
price всегда кратен tickSize
нет Binance REJECT по constraints
🥈 PHASE B — Finality Enforcement (Post-EFL)
🎯 Цель

Исключить влияние неисполненных ордеров на систему

B.1 — Position Projection Fix
✔ Сделать:
убедиться что REJECTED / CANCELED не попадают в projection
финализировать фильтр статусов
B.2 — Risk Funds Release
✔ Сделать:
riskEngine.releaseFunds(orderId) при:
REJECTED
CANCELED
EXPIRED
B.3 — Position Rebuild Safety
✔ Сделать:
PositionRebuildService.rebuildAllPositions()
периодический запуск (scheduler)
🥉 PHASE C — Outbox Lifecycle Management
🎯 Цель

Сделать систему устойчивой к длительной работе

C.1 — TTL Cleanup
✔ Сделать:
удаление PROCESSED событий старше N дней
C.2 — Archival Strategy (optional)
✔ Сделать:
перенос старых событий в архивную таблицу
C.3 — Индексы
✔ Проверить:
(status, next_attempt_at)
(aggregate_id)
🟡 PHASE D — RiskState Hardening (Later)
✔ Сделать:
edge-case replay consistency
защита от partial recovery
duplicate replay handling
🚫 НЕ ДЕЛАТЬ ДО ЗАВЕРШЕНИЯ EFL
Kafka
WebSocket streaming
Prometheus / Grafana
Multi-exchange support
📊 Definition of Done (EFL)

EFL считается завершённым, если:

все ордера проходят exchange constraints до БД
отсутствуют ошибки:
LOT_SIZE
MIN_NOTIONAL
PRICE_FILTER
Risk Engine возвращает только валидные ApprovedOrder
Execution layer не выполняет валидацию
🚀 Execution Order
ExchangeMetadataService
BinanceConstraintValidator
Integration into RiskManager
Finality fixes
Outbox cleanup
💡 Финальный статус после Phase A
Before: system functional but unsafe
After: system capital-safe