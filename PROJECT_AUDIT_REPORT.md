# Отчет об аудите разработки торговой системы 

## Введение
Данный документ содержит детальный разбор выполненных работ по Этапам 0, 1 и 2. Система эволюционировала от простого протокола до отказоустойчивой событийно-ориентированной архитектуры (Event-Driven Architecture).

---

## Этап 0: Фундамент и Инфраструктура (Foundation)

### Что должны были сделать:
*   Создать каркас Spring Boot приложения.
*   Настроить подключение к базе данных (PostgreSQL).
*   Интегрировать Flyway для управления миграциями.
*   Реализовать базовый клиент для получения данных с биржи (Binance API).
*   Определить основные доменные модели: `Candle`, `Order`, `Trade`.

### Что было сделано (Результат):
*   **Инфраструктура**: Развернуто приложение на Spring Boot 3.3.4 с поддержкой профилей (`test`, `backtest`, `prod`).
*   **Схема БД**: Реализованы первые миграции (V1), создавшие таблицы для ордеров и сделок.
*   **Market Data**: Реализован `BinanceMarketDataClient` на основе WebClient, способный загружать исторические свечи и синхронизировать время с биржей.
*   **Scheduler**: Настроен `MarketScheduler` для периодического опроса данных.

---

## Этап 1: Синхронный Пайплайн и Базовая Логика (MVP)

### Что должны были сделать:
*   Реализовать `TradingPipeline` для последовательной обработки данных.
*   Создать первую торговую стратегию (`SimpleStrategy`).
*   Внедрить риск-менеджмент (`RiskManager`).
*   Реализовать сервис позиций (`PositionService`) для отслеживания текущего баланса активов.
*   Обеспечить сохранение ордеров в БД.

### Что было сделано (Результат):
*   **Pipeline**: Создан линейный процесс: `Свеча → Стратегия → Риски → Исполнение → Позиция`.
*   **Стратегия**: Реализована `SimpleStrategy` на базе индикатора EMA20.
*   **Risk Management**: Внедрен `DefaultRiskManager`, проверяющий допустимый объем сделки.
*   **Execution**: Создан `BacktestExecutionEngine` для имитации торгов без реальных денег.
*   **Persistence**: Настроены JPA репозитории для `OrderEntity` и `TradeEntity`.

---

## Этап 2: Event-Driven Architecture & Ledger (Hardening)

### Что должны были сделать:
*   Перейти от прямых вызовов методов к обмену событиями (Application Events).
*   Реализовать **Ledger (Реестр)**: сделки как единственный источник истины для позиций.
*   Обеспечить **Идемпотентность**: защита от повторной обработки одного и того же сигнала/события.
*   Реализовать систему **Equity**: расчет стоимости портфеля в реальном времени.
*   Синхронизировать схему БД с кодом (исправление типов данных).

### Что было сделано (Результат):
*   **Event Backbone**:
    *   `SignalEvent`: генерируется стратегией.
    *   `OrderFilledEvent`: публикуется движком исполнения.
    *   `TradeCreatedEvent`: фиксирует факт сделки в Ledger.
*   **Position Reducer**: `PositionService` полностью переписан. Теперь он не "принимает команды", а "слушает сделки" и обновляет состояние. Добавлена защита через `lastTradeId` и `@Version` (Optimistic Locking).
*   **Analytics**: Реализован `EquityService`, который создает снимки состояния (`equity_snapshots`) после каждой сделки и по расписанию.
*   **Data Integrity**:
    *   Все временные метки переведены на `TIMESTAMP WITH TIME ZONE`.
    *   Типы ID в таблице сделок приведены к `BIGSERIAL` (Long).
    *   Добавлен детерминированный `clientOrderId` для предотвращения дублей на стороне биржи.
*   **Verification**: Проведен "Хаос-тест" (`EventDrivenChaosIntegrationTest`), подтвердивший, что система игнорирует дубликаты событий и корректно восстанавливает состояние из истории сделок.

---

## Текущий статус: ГОТОВО К ЭТАПУ 3
Система обладает промышленным уровнем надежности данных. Цепочка событий замкнута, Ledger работает корректно, позиции восстанавливаются автоматически.

**Следующий шаг**: Масштабирование (поддержка множества символов и стратегий одновременно).





