# Stage 2 Completion Checklist (Execution & Lifecycle)

## 1. Event Backbone (Ядро системы)
- [ ] **ExecutionEngine** → публикует `OrderFilledEvent`
- [ ] **ExecutionEngine** → публикует `OrderRejectedEvent`
- [ ] **TradeService** → публикует `TradeCreatedEvent`
- [ ] **CandleCloseEvent** → триггерит `EquityService`
- [ ] *Требование*: Только `ApplicationEventPublisher`, никаких прямых вызовов между Ledger/OMS/Position.

## 2. Event Model + Idempotency (Критично)
- [ ] Создать `OrderFilledEvent`, `OrderRejectedEvent`, `TradeCreatedEvent` с `eventId (UUID)`.
- [ ] Защита от дублей: UNIQUE constraint на `trades(external_execution_id)` и `orders(client_order_id, status)`.
- [ ] *Правило*: Любое событие должно быть safe to replay.

## 3. Ledger Layer (Trade → Position)
- [ ] **TradeService**: Idempotent insert по `external_execution_id`, публикация `TradeCreatedEvent`.
- [ ] **PositionService**: Реализация как State Reducer (`Position = reduce(Position, Trade)`).
- [ ] **Concurrency**: `@Version` (Optimistic Locking) в `PositionEntity`.
- [ ] Обновление Position строго через `TradeCreatedEvent`.

## 4. OMS (Order State Machine)
- [ ] Перевод OMS в event-driven state machine.
- [ ] Статусы: `NEW` → `SENT` → `FILLED` / `REJECTED`.
- [ ] Обновление статусов ТОЛЬКО через события.

## 5. Equity System (Analytics Layer)
- [ ] **EquityService**: Расчет `Equity = Balance + UnrealizedPnL`.
- [ ] Триггеры: `TradeCreatedEvent` (real-time) и `CandleCloseEvent` (snapshot).
- [ ] Хранилище: таблица `equity_snapshots`.

## 6. Hardening
- [ ] Transactional Outbox (гарантия публикации после commit).
- [ ] UNIQUE constraints в БД (Flyway migration).

## 7. E2E Full Flow Test
- [ ] Тест на 100 свечей: Signal → Order → Trade → Position → Equity.
- [ ] Chaos checks: дубликаты событий, replay, рестарт системы.

---
**Definition of Done**: Система переживает дубли, нет прямых вызовов в Ledger слое, FullFlow тест зеленый.
