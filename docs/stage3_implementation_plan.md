# Stage 3: Final Task Breakdown (Ready to Implement)

## 🔴 1. EVENT STORE (Ядро системы)
- [ ] **1.1 Миграция БД `risk_events`**
    - Добавить поля: `event_id` (UUID UNIQUE), `aggregate_id` (VARCHAR), `version` (BIGINT), `event_type` (VARCHAR), `payload` (JSONB), `created_at` (TIMESTAMP).
    - Constraint: `UNIQUE (aggregate_id, version)`.
    - Индексы: `(aggregate_id, version)`, `(event_id)`.
- [ ] **1.2 Snapshot таблица**
    - `risk_snapshots`: `aggregate_id`, `last_version`, `state_json`, `created_at`.

## 🔴 2. EVENT ORDERING & CONSISTENCY
- [ ] **2.1 Optimistic versioning**
    - При `publish(event)`: загрузка последнего `version` -> `version + 1` -> запись в БД.
    - Защита от race condition через UNIQUE constraint.
- [ ] **2.2 Idempotency layer**
    - Проверка `event_id`: если уже существует -> IGNORE.

## 🔴 3. CORE PIPELINE (ЗАПРЕТ ОБХОДА)
- [ ] **3.1 Жёсткий refactor (КРИТИЧНО)**
    - Удалить ВСЕ: `RiskStateStore.save()`, `RiskStateStore.update()`.
    - Оставить ТОЛЬКО: `RiskEngine.publish(event)`.
- [ ] **3.2 Pipeline внутри RiskEngine**
    - `publish(event)` должен делать: `persist(event)` -> `apply(event)` -> `update cache` (RiskStateStore internal only).
    - Запрет внешнего доступа к store write methods.

## 🔴 4. STATE PROJECTION (REDUCER RULE)
- [ ] **4.1 RiskStateReducer**
    - Строго чистая функция: `State = f(State, Event)`. Никаких side effects.

## 🔴 5. SNAPSHOT SYSTEM
- [ ] **5.1 Snapshot creation**
    - Стратегия: каждые N событий (например 500) или time-based (например 5 минут).
    - Сохранять: `state_json`, `last_version`.
- [ ] **5.2 Recovery flow**
    - Загрузка snapshot + дочитка событий: `version > snapshot.last_version`.

## 🔴 6. KILL SWITCH (EVENT-DRIVEN ONLY)
- [ ] **6.1 Event**
    - `TradingHaltedEvent`.
- [ ] **6.2 Projection rule**
    - `RiskState.isHalted = true` только через reducer.
- [ ] **6.3 Guard**
    - В `RiskEngine.publish()`: если `state.isHalted == true` -> reject all new events.

## 🔴 7. MARKET DATA FILTERING
- [ ] **7.1 Pre-risk filtering (обязательно)**
    - В `MarketDataService`: `delta >= 0.1%`.
    - `RiskEngine` НЕ должен фильтровать цены.

## 🔴 8. CONCURRENCY & ORDER GUARANTEE
- [ ] **8.1 version locking**
    - Гарантировать последовательность `version++`.
    - При параллельных `publish`: optimistic locking retry.

## 🔴 9. TEST SUITE (КРИТЕРИЙ ГОТОВНОСТИ)
- [ ] **9.1 Deterministic Replay Test**: одинаковый event log -> одинаковый state.
- [ ] **9.2 Snapshot Integrity Test**: snapshot + tail events == full replay.
- [ ] **9.3 Kill Switch Test**: `TradingHaltedEvent` -> блокировка всех publish.
- [ ] **9.4 Concurrency Test**: параллельные publish, нет дыр в version, нет дубликатов `event_id`.

## 🔴 10. DEFINITION OF DONE (ГОТОВНОСТЬ STAGE 3)
- [ ] Event Store — единственный источник истины.
- [ ] `RiskEngine.publish` — единственная точка входа.
- [ ] Нет прямых update/save состояния риска.
- [ ] Replay полностью детерминирован.
- [ ] Snapshot работает и ускоряет recovery.
- [ ] Kill-switch event-driven.
- [ ] Concurrency-safe versioning работает.
- [ ] Тесты проходят 100%.