ТЕКУЩЕЕ СОСТОЯНИЕ В ПАМЯТИ (система)
🧠 Архитектурная база (зафиксирована)

Ты работаешь в рамках:

✔ Trading OMS + risk engine + event-driven execution
Risk → Reserve → Order → Execution → Outbox → Reconciliation
DDD + event-driven + event sourcing элементы
Hybrid RiskState:
JSONB snapshot
append-only reservation ledger
🧱 Core Stabilization Phase (обязательная перед инфраструктурой)

Зафиксировано как жёсткий pre-production этап:

1. Order State Machine (СЕЙЧАС АКТИВНО)
   OrderStateTransitionPolicy = single source of truth
   устранение дублирующей логики переходов
2. Risk Engine
   строго детерминированный gate
   без side effects
3. Execution Layer
   ExecutionPort abstraction (exchange-agnostic)
   removal of business logic from handlers
4. Reconciliation
   read-only / correction layer
   no mutation of domain state
5. Idempotency
   unified global layer
   protect claim / commit / reconciliation
6. Transaction model
   unit-of-work стабилизация
   убрать хаотичный REQUIRES_NEW
   📍 ТЕКУЩАЯ ФАЗА (КРИТИЧЕСКИ ВАЖНО)
   👉 ФАЗА 3: SINGLE ORDER STATE MACHINE
   Мы ЗАФИКСИРОВАЛИ:
   переходы состояния сейчас централизуются
   OrderStateTransitionPolicy = ядро консистентности
   📌 ЧТО УЖЕ СДЕЛАНО (по памяти + твоим логам)
   ✔ 1. Bootstrap lifecycle полностью реализован
   INITIALIZING → TRADING_ENABLED
   deterministic startup sequence
   ✔ 2. Outbox pipeline работает
   ORDER_CREATED → EXECUTION → ORDER_EXECUTED
   event routing работает стабильно
   ✔ 3. Risk recovery реализован (Hybrid model)
   snapshot + replay
   ledger-based rebuild
   ✔ 4. Exchange adapter работает (Binance testnet)
   orders отправляются
   ошибки обрабатываются
   ✔ 5. Market data pipeline
   candle detection
   strategy signal generation
   ✔ 6. Position projection exists
   PositionService + rebuild service
   ⚠️ ЧТО ЕЩЁ НЕ ЗАВЕРШЕНО (КЛЮЧЕВОЕ)
   ❌ 1. OrderStateTransitionPolicy НЕ зафиксирован как единственный источник истины

Сейчас:

transition logic всё ещё размазан
execution + risk + handler влияют на состояние

👉 Это главный архитектурный долг

❌ 2. Execution finality не унифицирована

Проблема из логов:

REJECTED orders всё ещё идут в PositionService
нет строгого distinction:
FINAL (FILLED / REJECTED / CANCELED)
vs
INTERMEDIATE (PENDING / SENT / EXECUTING)
❌ 3. RiskState replay inconsistency

Из логов:

“No active reservation” при release
snapshot = 0, но ledger есть

👉 значит:

либо snapshot не синхронизирован с ledger
либо ordering replay нарушен
❌ 4. Risk vs Exchange divergence
Risk APPROVES
Exchange REJECTS (insufficient balance)

👉 нет pre-trade reconciliation barrier

❌ 5. System has duplicate risk recovery trigger
RiskStateRecoveryService запускается 2 раза в lifecycle
📊 СТАТУС ПО ФАЗАМ
Фаза	Статус
Bootstrap lifecycle	✅ DONE
Outbox pipeline	✅ DONE
Risk engine gate	⚠️ PARTIAL
Execution layer	⚠️ LEAKING DOMAIN LOGIC
Reconciliation	⚠️ PARTIAL
Idempotency layer	⚠️ WORKING BUT FRAGILE
Order State Machine	❗ IN PROGRESS (главный фокус)
🧭 ГДЕ МЫ СЕЙЧАС (точка истины)

Ты сейчас находишься в следующей точке:

Core Stabilization Phase 3 — Order State Machine Consolidation

И это не “рефакторинг”, а:

👉 формализация конечной модели состояния ордера
⚠️ СУПЕР ВАЖНЫЙ ВЫВОД

Система уже:

не прототип
не MVP
не сервис

👉 это уже event-driven trading engine, у которого:

проблема не в коде
а в отсутствии единой модели конечности состояния (finality model)